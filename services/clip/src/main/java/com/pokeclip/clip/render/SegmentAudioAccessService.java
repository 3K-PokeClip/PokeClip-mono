package com.pokeclip.clip.render;

import com.pokeclip.clip.broadcast.Broadcast;
import com.pokeclip.clip.broadcast.BroadcastRepository;
import com.pokeclip.clip.delegation.AccessErrors;
import com.pokeclip.clip.delegation.BroadcastAccessGuard;
import com.pokeclip.clip.recipe.RecipeValidator;
import com.pokeclip.clip.render.RenderErrors.InvalidSegmentRangeException;
import com.pokeclip.clip.render.RenderErrors.RenderUnavailableException;
import com.pokeclip.clip.render.RenderErrors.SegmentsExpiredException;
import com.pokeclip.clip.segment.CutPlanner;
import com.pokeclip.clip.segment.LedgerCutPlanner;
import com.pokeclip.clip.segment.RoundRow;
import com.pokeclip.clip.segment.SegmentSource;
import com.pokeclip.clip.segment.StreamSegmentReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 원본 조각 주소(POK-389). 편집기가 구간의 조각을 받아 소리 트랙별로 미리 듣는다(저장·렌더와 같은 트랙 번호).
 *
 * <p><b>순서가 계약이다: 구간 → 자격 → 만료 → 켜짐 → 조회.</b> 조각 조회 문({@code SegmentQueryService})과 같다:
 * 만료(410)가 자격 앞이면 남남에게 방송의 실재가 샌다. 표를 안 바꾸므로 트랜잭션이 없다.
 *
 * <p><b>조각 목록과 확보 판정은 따로 만든다(POK-196 · 설계 r7 9.6).</b> 목록은 develop 뜻 그대로 물리 키 조각 가운데 창과 목록 PDT
 * 축({@code COALESCE(playback_pdt, start_wall_utc)})으로 겹치는 올라간 조각이다. 끝 소리 확보는 같은 창을 렌더 주문 컷으로 받았을
 * 때와 같은 판정이다({@link LedgerCutPlanner#audioCapture}) — k 를 목록 마지막에서 고르면 비귀속 · 아직 안 올라간 행이 k 가 되어
 * 렌더와 갈린다.
 */
@Service
public class SegmentAudioAccessService {

    /** 한 번에 줄 구간의 상한. 쇼츠 최대 3분의 두 배: 화면이 받아 푸는 양(조각 약 90개)의 한도다. */
    static final long MAX_RANGE_MS = 360_000L;

    private static final Logger log = LoggerFactory.getLogger(SegmentAudioAccessService.class);

    private final BroadcastAccessGuard guard;
    private final BroadcastRepository broadcasts;
    private final StreamSegmentReader segments;
    private final LedgerCutPlanner cuts;
    private final ObjectProvider<SegmentAudioSigner> signer;
    private final Clock clock;

    @Autowired
    SegmentAudioAccessService(BroadcastAccessGuard guard, BroadcastRepository broadcasts, StreamSegmentReader segments,
                              LedgerCutPlanner cuts, ObjectProvider<SegmentAudioSigner> signer) {
        this(guard, broadcasts, segments, cuts, signer, Clock.systemUTC());
    }

    /** 시험이 「지금」 을 고정하는 자리(확보 후보 {@code failed} 문턱). */
    SegmentAudioAccessService(BroadcastAccessGuard guard, BroadcastRepository broadcasts, StreamSegmentReader segments,
                              LedgerCutPlanner cuts, ObjectProvider<SegmentAudioSigner> signer, Clock clock) {
        this.guard = guard;
        this.broadcasts = broadcasts;
        this.segments = segments;
        this.cuts = cuts;
        this.signer = signer;
        this.clock = clock;
    }

    /**
     * @param fromMs 재생 축 시작(UTC epoch ms, 포함)
     * @param toMs   재생 축 끝(제외) — 끝 소리 확보는 이것을 컷 끝으로 본다
     * @throws InvalidSegmentRangeException 구간이 잘못됐다 (400)
     * @throws com.pokeclip.clip.delegation.AccessErrors.NotViewableException 방송이 없거나 볼 자격이 없다 (404)
     * @throws SegmentsExpiredException 보관 기한이 지났다 (410)
     * @throws RenderUnavailableException 렌더 설정이 꺼져 조각 창고 좌표가 없다 (503)
     */
    public SegmentAudioAccess issue(String requesterSubject, String streamId, long fromMs, long toMs) {
        if (fromMs < 0) {
            throw new InvalidSegmentRangeException("fromMs");
        }
        // 뺄셈이 안 넘친다: fromMs >= 0 이고 toMs > fromMs 이면 toMs - fromMs <= toMs다.
        // 먼 미래는 DB 시각으로 못 바꿔 조회가 500이다: 편집본 검사와 같은 상한에서 거절한다
        if (toMs <= fromMs || toMs - fromMs > MAX_RANGE_MS || toMs > RecipeValidator.MAX_EPOCH_MS) {
            throw new InvalidSegmentRangeException("toMs");
        }
        guard.requireViewable(requesterSubject, streamId);
        if (Boolean.TRUE.equals(broadcasts.isVodExpired(streamId))) {
            throw new SegmentsExpiredException();
        }
        SegmentAudioSigner available = signer.getIfAvailable();
        if (available == null) {
            throw new RenderUnavailableException();
        }
        // 자격 검사 뒤 방송 줄이 지워지는 경합도 자격 검사와 같은 404 다(500 으로 새지 않게).
        Broadcast broadcast = broadcasts.findByStreamId(streamId)
                .orElseThrow(() -> new AccessErrors.NotViewableException("broadcast_not_found"));
        Instant now = clock.instant();
        // 조각 장부는 물리 키로 찾는다(POK-233). 아직 안 올라간 조각은 주소를 줘도 창고에 없다.
        List<SegmentAudioSigner.Unsigned> uploaded = segments
                .findOverlappingByPlaybackTime(broadcast.ingestKey(), fromMs, toMs).stream()
                .filter(segment -> "uploaded".equals(segment.uploadState()))
                .map(SegmentAudioAccessService::unsigned)
                .toList();
        Optional<CutPlanner.AudioCapture> capture = captureOrEmpty(broadcast, fromMs, toMs, now);
        SegmentAudioAccess.Segment captureSegment = capture.flatMap(CutPlanner.AudioCapture::row)
                .map(row -> available.sign(List.of(unsigned(row))).get(0))
                .orElse(null);
        SegmentAudioAccess access = new SegmentAudioAccess(available.expiresAt(now), available.sign(uploaded),
                capture.map(c -> wire(c.state())).orElse(null), captureSegment);
        // 주소·창고 좌표는 안 찍는다: 주소가 곧 출입증이다.
        log.info("clip.segment_audio_access.issued streamId={} userId={} segments={} expiresAt={} audioCapture={}",
                streamId, requesterSubject, access.segments().size(), access.expiresAt(), access.audioCapture());
        return access;
    }

    /**
     * 끝 소리 확보는 부가 칸이다(kty 2026-11-12). 판정이 실패하면 — 장부를 못 읽거나(생산자 c1 DDL 이 아직 없는 DB) 장부 값이 판정
     * 전제를 깨면(회차 안 판 섞임 등) — 칸만 비우고 조각 목록은 낸다. 비면 웹은 확보 없이 목록으로 미리 듣는다. 카드 장면 실패
     * ({@code jumpcard.scene_unavailable})와 같은 처분이다. 입력 값 · 메시지는 싣지 않는다.
     */
    private Optional<CutPlanner.AudioCapture> captureOrEmpty(Broadcast broadcast, long fromMs, long toMs, Instant now) {
        try {
            return Optional.of(cuts.audioCapture(broadcast, fromMs, toMs, now));
        } catch (RuntimeException e) {
            log.warn("clip.segment_audio_access.capture_unavailable streamId={} reason={}", broadcast.getStreamId(),
                    e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private static SegmentAudioSigner.Unsigned unsigned(SegmentSource s) {
        return new SegmentAudioSigner.Unsigned(s.s3Key(),
                new SegmentAudioAccess.Segment(s.seq(), s.startAtMs(), s.durationMs(), null));
    }

    /** 확보 조각 — 시작은 목록 PDT 축(판정 축과 같은 ms 내림 값)이다. */
    private static SegmentAudioSigner.Unsigned unsigned(RoundRow row) {
        long startAtMs = row.pdtMs() != null ? row.pdtMs() : row.wallMs();
        return new SegmentAudioSigner.Unsigned(row.s3Key(),
                new SegmentAudioAccess.Segment(row.seq(), startAtMs, row.durationMs(), null));
    }

    /** 응답 칸 값 — 웹과의 계약이라 바꾸지 않는다. */
    private static String wire(CutPlanner.AudioCapture.State state) {
        return switch (state) {
            case ATTACHED -> "attached";
            case NOT_READY -> "not_ready";
            case SKIPPED -> "skipped";
        };
    }
}
