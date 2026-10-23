package com.pokeclip.clip.mark;

import com.pokeclip.clip.broadcast.Broadcast;
import com.pokeclip.clip.broadcast.BroadcastRepository;
import com.pokeclip.clip.jumpcard.JumpCardService;
import com.pokeclip.clip.jumpcard.JumpCardService.RecordResult;
import com.pokeclip.clip.segment.TimelineOriginReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * OBS 수동 마킹(계약4 4B/2B)을 그 회원의 켜진 방송의 수동 카드로 만든다(POK-391).
 *
 * <p><b>순서가 계약이다.</b> 본문(400) → 헤더 대조(400) → 키 확인(400·401·503) → 파생 값 대조(401) → eventId 선조회(200·400)
 * → 방송 고르기(404) → 늦은 마크(400) → 기준점(503) → 시각 환산(400) → 저장(201·200). 앞 단계가 거절하면 뒤 단계는 안 돈다 :
 * 키를 확인하기 전에 방송·카드를 읽으면 키 없는 사람이 응답으로 남의 방송 상태를 알 수 있다.
 *
 * <p>🔴 <b>로그에 streamid·Authorization·파생 값·passphrase를 안 싣는다</b>(계약4 보안). 회원 번호·카드 번호·사유만.
 */
@Service
public class MarkService {

    private static final Logger log = LoggerFactory.getLogger(MarkService.class);

    private static final String BEARER = "Bearer ";

    /** 기준점이 없을 때 플러그인에게 주는 다시 보내기 간격(초). 첫 조각은 방송 시작 뒤 수 초 안에 생긴다. */
    static final int TIMELINE_RETRY_AFTER_SECONDS = 5;

    /**
     * 누른 시각이 방송 시작보다 이만큼 넘게 앞이면 「누른 그때 켜진 방송이 없다」(계획 검증 E6). 응답은 400이다(감사 2회차 B3) :
     * 누른 시각은 다시 보내도 안 바뀌므로 404(플러그인이 10분 동안 다시 보낸다)는 같은 거절을 스무 번 받게 할 뿐이다. 기준점 조회의 여유
     * ({@code TimelineOriginReader.BEFORE_START})와 같은 2분이다: 그 안이면 이 방송의 조각이 있을 수 있다.
     */
    static final Duration LATE_MARK_TOLERANCE = Duration.ofMinutes(2);

    /** 카드 ms 상한(감사 1회차 A2). 이보다 긴 방송은 없다고 본다: 넘으면 시각이 틀린 것이라 400. */
    static final long MAX_CARD_MS = Duration.ofHours(48).toMillis();

    private final StreamKeyResolveClient keys;
    private final BroadcastRepository broadcasts;
    private final TimelineOriginReader origins;
    private final JumpCardService cards;
    private final Clock clock;

    @Autowired
    MarkService(StreamKeyResolveClient keys, BroadcastRepository broadcasts,
                TimelineOriginReader origins, JumpCardService cards) {
        this(keys, broadcasts, origins, cards, Clock.systemUTC());
    }

    /** 시험이 받은 시각을 고정하는 자리(계획 검증 E9: {@code Clock} 빈이 없다. {@code ThumbnailSweeper}와 같은 모양). */
    MarkService(StreamKeyResolveClient keys, BroadcastRepository broadcasts,
                TimelineOriginReader origins, JumpCardService cards, Clock clock) {
        this.keys = keys;
        this.broadcasts = broadcasts;
        this.origins = origins;
        this.cards = cards;
        this.clock = clock;
    }

    /**
     * 받은 시각. 문이 요청에 들어오자마자 한 번 부른다(계획 검증 E5): auth 왕복 뒤에 읽으면 그 시간(최대 수 초)만큼
     * 누른 시각이 뒤로 밀린다.
     */
    public Instant now() {
        return clock.instant();
    }

    /**
     * @param authorization {@code Authorization} 헤더 원문(없으면 null)
     * @param idempotencyKey {@code Idempotency-Key} 헤더(없으면 null)
     * @param body 본문 원문
     * @param receivedAt {@link #now()}로 문 들어올 때 읽은 시각
     * @throws MarkErrors.MarkRejectedException 거절(상태·사유는 그 안에)
     * @throws com.pokeclip.clip.jumpcard.JumpCardErrors.HotkeyEventOwnedByOtherException eventId가 남의 카드(400)
     * @throws com.pokeclip.clip.jumpcard.JumpCardErrors.HotkeyBroadcastGoneException 고른 방송이 그 사이 사라졌다(404)
     */
    public RecordResult mark(String authorization, String idempotencyKey, String body, Instant receivedAt) {
        MarkRequest request = MarkRequest.parse(body);
        if (idempotencyKey == null || !idempotencyKey.equals(request.eventId())) {
            throw MarkErrors.invalidRequest();
        }
        if (authorization == null || !authorization.startsWith(BEARER)) {
            // 키 확인 전에 끊는다: auth를 부를 이유가 없다. 사유는 키가 틀린 것과 같다(무엇이 빠졌는지 안 알린다).
            throw MarkErrors.invalidStreamKey();
        }

        String userId = verifiedUser(request.streamid(), authorization.substring(BEARER.length()));

        Optional<RecordResult> existing = cards.existingHotkey(request.eventId(), userId);
        if (existing.isPresent()) {
            return existing.get();
        }

        Broadcast broadcast = pick(userId, request.streamKeyToken());
        Instant pressed = serverPressedAt(request, receivedAt);
        if (broadcast.getStartedAt() != null && pressed.isBefore(broadcast.getStartedAt().minus(LATE_MARK_TOLERANCE))) {
            // 누른 그때는 이 방송이 없었다. 큐에 오래 있던 앞 방송의 마킹이 새 방송에 붙지 않게 한다.
            // 400이다: 다시 보내도 결과가 같다(감사 2회차 B3).
            log.info("clip.mark.late by={}", userId);
            throw MarkErrors.invalidRequest();
        }
        Instant origin = origins.originsOf(List.of(broadcast)).get(broadcast.getStreamId());
        if (origin == null) {
            throw MarkErrors.timelineNotReady(TIMELINE_RETRY_AFTER_SECONDS);
        }
        long cardMs = cardMillis(pressed, origin);
        return cards.recordHotkey(broadcast.getStreamId(), userId, request.eventId(), cardMs);
    }

    /** 키를 auth에 묻고 파생 값을 맞댄다. 맞으면 회원 번호. */
    private String verifiedUser(String streamid, String bearer) {
        StreamKeyResolution resolution = keys.resolve(streamid);
        return switch (resolution.kind()) {
            case VALID -> {
                if (!MarkSignature.matches(resolution.passphrase(), bearer)) {
                    log.info("clip.mark.signature_mismatch by={}",
                            resolution.userId());
                    throw MarkErrors.invalidStreamKey();
                }
                yield resolution.userId();
            }
            case MALFORMED -> throw MarkErrors.invalidRequest();
            case REJECTED -> throw MarkErrors.invalidStreamKey();
            case UNAVAILABLE -> throw MarkErrors.keyCheckUnavailable();
        };
    }

    /**
     * 이 회원의 켜진 방송. 둘 이상이면 물리 키가 이 스트림키 토큰인 방송, 없으면 가장 늦게 시작한 방송(계약4 2B-2).
     *
     * <p>🔴 <b>물리 키가 스트림키 토큰과 같다는 전제다(U9 미결, 계획 검증 E3).</b> 오늘 media는 스트림키 토큰을 물리 키로 쓴다.
     * 바뀌면 이 맞대기가 늘 빗나가 「가장 늦게 시작한 방송」으로 떨어진다: 틀린 방송이 아니라 덜 정확한 고르기다.
     */
    private Broadcast pick(String userId, String token) {
        List<Broadcast> live = broadcasts.findLiveByStreamerId(userId);
        if (live.isEmpty()) {
            log.info("clip.mark.no_live_broadcast by={}", userId);
            throw MarkErrors.broadcastNotFound();
        }
        if (live.size() > 1 && token != null) {
            for (Broadcast b : live) {
                if (token.equals(b.ingestKey())) {
                    return b;
                }
            }
        }
        return live.get(0);
    }

    /**
     * 플러그인 시계의 누른 시각을 서버 시계로 옮긴다: {@code pressedAt + (받은 시각 − sentAt)}(계약4 2B-1).
     * 받은 시각보다 미래면 받은 시각이다(플러그인 시계가 앞서 있거나 sentAt이 틀렸다). 넘치면 400.
     */
    private static Instant serverPressedAt(MarkRequest request, Instant receivedAt) {
        try {
            long shift = Math.subtractExact(receivedAt.toEpochMilli(), request.sentAt());
            long pressed = Math.addExact(request.pressedAt(), shift);
            return Instant.ofEpochMilli(Math.min(pressed, receivedAt.toEpochMilli()));
        } catch (ArithmeticException e) {
            throw MarkErrors.invalidRequest();
        }
    }

    /**
     * 카드 ms = 누른 시각 − 기준점. 기준점보다 앞이면 0(감사 1회차 A2: {@code recordHotkey}에 음수가 가면 500이다).
     * 상한 밖이면 시각이 틀린 것이라 400.
     */
    private static long cardMillis(Instant pressed, Instant origin) {
        long ms;
        try {
            ms = Math.subtractExact(pressed.toEpochMilli(), origin.toEpochMilli());
        } catch (ArithmeticException e) {
            throw MarkErrors.invalidRequest();
        }
        if (ms > MAX_CARD_MS) {
            throw MarkErrors.invalidRequest();
        }
        return Math.max(0, ms);
    }
}
