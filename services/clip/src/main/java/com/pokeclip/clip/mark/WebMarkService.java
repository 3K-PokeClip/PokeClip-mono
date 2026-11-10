package com.pokeclip.clip.mark;

import com.pokeclip.clip.broadcast.Broadcast;
import com.pokeclip.clip.broadcast.BroadcastRepository;
import com.pokeclip.clip.broadcast.BroadcastStatus;
import com.pokeclip.clip.delegation.BroadcastAccessGuard;
import com.pokeclip.clip.jumpcard.JumpCardErrors.BroadcastNotFoundException;
import com.pokeclip.clip.jumpcard.JumpCardErrors.HotkeyBroadcastGoneException;
import com.pokeclip.clip.jumpcard.JumpCardErrors.HotkeyEventOwnedByOtherException;
import com.pokeclip.clip.jumpcard.JumpCardErrors.InvalidHighlightException;
import com.pokeclip.clip.jumpcard.JumpCardErrors.TimelineNotReadyException;
import com.pokeclip.clip.jumpcard.JumpCardService;
import com.pokeclip.clip.jumpcard.JumpCardService.RecordResult;
import com.pokeclip.clip.segment.ServerClockPosition;
import com.pokeclip.clip.segment.ServerClockPosition.Position;
import com.pokeclip.clip.segment.TimelineOriginReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 웹 방송 화면의 수동 마킹(POK-392)을 그 방송의 수동 카드로 만든다. 저장·20초 판정은 OBS 문과 같은
 * {@link JumpCardService#recordHotkey}라 두 문의 카드가 한 판정을 같이 쓴다.
 *
 * <p><b>순서가 계약이다.</b> 본문(400) → 자격(404·503, 트랜잭션 밖) → 방송(404) → 갈래와 방송 상태(400) → 라이브는 시각(400) →
 * 저장 위치(503 회차 귀속 행 없음) → 기준점(503) → 상한(400), 지난 방송은 기준점(503) → 위치 범위(400) → 저장 위치(503) →
 * 상한(400) → 저장(201·200). 상한은 두 갈래 모두 방송 시작 뒤 48시간이고 저장 위치에 건다(POK-562).
 * 본문이 맨 앞인 것은 형식 오류가 auth 왕복을 태우지 않게, 자격이 방송 읽기보다 앞인 것은
 * 남의 방송이 메모리에 안 올라오게 하려는 것이다({@code BroadcastListService}와 같은 순서).
 *
 * <p>🔴 <b>저장에는 요청자가 아니라 방송 주인 번호를 넘긴다.</b> {@code recordHotkey}가 「넘겨받은 방송의 주인이 이 회원인가」를
 * 보므로, 편집자 번호를 넘기면 편집자의 마킹이 전부 404가 된다(편집자가 매일 쓰는 쪽이다). 로그에는 둘 다 남긴다.
 *
 * <p>🔴 <b>OBS 거절 타입({@code MarkErrors.MarkRejectedException})을 안 던진다</b>(계획 검증 C1). 그 타입의 처리기는 OBS 문에만
 * 걸려 있어 여기서 나가면 500이다. 400·404는 전역 조언의 {@code {"error":…}} 봉투, 503 기준점은 이 문 전용 타입이다.
 */
@Service
public class WebMarkService {

    private static final Logger log = LoggerFactory.getLogger(WebMarkService.class);

    /**
     * 지난 방송 위치의 아래 여유. 녹화가 기준점보다 앞에서 시작하면 위치가 음수다: 그 안이면 0으로 접는다.
     * 기준점 조회가 시작 편지 앞 2분까지 조각을 보고, 녹화 첫 조각과 기준점 조각은 그보다 멀지 않다. 넉넉히 10분이고 그 아래는 시각이 틀린 것이다.
     */
    static final long POSITION_BELOW_ORIGIN_MS = Duration.ofMinutes(10).toMillis();

    /** 지난 방송 위치의 위 여유. 녹화는 종료 편지 뒤까지 이어질 수 있다(기준점 조회의 종료 뒤 여유와 같은 10분). */
    static final long POSITION_AFTER_END_MS = Duration.ofMinutes(10).toMillis();

    private final BroadcastAccessGuard guard;
    private final BroadcastRepository broadcasts;
    private final TimelineOriginReader origins;
    private final ServerClockPosition positions;
    private final JumpCardService cards;
    private final Clock clock;

    @Autowired
    WebMarkService(BroadcastAccessGuard guard, BroadcastRepository broadcasts,
                   TimelineOriginReader origins, ServerClockPosition positions, JumpCardService cards) {
        this(guard, broadcasts, origins, positions, cards, Clock.systemUTC());
    }

    WebMarkService(BroadcastAccessGuard guard, BroadcastRepository broadcasts,
                   TimelineOriginReader origins, ServerClockPosition positions, JumpCardService cards, Clock clock) {
        this.guard = guard;
        this.broadcasts = broadcasts;
        this.origins = origins;
        this.positions = positions;
        this.cards = cards;
        this.clock = clock;
    }

    /**
     * 받은 시각. 문이 요청에 들어오자마자 한 번 부른다(계획 검증 m1, OBS 문 E5와 같다): auth 왕복 뒤에 읽으면
     * 그 시간(최대 7초)만큼 누른 시각이 뒤로 밀린다.
     */
    public Instant now() {
        return clock.instant();
    }

    /**
     * @param requesterSubject JWT {@code sub}
     * @param body 본문 원문(상한까지 읽은 것)
     * @param receivedAt {@link #now()}로 문 들어올 때 읽은 시각
     * @throws InvalidHighlightException 400 {@code field}
     * @throws BroadcastNotFoundException 404(방송이 없거나 그 사이 사라졌다)
     * @throws com.pokeclip.clip.delegation.AccessErrors.NotViewableException 404(자격 없음, 같은 본문)
     * @throws com.pokeclip.clip.delegation.AccessErrors.AuthUnavailableException 503
     * @throws TimelineNotReadyException 503 기준점 · 회차 귀속 행 없음
     */
    public RecordResult mark(String requesterSubject, String streamId, String body, Instant receivedAt) {
        WebMarkRequest request = WebMarkRequest.parse(body);

        guard.requireViewable(requesterSubject, streamId);

        // 판정 뒤에 엔티티를 한 번(m6). 판정은 스칼라로 읽어 1차 캐시가 비어 있다.
        Broadcast broadcast = broadcasts.findByStreamId(streamId)
                .orElseThrow(() -> new BroadcastNotFoundException(streamId));
        boolean live = broadcast.getStatus() == BroadcastStatus.LIVE;
        if (request.live() != live) {
            // 라이브는 서버 시각으로 정하고 지난 방송은 재생 위치로 정한다. 섞이면 카드가 엉뚱한 자리에 선다.
            throw new InvalidHighlightException(request.live() ? "pressedAt" : "positionMs");
        }

        long cardMs = live ? liveMillis(request, receivedAt, broadcast) : positionMillis(request, broadcast);

        String owner = broadcast.getStreamerId();
        RecordResult result;
        try {
            result = cards.recordHotkey(streamId, owner, request.eventId(), cardMs);
        } catch (HotkeyEventOwnedByOtherException e) {
            // 그 eventId는 다른 회원 방송의 카드다(전역 유일). 그 카드를 안 싣는다.
            throw new InvalidHighlightException("eventId");
        } catch (HotkeyBroadcastGoneException e) {
            // 판정 뒤 저장 전에 방송이 사라졌다. 없는 방송과 같은 404(바닥도 같다).
            throw new BroadcastNotFoundException(streamId);
        }
        if (result.created()) {
            log.info("clip.webmark.created cardId={} by={} owner={}", result.card().id(), requesterSubject, owner);
        } else {
            log.info("clip.webmark.suppressed cardId={} by={} owner={}", result.card().id(), requesterSubject, owner);
        }
        return result;
    }

    /**
     * 라이브: 서버 시각은 OBS 문과 같은 식(pressedAt + (받은 − sentAt), 미래면 받은 시각)이고, 카드 ms 는 회차 귀속 행에서
     * 행별로 고른다({@link ServerClockPosition} — OBS 문과 같은 함수). 상한은 방송 시작 뒤 48시간
     * ({@link MarkService#cardUpperMillis}, POK-562)이고 저장 위치에 건다.
     */
    private long liveMillis(WebMarkRequest request, Instant receivedAt, Broadcast broadcast) {
        Instant pressed;
        try {
            pressed = MarkService.serverPressedAt(request.pressedAt(), request.sentAt(), receivedAt);
        } catch (ArithmeticException e) {
            throw new InvalidHighlightException("pressedAt");
        }
        Position position = positions.ofServerClock(broadcast, pressed, ServerClockPosition.Source.WEB_LIVE)
                .orElseThrow(() -> new TimelineNotReadyException(MarkService.TIMELINE_RETRY_AFTER_SECONDS));
        long upperMs = upperMillis(broadcast, originOf(broadcast), "pressedAt");
        if (position.cardMs() > upperMs) {
            throw new InvalidHighlightException("pressedAt");
        }
        return position.cardMs();
    }

    /**
     * 지난 방송: 웹은 「목록 PDT − 기준점」 을 보낸다(계획 검증 m3 의 위치 그대로). 범위는 지금 그대로다 — 아래는 기준점 앞
     * 10분, 위는 (종료 − 기준점) + 10분, 종료를 모르면 방송 시작 뒤 48시간 = (시작 − 기준점) + 48시간
     * ({@link MarkService#cardUpperMillis}, POK-562). 저장 값은 그 위치가 아니라 {@code P = 기준점 + positionMs}(같은 기준점이라
     * 목록 PDT 를 정확히 되살린다)를 회차 귀속 행에서 행별로 옮긴 위치다(POK-196 · 설계 r7 8.2) — 출시 이미지 판 NULL
     * 행에서는 「목록 PDT − 기준점」 이 재생 위치 축 값이 아니다.
     */
    private long positionMillis(WebMarkRequest request, Broadcast broadcast) {
        // 기준점과 저장 위치는 다른 왕복이라 같은 시점 읽기가 아니다. 지난 방송은 장부 행이 더 붙지 않아 두 읽기가 같은 행을 본다.
        Instant origin = originOf(broadcast);
        long position = request.positionMs();
        if (position < -POSITION_BELOW_ORIGIN_MS) {
            throw new InvalidHighlightException("positionMs");
        }
        long upper = broadcast.getEndedAt() == null
                ? upperMillis(broadcast, origin, "positionMs")
                : broadcast.getEndedAt().toEpochMilli() - origin.toEpochMilli() + POSITION_AFTER_END_MS;
        if (position > upper) {
            throw new InvalidHighlightException("positionMs");
        }
        Instant listed = origin.plusMillis(position);
        Position stored = positions.ofPlayback(broadcast, listed)
                .orElseThrow(() -> new TimelineNotReadyException(MarkService.TIMELINE_RETRY_AFTER_SECONDS));
        // 상한은 라이브 · OBS 와 같이 저장 위치에 건다 — 위 범위는 입력 위치의 것이다.
        if (stored.cardMs() > upperMillis(broadcast, origin, "positionMs")) {
            throw new InvalidHighlightException("positionMs");
        }
        return stored.cardMs();
    }

    /** 기준점. 없으면 503(첫 조각 · 회차 귀속 행이 아직 없다). */
    private Instant originOf(Broadcast broadcast) {
        Instant origin = origins.originsOf(List.of(broadcast)).get(broadcast.getStreamId());
        if (origin == null) {
            throw new TimelineNotReadyException(MarkService.TIMELINE_RETRY_AFTER_SECONDS);
        }
        return origin;
    }

    /** 이 방송의 카드 ms 상한(방송 시작 뒤 48시간 · {@link MarkService#cardUpperMillis}). 넘치면 그 칸의 400. */
    private static long upperMillis(Broadcast broadcast, Instant origin, String field) {
        return MarkService.cardUpperMillis(broadcast.getStartedAt(), origin)
                .orElseThrow(() -> new InvalidHighlightException(field));
    }
}
