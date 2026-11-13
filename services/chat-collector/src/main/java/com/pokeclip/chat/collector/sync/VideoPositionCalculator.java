package com.pokeclip.chat.collector.sync;

import com.pokeclip.chat.collector.broadcast.IngestKeyStore;
import com.pokeclip.ledger.timeline.LedgerRow;
import com.pokeclip.ledger.timeline.LedgerTimeline;
import com.pokeclip.ledger.timeline.Place;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 채팅 시각({@code messageTime})을 그 방송 영상 안의 위치(ms)로 바꾼다.
 *
 * <h2>판정 셋의 뜻</h2>
 * {@link VideoPosition.State#CONVERTED}만 위치가 있다. {@code NOT_YET_INDEXED}는
 * <b>다시 물으면 답이 바뀔 수 있다</b>(조각이 아직 장부에 안 들어왔다). {@code NO_FOOTAGE}는
 * <b>영영 없다</b>. 부르는 쪽이 재시도 루프를 돌지 말지가 이 구분에 달려 있다.
 *
 * <h2>🔴 방송 시작 직후 「보정값만큼」은 {@code NO_FOOTAGE}다 — 결함이 아니라 정의다</h2>
 * 기본 보정값이 양수이므로(2026-08-24 실측 3.9초) {@code messageTime}에서 그만큼을 빼면
 * <b>방송이 시작하고 처음 그 시간 동안의 채팅은 첫 조각보다 이른 시각</b>이 된다. 그래서
 * <b>모든 방송이 시작할 때마다</b> 그 구간이 {@code NO_FOOTAGE}(영영 없음)로 나간다.
 *
 * <p><b>그것이 옳다.</b> 보정값의 뜻은 「이 채팅은 그만큼 전의 화면에 대한 반응이다」이고,
 * 방송이 막 시작한 시점에 그 화면은 녹화에 없다. 「다시 물으면 답이 바뀔 수 있다」도 거짓이
 * 아니다 — 장부는 INSERT만 되고 seq 1이 첫 조각이라 그 사실은 뒤집히지 않는다.
 *
 * <p><b>버그로 보고 「위치 0으로 접어 주는」 처방을 넣지 마라</b> — 없던 화면을 가리키는 클립이
 * 만들어진다. 판정 축({@link VideoPosition.State} javadoc)의 <b>위험한 쪽</b>이므로 부르는
 * 쪽(clip·판별기)이 이 구간을 알고 배선해야 한다.
 * {@code VideoPositionCalculatorTest.방송_시작_직후_보정값만큼은_영영_없다()}가 <b>실물 yml
 * 기본값이 걸린 컨텍스트에서</b> 이 갈래를 잰다 — 보정 0으로 만든 계산기로는 안 지나간다.
 *
 * <h2>판 2 행과 판 NULL 행은 따로 고르고 따로 잰다(계약 세그먼트인덱스 6-6-9)</h2>
 * 판 NULL 행(옛 후보 A)은 파일명 시각({@code start_wall_utc}) floor · 아래 클램프 식이고, 판 2 행(판 2 후보 C)은
 * {@code playback_pdt} floor · F(6-6-6 — {@link LedgerTimeline#locate})다. 판마다 자기 보정값으로 후보를 찾고 둘 다
 * 있으면 seq 큰 쪽이다 — 스트림 범위이고 회차로 자르지 않는다. 판 2 후보에는 접힘이 없다: 조각 안이면 그 자리, 이어진
 * 이웃 사이 틈(녹화가 이어진 새 장의 건너뜀 포함)이면 뒤 조각 시작, 1초 미만 겹친 시각이면 나중 조각이다(경계 표 B8).
 *
 * <h2>옛 행의 {@code min(delta, duration)} 클램프 — 실효 상한이 1500ms다</h2>
 * 등식 {@code next.start_pts == floor.start_pts + floor.duration}은 같은 장 안 맞닿은 이웃에서 선다
 * ({@code media/internal/indexer/indexer.go:528}). 장 경계에서는 PDT가 뛴 만큼(겹치면 그만큼 작게) 다르다(계약 6-6-9).
 * 그래서 옛 행에서는 드리프트로 {@code delta}가 길이를 넘는 구간에서도 위치가 조각 끝에 머물다가 경계에서 다음 조각의
 * 시작으로 이어진다 — <b>뒤로 튀지 않는다.</b>
 *
 * <p>대신 <b>그 구간의 채팅이 조각 끝 한 점에 접힌다.</b> 얼마나 접히나 = 갭이 불연속으로
 * 안 서는 한계, 즉 {@code SEGMENT_DRIFT_TOLERANCE_MS = 1500}이다
 * ({@code media/internal/indexer/indexer.go:114} · {@code media/README.md:167}).
 * PRD가 말한 「미세 어긋남 … ms급」과 자릿수가 다르다 — <b>알고 받아들인 것이다.</b>
 * 1번이 그 상수를 바꾸면 이 문장의 숫자도 같이 바뀐다. 이 상한과 접힘은 <b>옛 행 한정</b>이다 — 판 2 행은 F라 접지 않는다.
 *
 * <h2>{@code is_discontinuity}는 「PTS가 끊겼다」와 동치가 아니다</h2>
 * 표지는 drift가 tolerance를 넘을 때만 서는 것이 아니다 — 훅이 세션 경계를 확인해도 선다
 * ({@code indexer.go:556}의 {@code isDiscont = isDiscont || breakHit}). 그때 PTS는
 * {@code cur.NextPTSMS()}로 <b>정확히 이어져 있어</b>({@code indexer.go:527-528}) 위 등식이
 * <b>표지가 선 조각에서도 성립한다.</b> 즉 표지와 PTS 연속성은 별개다.
 *
 * <p>그 경우 우리는 연장 갈래를 안 타고 {@code delta ≥ duration}에서 {@code NO_FOOTAGE}를 준다.
 * <b>일부러 그렇다</b> — 그 벽시계 구간은 방송이 실제로 끊겨 녹화가 없었던 시간이고, 「없던
 * 하이라이트를 만들지 않는다」는 PRD의 공백 정책과 같은 방향이다. PRD 자체가 「이어져 있음」을
 * <b>표지 없음</b>으로 정의했다. <b>「표지 = PTS 끊김」으로 읽고 연장 조건을 {@code start_pts}
 * 비교로 바꾸지 마라</b> — 그러면 녹화가 없던 구간이 변환된다.
 *
 * <h2>벽시계가 역행한 구간은 접는다</h2>
 * 1번 인덱서는 drift가 음수이고 tolerance를 넘으면 {@code log.Error("negative_drift")}를 찍고
 * <b>그 행을 그대로 INSERT한다</b>({@code indexer.go:538-548}). 즉 이 데이터는 실재하고,
 * 그 위에서 산수를 그대로 돌리면 <b>「변환됨」을 주면서 위치가 뒤로 튄다</b> — 부르는 쪽은
 * 그것을 믿고 엉뚱한 장면을 가리키는 클립을 만든다.
 *
 * <p><b>1번의 ERROR 로그로는 못 갈음한다.</b> 그것은 다른 서버의 로그이고 <b>조각을 넣을 때</b>
 * 찍히지 우리가 변환할 때 찍히지 않는다. 그래서 우리가 데이터로 알아채고 우리 로그에 남긴다.
 *
 * <h2>{@code upload_state}를 안 본다</h2>
 * 이유는 {@link SegmentLedger} javadoc에 있다 — 여기 복사하지 않는다(한쪽만 낡는다).
 */
@Component
public class VideoPositionCalculator {

    private static final Logger log = LoggerFactory.getLogger(VideoPositionCalculator.class);

    private final SegmentLedger ledger;
    private final IngestKeyStore ingestKeys;
    private final SyncProperties properties;

    public VideoPositionCalculator(SegmentLedger ledger, IngestKeyStore ingestKeys, SyncProperties properties) {
        this.ledger = ledger;
        this.ingestKeys = ingestKeys;
        this.properties = properties;
    }

    /**
     * @param channelId 보정값을 고르는 열쇠. 모르면 {@code null}을 줘도 된다(기본값이 쓰인다)
     */
    public VideoPosition locate(String streamId, String channelId, Instant messageTime) {
        Offsets offsets = new Offsets(properties.offsetFor(channelId), properties.v2OffsetFor(channelId));
        // 장부는 물리 키로 찾는다(POK-233). 부르는 쪽(판별기)은 방송 번호를 주고, 그것은 회차 번호라 장부에 없는 이름이다.
        // 로그에는 방송 번호를 그대로 남긴다(어느 방송이 아픈지가 진단이다)
        String ledgerKey = ingestKeys.keyOf(streamId);
        // 음수 보정이면 미래로 간다 — minusMillis가 그것을 그대로 처리한다.
        Instant legacyAt = messageTime.minusMillis(offsets.legacy());
        Instant v2At = messageTime.minusMillis(offsets.v2());

        Pick pick = pick(streamId, ledgerKey, legacyAt, v2At);
        if (pick.chosen().isEmpty()) {
            if (pick.legacyInverted()) {
                return VideoPosition.noFootage(offsets.legacy());
            }
            // 「이 시각보다 이른 조각이 없다」와 「조각이 하나도 없다」는 다른 상태다.
            // 앞은 첫 조각 이전(영영 없음), 뒤는 장부가 아직(다시 물으면 됨).
            if (!ledger.hasAnySegment(ledgerKey)) {
                return VideoPosition.notYetIndexed(offsets.legacy());
            }
            // 🔴 여기서 곧장 NO_FOOTAGE를 답하면 안 된다. 위 왕복들 「사이」에 이 방송의 첫
            // 조각이 들어오면 「floor 빈손 + 조각 있음」이 서는데, 그 조각의 벽시계는 물어본
            // 시각보다 이르므로 지금 다시 물으면 CONVERTED다 — 「영영 없음」은 거짓이고
            // 부르는 쪽은 그것을 믿고 재시도를 그만둔다. 채팅에는 백필이 없어 그 채팅의
            // 하이라이트가 영영 없어진다(감사 2가 창 300ms 주입으로 재현).
            //
            // 그래서 빈손일 때만 두 후보를 같은 순서로 한 번 더 묻는다. 재조회도 빈손이면 진짜 첫 조각 이전이고
            // (장부는 INSERT만 되므로 그 사실은 뒤집히지 않는다), 잡히면 그것이 더 최신이라
            // 더 정확하다. 존재 확인을 floor보다 「먼저」 묻는 순서로도 닫히지만, 그러면
            // 변환되는 경로 전부가 왕복 하나를 더 쓴다 — 판별기(POK-59)가 채팅마다 부른다.
            pick = pick(streamId, ledgerKey, legacyAt, v2At);
            if (pick.chosen().isEmpty()) {
                return VideoPosition.noFootage(offsets.legacy());
            }
        }

        LedgerSegment segment = pick.chosen().get();
        Optional<LedgerSegment> next = ledger.nextAfterSeq(ledgerKey, segment.seq());
        return segment.v2()
                ? v2Position(segment, next, v2At.toEpochMilli(), offsets.v2())
                : legacyPosition(segment, next, legacyAt, offsets.legacy());
    }

    /** 판마다의 보정값. 판 2 는 기본 보정값에서 판 2 차감을 뺀 값이다({@link SyncProperties}). */
    private record Offsets(long legacy, long v2) {
    }

    /**
     * 섞인 floor 한 번(계약 6-6-9). {@code chosen}은 옛 후보 · 판 2 후보 가운데 seq 큰 쪽이고, {@code legacyInverted}는
     * 옛 후보가 벽시각 역행이라 버려졌는가다.
     */
    private record Pick(Optional<LedgerSegment> chosen, boolean legacyInverted) {
    }

    private Pick pick(String streamId, String ledgerKey, Instant legacyAt, Instant v2At) {
        Optional<LedgerFloor> legacyFloor = ledger.floorByWallClock(ledgerKey, legacyAt);
        boolean inverted = legacyFloor.isPresent() && legacyFloor.get().wallClockInverted();
        if (inverted) {
            // 실을 것은 셋뿐이다. 채팅 시각·본문·채널 식별자는 이 서버의 유출 방어선 안쪽이라
            // 진단이 아쉬워도 넣지 않는다 — 방송 번호와 조각 번호로 장부를 직접 볼 수 있다.
            log.warn("chat.sync.wall_clock_inverted stream={} floorSeq={} maxCandidateSeq={}",
                    streamId, legacyFloor.get().segment().seq(), legacyFloor.get().maxCandidateSeq());
        }
        // 역행이면 옛 후보만 버린다 — 판 2 후보가 있으면 그것으로 간다(계약 6-6-9 · cc AC-M1).
        Optional<LedgerSegment> legacy = inverted ? Optional.empty() : legacyFloor.map(LedgerFloor::segment);
        Optional<LedgerSegment> v2 = ledger.floorByPlaybackPdt(ledgerKey, v2At);
        if (legacy.isEmpty() || v2.isEmpty()) {
            return new Pick(legacy.isPresent() ? legacy : v2, inverted);
        }
        return new Pick(Optional.of(legacy.get().seq() > v2.get().seq() ? legacy.get() : v2.get()), inverted);
    }

    /**
     * 판 2 후보 — 고른 행과 그 seq 다음 행 둘로 F를 돌린다(계약 6-6-9 · 6-6-6 2 · 3항). 조각 안이면 그 자리, 이어진 다음
     * 행이면 그 시작, 끊긴 다음 행이면 공백(영상 없음), 다음 행이 없으면 아직이다. 위치는 고른 조각의 {@code start_pts}에 매체
     * 자리를 더한 값이다 — 판 2 행은 위치와 PDT가 같은 영상 시작 축이다.
     */
    private static VideoPosition v2Position(LedgerSegment floor, Optional<LedgerSegment> next, long t, long offset) {
        List<LedgerRow> rows = next.isEmpty()
                ? List.of(pdtRow(floor))
                : List.of(pdtRow(floor), nextRow(next.get(), t));
        return switch (LedgerTimeline.locate(rows, t)) {
            case Place.Media media -> {
                LedgerSegment at = media.seq() == floor.seq() ? floor : next.orElseThrow();
                yield VideoPosition.converted(at.startPtsMs() + media.offsetMs(), at.seq(), offset);
            }
            case Place.Gap gap -> VideoPosition.noFootage(offset);
            case Place.AfterLast afterLast -> VideoPosition.notYetIndexed(offset);
            case Place.BeforeFirst beforeFirst -> throw new IllegalStateException(
                    "판 2 floor 조각이 물어본 시각보다 늦다: seq=" + floor.seq());
        };
    }

    private static LedgerRow pdtRow(LedgerSegment segment) {
        return new LedgerRow(segment.seq(), segment.playbackPdt().toEpochMilli(), segment.durationMs(), false);
    }

    /**
     * 다음 행은 판과 무관한 seq 다음 행이다. 판 2 면 자기 PDT에 선다. 판 NULL 이면(판 2 회차 뒤 옛 회차 — 롤백 경계) 이 축의
     * 값이 없다 — 계약 6-6-9 는 그 행의 이어짐({@code is_discontinuity})만 보므로, F가 고른 행을 바꾸지 않게 물어본 시각
     * 바로 뒤에 세운다.
     */
    private static LedgerRow nextRow(LedgerSegment next, long t) {
        long startMs = next.v2() ? next.playbackPdt().toEpochMilli() : t + 1;
        return new LedgerRow(next.seq(), startMs, next.durationMs(), !next.discontinuity());
    }

    /** 옛 후보 — 지금 식 그대로(파일명 시각에서 잰 거리 · 이어진 다음 행이면 조각 길이로 접음). */
    private static VideoPosition legacyPosition(LedgerSegment segment, Optional<LedgerSegment> next,
                                                Instant at, long offset) {
        long delta = at.toEpochMilli() - segment.startWallUtc().toEpochMilli();

        // 다음 조각이 이어져 있으면 이 조각의 끝은 그 조각의 시작이다 — 사이에 빈 곳이 없으므로
        // floor 정의상 delta가 길이를 넘어도 그 시각은 여전히 이 조각의 몫이다(미세 어긋남 연장).
        if (next.isPresent() && !next.get().discontinuity()) {
            return convert(segment, delta, offset);
        }
        if (delta < segment.durationMs()) {
            return convert(segment, delta, offset);
        }
        // 길이를 넘었고 이어짐도 아니다. 다음 조각이 있으면 그 사이는 진짜 공백이고,
        // 없으면 아직 안 들어온 것이다.
        return next.isPresent()
                ? VideoPosition.noFootage(offset)
                : VideoPosition.notYetIndexed(offset);
    }

    private static VideoPosition convert(LedgerSegment segment, long delta, long offset) {
        long position = segment.startPtsMs() + Math.min(delta, segment.durationMs());
        return VideoPosition.converted(position, segment.seq(), offset);
    }
}
