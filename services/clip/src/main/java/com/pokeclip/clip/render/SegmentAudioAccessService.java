package com.pokeclip.clip.render;

import com.pokeclip.clip.broadcast.BroadcastRepository;
import com.pokeclip.clip.delegation.BroadcastAccessGuard;
import com.pokeclip.clip.render.RenderErrors.InvalidSegmentRangeException;
import com.pokeclip.clip.render.RenderErrors.RenderUnavailableException;
import com.pokeclip.clip.render.RenderErrors.SegmentsExpiredException;
import com.pokeclip.clip.segment.SegmentSource;
import com.pokeclip.clip.segment.StreamSegmentReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * 원본 조각 주소(POK-389). 편집기가 구간의 조각을 받아 소리 트랙별로 미리 듣는다(저장·렌더와 같은 트랙 번호).
 *
 * <p><b>순서가 계약이다: 구간 → 자격 → 만료 → 켜짐 → 조회.</b> 조각 조회 문({@code SegmentQueryService})과 같다:
 * 만료(410)가 자격 앞이면 남남에게 방송의 실재가 샌다. 표를 안 바꾸므로 트랜잭션이 없다.
 */
@Service
public class SegmentAudioAccessService {

    /** 한 번에 줄 구간의 상한. 쇼츠 최대 3분의 두 배: 화면이 받아 푸는 양(조각 약 90개)의 한도다. */
    static final long MAX_RANGE_MS = 360_000L;

    private static final Logger log = LoggerFactory.getLogger(SegmentAudioAccessService.class);

    private final BroadcastAccessGuard guard;
    private final BroadcastRepository broadcasts;
    private final StreamSegmentReader segments;
    private final ObjectProvider<SegmentAudioSigner> signer;

    SegmentAudioAccessService(BroadcastAccessGuard guard, BroadcastRepository broadcasts, StreamSegmentReader segments,
                              ObjectProvider<SegmentAudioSigner> signer) {
        this.guard = guard;
        this.broadcasts = broadcasts;
        this.segments = segments;
        this.signer = signer;
    }

    /**
     * @param fromMs 재생 축 시작(UTC epoch ms, 포함)
     * @param toMs   재생 축 끝(제외)
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
        if (toMs <= fromMs || toMs - fromMs > MAX_RANGE_MS) {
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
        // 조각 장부는 물리 키로 찾는다(POK-233). 아직 안 올라간 조각은 주소를 줘도 창고에 없다.
        String ingestKey = broadcasts.findIngestKeyByStreamId(streamId).orElse(streamId);
        List<SegmentSource> uploaded = segments.findOverlappingByPlaybackTime(ingestKey, fromMs, toMs).stream()
                .filter(segment -> "uploaded".equals(segment.uploadState()))
                .toList();
        SegmentAudioAccess access = available.sign(uploaded, Instant.now());
        // 주소·창고 좌표는 안 찍는다: 주소가 곧 출입증이다.
        log.info("clip.segment_audio_access.issued streamId={} userId={} segments={} expiresAt={}",
                streamId, requesterSubject, access.segments().size(), access.expiresAt());
        return access;
    }
}
