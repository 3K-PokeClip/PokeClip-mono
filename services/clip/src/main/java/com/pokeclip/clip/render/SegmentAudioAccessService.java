package com.pokeclip.clip.render;

import com.pokeclip.clip.broadcast.Broadcast;
import com.pokeclip.clip.broadcast.BroadcastRepository;
import com.pokeclip.clip.broadcast.BroadcastStatus;
import com.pokeclip.clip.delegation.BroadcastAccessGuard;
import com.pokeclip.clip.recipe.RecipeValidator;
import com.pokeclip.clip.render.RenderErrors.InvalidSegmentRangeException;
import com.pokeclip.clip.render.RenderErrors.RenderUnavailableException;
import com.pokeclip.clip.render.RenderErrors.SegmentsExpiredException;
import com.pokeclip.clip.segment.AudioCaptureRule;
import com.pokeclip.clip.segment.CutDecision.End;
import com.pokeclip.clip.segment.RoundRow;
import com.pokeclip.clip.segment.RoundRows;
import com.pokeclip.clip.segment.SegmentSource;
import com.pokeclip.clip.segment.StreamSegmentReader;
import com.pokeclip.clip.segment.StreamSegmentReader.WallAxisRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 원본 조각 주소(POK-389). 편집기가 구간의 조각을 받아 소리 트랙별로 미리 듣는다(저장·렌더와 같은 트랙 번호).
 *
 * <p><b>순서가 계약이다: 구간 → 자격 → 만료 → 켜짐 → 조회.</b> 조각 조회 문({@code SegmentQueryService})과 같다:
 * 만료(410)가 자격 앞이면 남남에게 방송의 실재가 샌다. 표를 안 바꾸므로 트랜잭션이 없다.
 *
 * <p><b>두 갈래(POK-196 · 설계 r7 9.6).</b> 요청 표지 {@code axis=fileHead} 가 있으면 두 판 모두 파일명 시각
 * ({@code start_wall_utc}) 축으로 회차 행을 고르고 · 범위 끝을 재고 · 조각 시작을 내고 · 확정 공백 쌍을 내며, 그 축으로 처리했을 때만
 * 뿌리에 메아리를 싣는다. 표지가 없으면 develop 그대로다 — 재생 축({@code COALESCE(playback_pdt, start_wall_utc)})으로 고르고 메아리
 * · 새 갈래 칸이 없다(조각마다 {@code startWallUtcMs} 만 더한다). 표지와 무관하게 축을 바꾸면 열린 옛 편집기 탭이 새 축 행을 옛 축에
 * 놓아 어긋나므로 그런 판은 내지 않는다.
 */
@Service
public class SegmentAudioAccessService {

    /** 한 번에 줄 구간의 상한. 쇼츠 최대 3분의 두 배: 화면이 받아 푸는 양(조각 약 90개)의 한도다. */
    static final long MAX_RANGE_MS = 360_000L;

    /** 요청 표지 · 응답 메아리의 값. 웹과의 계약이라 바꾸지 않는다. */
    static final String FILE_HEAD = "fileHead";

    private static final Logger log = LoggerFactory.getLogger(SegmentAudioAccessService.class);

    private final BroadcastAccessGuard guard;
    private final BroadcastRepository broadcasts;
    private final StreamSegmentReader segments;
    private final RoundRows rounds;
    private final ObjectProvider<SegmentAudioSigner> signer;
    private final Clock clock;

    @Autowired
    SegmentAudioAccessService(BroadcastAccessGuard guard, BroadcastRepository broadcasts, StreamSegmentReader segments,
                              RoundRows rounds, ObjectProvider<SegmentAudioSigner> signer) {
        this(guard, broadcasts, segments, rounds, signer, Clock.systemUTC());
    }

    /** 시험이 「지금」 을 고정하는 자리(확보 후보 {@code failed} 문턱). */
    SegmentAudioAccessService(BroadcastAccessGuard guard, BroadcastRepository broadcasts, StreamSegmentReader segments,
                              RoundRows rounds, ObjectProvider<SegmentAudioSigner> signer, Clock clock) {
        this.guard = guard;
        this.broadcasts = broadcasts;
        this.segments = segments;
        this.rounds = rounds;
        this.signer = signer;
        this.clock = clock;
    }

    /**
     * @param fromMs 재생 축 시작(UTC epoch ms, 포함). 표지가 있으면 파일명 시각 축
     * @param toMs   끝(제외)
     * @param axis   표지 — null(옛 갈래) 또는 {@code fileHead}
     * @throws InvalidSegmentRangeException 구간이나 표지가 잘못됐다 (400)
     * @throws com.pokeclip.clip.delegation.AccessErrors.NotViewableException 방송이 없거나 볼 자격이 없다 (404)
     * @throws SegmentsExpiredException 보관 기한이 지났다 (410)
     * @throws RenderUnavailableException 렌더 설정이 꺼져 조각 창고 좌표가 없다 (503)
     */
    public SegmentAudioAccess issue(String requesterSubject, String streamId, long fromMs, long toMs, String axis) {
        if (fromMs < 0) {
            throw new InvalidSegmentRangeException("fromMs");
        }
        // 뺄셈이 안 넘친다: fromMs >= 0 이고 toMs > fromMs 이면 toMs - fromMs <= toMs다.
        // 먼 미래는 DB 시각으로 못 바꿔 조회가 500이다: 편집본 검사와 같은 상한에서 거절한다
        if (toMs <= fromMs || toMs - fromMs > MAX_RANGE_MS || toMs > RecipeValidator.MAX_EPOCH_MS) {
            throw new InvalidSegmentRangeException("toMs");
        }
        if (axis != null && !FILE_HEAD.equals(axis)) {
            throw new InvalidSegmentRangeException("axis");
        }
        guard.requireViewable(requesterSubject, streamId);
        if (Boolean.TRUE.equals(broadcasts.isVodExpired(streamId))) {
            throw new SegmentsExpiredException();
        }
        SegmentAudioSigner available = signer.getIfAvailable();
        if (available == null) {
            throw new RenderUnavailableException();
        }
        Optional<SegmentAudioAccess> fileHead = axis == null ? Optional.empty()
                : broadcasts.findByStreamId(streamId).flatMap(b -> byFileHead(b, fromMs, toMs, available));
        SegmentAudioAccess access = fileHead.orElseGet(() -> byPlayback(streamId, fromMs, toMs, available));
        // 주소·창고 좌표는 안 찍는다: 주소가 곧 출입증이다.
        // axis 는 실제로 처리한 축, requested 는 요청 표지다 — 표지를 보냈는데 옛 갈래로 간 것(회차를 못 고름)을 가른다.
        log.info("clip.segment_audio_access.issued streamId={} userId={} segments={} expiresAt={} axis={} requested={}",
                streamId, requesterSubject, access.segments().size(), access.expiresAt(),
                access.axis() == null ? "legacy" : access.axis(), axis == null ? "none" : axis);
        return access;
    }

    /** 옛 갈래 — develop 그대로(재생 축 겹침 · 올라간 조각만). */
    private SegmentAudioAccess byPlayback(String streamId, long fromMs, long toMs, SegmentAudioSigner available) {
        // 조각 장부는 물리 키로 찾는다(POK-233). 아직 안 올라간 조각은 주소를 줘도 창고에 없다.
        String ingestKey = broadcasts.findIngestKeyByStreamId(streamId).orElse(streamId);
        List<SegmentAudioSigner.Unsigned> uploaded = segments.findOverlappingByPlaybackTime(ingestKey, fromMs, toMs)
                .stream()
                .filter(segment -> "uploaded".equals(segment.uploadState()))
                .map(SegmentAudioAccessService::legacy)
                .toList();
        return new SegmentAudioAccess(available.expiresAt(clock.instant()), available.sign(uploaded));
    }

    private static SegmentAudioSigner.Unsigned legacy(SegmentSource s) {
        return new SegmentAudioSigner.Unsigned(s.s3Key(), new SegmentAudioAccess.Segment(s.seq(), s.startAtMs(),
                s.durationMs(), null, s.startWallUtcMs(), null, null, null));
    }

    /**
     * 표지 갈래 — 파일명 시각 축 창의 회차 귀속 행(옛 방송은 시간창 행)에서 올라간 행만 싣고, 끝 소리 확보는 렌더 주문과 같은
     * {@link AudioCaptureRule} 하나로 정한다(컷 끝 = {@code toMs} · 계약 6-6-8 8항). 회차를 못 고르면(머리 세션 행도 시작 시각도
     * 없음) 그 축으로 처리하지 못했으니 비어 있다 — 부르는 쪽이 옛 갈래로 간다(메아리 없음).
     */
    private Optional<SegmentAudioAccess> byFileHead(Broadcast broadcast, long fromMs, long toMs,
                                                    SegmentAudioSigner available) {
        Optional<RoundRows.Round> round = rounds.roundOf(broadcast);
        if (round.isEmpty()) {
            return Optional.empty();
        }
        List<WallAxisRow> rows = segments.findRoundRowsByWallTime(round.get(), fromMs, toMs);
        // 창 안 회차 행이 없으면 「영상 범위의 마지막 조각」 이 없어 확보할 것도 없다.
        Capture capture = rows.isEmpty() ? new Capture("skipped", Optional.empty())
                : captureOf(broadcast, round.get(), rows.get(rows.size() - 1).row(), toMs);
        List<SegmentAudioSigner.Unsigned> unsigned = new ArrayList<>(rows.size() + 1);
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).row().uploaded()) {
                unsigned.add(unsigned(rows.get(i), "segment", followsInResponse(rows, i)));
            }
        }
        // 확보 조각은 마지막 회차 행의 장부 바로 다음 행이라, 그 회차 행이 응답에 실렸을 때만 이웃이다.
        capture.row().ifPresent(r -> unsigned.add(unsigned(r, "audio_capture",
                followsInResponse(rows, rows.size()))));
        return Optional.of(new SegmentAudioAccess(available.expiresAt(clock.instant()), available.sign(unsigned),
                FILE_HEAD, capture.state(), gapsOf(rows)));
    }

    /** 끝 소리 확보 판정 결과 — 응답 칸 값과 실을 확보 조각. */
    private record Capture(String state, Optional<WallAxisRow> row) {
    }

    private Capture captureOf(Broadcast broadcast, RoundRows.Round round, RoundRow last, long toMs) {
        End out = new End(last.seq(), Math.multiplyExact(toMs - last.wallMs(), 1000L));
        AtomicReference<Optional<WallAxisRow>> next = new AtomicReference<>(Optional.empty());
        AudioCaptureRule.Outcome outcome = AudioCaptureRule.decide(last, out, () -> {
            next.set(segments.findRowAfter(round.ingestKey(), last.seq()));
            return next.get().map(WallAxisRow::row);
        }, () -> broadcast.getStatus() != BroadcastStatus.LIVE
                || round instanceof RoundRows.Members && rounds.roundComplete(broadcast.getStreamId()),
                clock.instant());
        return switch (outcome) {
            case AudioCaptureRule.Outcome.Attach a -> new Capture("attached", next.get());
            case AudioCaptureRule.Outcome.Wait w -> new Capture("not_ready", Optional.empty());
            case AudioCaptureRule.Outcome.Proceed p -> new Capture("skipped", Optional.empty());
            case AudioCaptureRule.Outcome.FailedSkip f -> new Capture("skipped", Optional.empty());
        };
    }

    /**
     * 회차 목록 {@code i} 번째 자리(목록 끝 = 확보 조각)의 이웃 표시. <b>응답 목록 기준</b>이다 — 회차 목록의 바로 앞 행이 응답에
     * 실렸고(올라감) 그 행이 장부 바로 앞 행일 때만 참이다. 아직 안 올라간 행이 빠진 자리는 거짓이라 웹이 「업로드 대기」 로 읽는다.
     * 확보 조각은 장부 바로 다음 행이라 장부 이웃 칸을 따로 보지 않는다.
     */
    private static boolean followsInResponse(List<WallAxisRow> rows, int i) {
        if (i == 0 || !rows.get(i - 1).row().uploaded()) {
            return false;
        }
        return i == rows.size() || rows.get(i).row().followsLedgerPrevious();
    }

    private static SegmentAudioSigner.Unsigned unsigned(WallAxisRow r, String role, boolean follows) {
        RoundRow row = r.row();
        return new SegmentAudioSigner.Unsigned(row.s3Key(), new SegmentAudioAccess.Segment(row.seq(),
                r.playbackStartAtMs(), row.durationMs(), null, row.wallMs(), role, row.isDiscontinuity(), follows));
    }

    /** 창 안 회차 행 이웃 가운데 같은 녹화로 이어지지 않은 쌍(J 거짓 — 렌더 계획 검사 · 컷 판정과 같은 식). */
    private static List<SegmentAudioAccess.GapPair> gapsOf(List<WallAxisRow> rows) {
        List<SegmentAudioAccess.GapPair> gaps = new ArrayList<>();
        for (int i = 1; i < rows.size(); i++) {
            if (!rows.get(i).row().continuesPrevious()) {
                gaps.add(new SegmentAudioAccess.GapPair(rows.get(i - 1).row().seq(), rows.get(i).row().seq()));
            }
        }
        return gaps;
    }
}
