package com.pokeclip.chat.collector.sync;

import com.pokeclip.chat.collector.support.IntegrationTestSupport;
import com.pokeclip.chat.collector.support.StreamSegmentsFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 조각 장부 조회를 <b>실 PostgreSQL</b>에서 잰다. 가짜로 못 재는 것이 셋이다 —
 * {@code timestamptz} 왕복, {@code ORDER BY} 동률 처리, 스칼라 서브쿼리 둘의 값.
 *
 * <p><b>방송 번호 접두는 {@code ledger-}다.</b> 조각 장부는 Flyway 밖이라 컨텍스트가
 * 갈려도 표가 하나이고, 다른 검사 클래스({@code calc-}·{@code api-})와 같은 컨테이너를
 * 쓴다. 접두가 겹치면 단독은 통과하고 전체에서만 터진다.
 *
 * <p><b>시계 역행 셋은 지어낸 상황이 아니다.</b> 1번의 인덱서가 drift가 음수이고
 * tolerance를 넘으면 {@code log.Error("negative_drift")}를 찍고 <b>그 행을 그대로
 * INSERT한다</b>({@code media/internal/indexer/indexer.go:538-548}, 주석이 원인을
 * 「시계 역행 또는 NTP step」으로 명시). 그 로그는 1번 서버에서 조각을 넣을 때
 * 찍히지 우리가 변환할 때 찍히지 않는다 — 그래서 우리가 데이터로 알아채야 한다.
 */
@SpringBootTest
@ActiveProfiles("test")
class SegmentLedgerTest extends IntegrationTestSupport {

    private static final Instant T0 = Instant.parse("2026-08-24T00:00:00Z");

    private static final String NORMAL = "ledger-normal";
    private static final String SEQ_ORDER = "ledger-seq-order";
    private static final String TIE = "ledger-tie";
    private static final String INVERTED = "ledger-inverted";
    private static final String NO_SEGMENT = "ledger-no-segment";

    private static final List<String> STREAMS = List.of(NORMAL, SEQ_ORDER, TIE, INVERTED, NO_SEGMENT);

    private final SegmentLedger ledger;
    private final JdbcTemplate jdbc;

    SegmentLedgerTest(SegmentLedger ledger, JdbcTemplate jdbc) {
        this.ledger = ledger;
        this.jdbc = jdbc;
    }

    @BeforeEach
    void 표를_세우고_내_방송_행만_비운다() {
        StreamSegmentsFixture.ensureTable(jdbc);
        STREAMS.forEach(streamId -> StreamSegmentsFixture.clear(jdbc, streamId));
    }

    /**
     * seq 순서와 벽시계 순서가 같은 평범한 방송. seq3만 불연속이다(매퍼 양성 대조용).
     *
     * <p><b>길이를 벽시계 간격과 일부러 다르게 준다</b>(4100·4200·4300 vs 간격 4000).
     * 둘을 같은 숫자로 두면 {@code start_pts_ms}와 {@code duration_ms}를 <b>바꿔 읽는
     * 매퍼가 여덟 검사를 전부 통과한다</b> — 결함 주입으로 실제로 초록이 나와서 고쳤다.
     * 조각 길이가 간격과 어긋나는 것은 실물이기도 하다(POK-167 실측 4036~4312ms).
     */
    private void 정상_방송을_넣는다() {
        StreamSegmentsFixture.insert(jdbc, NORMAL, 1, 0, T0, 4100, false);
        StreamSegmentsFixture.insert(jdbc, NORMAL, 2, 4000, T0.plusMillis(4000), 4200, false);
        StreamSegmentsFixture.insert(jdbc, NORMAL, 3, 8000, T0.plusMillis(8000), 4300, true);
    }

    /**
     * 벽시계 순서가 seq 순서와 <b>다른</b> 방송. seq1(T0) → seq3(T0+4s) → seq2(T0+8s)다.
     * {@code nextAfterSeq}를 벽시계로 정렬하는 구현이면 seq1의 다음이 seq3이 된다.
     */
    private void 벽시계가_seq와_어긋난_방송을_넣는다() {
        StreamSegmentsFixture.insert(jdbc, SEQ_ORDER, 1, 0, T0, 4100, false);
        StreamSegmentsFixture.insert(jdbc, SEQ_ORDER, 2, 4000, T0.plusMillis(8000), 4200, true);
        StreamSegmentsFixture.insert(jdbc, SEQ_ORDER, 3, 8000, T0.plusMillis(4000), 4300, false);
    }

    /**
     * seq1의 벽시계가 seq2보다 10초 늦다 — 시계가 뒤로 튄 뒤에 seq2가 들어온 모양이다.
     * <b>같은 데이터를 두 시각으로 물어 역행 신호 둘을 각각 켠다</b>(계획 규칙표 1·2행).
     */
    private void 시계가_역행한_방송을_넣는다() {
        StreamSegmentsFixture.insert(jdbc, INVERTED, 1, 0, T0.plusMillis(10_000), 4000, false);
        StreamSegmentsFixture.insert(jdbc, INVERTED, 2, 4000, T0, 4000, false);
    }

    /**
     * 칸 다섯을 전부 단언한다 — seq만 보면 {@code start_pts_ms}와 {@code duration_ms}를
     * 바꿔 읽는 매퍼도 초록이다. {@code discontinuity}는 <b>거짓 한 번·참 한 번</b>을
     * 같이 본다(늘 false를 돌려주는 매퍼를 막는 양성 대조).
     */
    @Test
    void 시각_이하에서_가장_늦은_조각을_준다() {
        정상_방송을_넣는다();

        LedgerSegment floor = ledger.floorByWallClock(NORMAL, T0.plusMillis(5000)).orElseThrow().segment();
        assertThat(floor.seq()).isEqualTo(2L);
        assertThat(floor.startPtsMs()).isEqualTo(4000L);
        assertThat(floor.startWallUtc()).isEqualTo(T0.plusMillis(4000));
        assertThat(floor.durationMs()).as("pts와 다른 숫자여야 바꿔 읽는 매퍼가 잡힌다").isEqualTo(4200);
        assertThat(floor.discontinuity()).isFalse();

        LedgerSegment last = ledger.floorByWallClock(NORMAL, T0.plusMillis(9000)).orElseThrow().segment();
        assertThat(last.seq()).isEqualTo(3L);
        assertThat(last.discontinuity()).as("불연속 표지를 실제로 읽어 온다").isTrue();
    }

    /**
     * 양성 대조가 곧 {@code <=} 경계 검사다 — {@code start_wall_utc <= ?}를 {@code <}로
     * 바꾸면 첫 조각의 시작 시각이 자기 조각을 못 잡아 아래 두 번째 줄이 빨간불이다.
     * 그 줄이 없으면 「언제나 빈손」인 구현도 통과한다.
     */
    @Test
    void 첫_조각보다_이른_시각이면_빈손이다() {
        정상_방송을_넣는다();

        assertThat(ledger.floorByWallClock(NORMAL, T0.minusMillis(1))).isEmpty();
        assertThat(ledger.floorByWallClock(NORMAL, T0))
                .as("첫 조각의 시작 시각은 그 조각 안이다")
                .isPresent();
    }

    /**
     * <b>「다음」은 벽시계가 아니라 seq다.</b> 조각 번호가 곧 재생 순서이고,
     * 시계가 튄 방송에서는 둘이 갈린다.
     */
    @Test
    void 다음_조각은_seq_기준이다() {
        벽시계가_seq와_어긋난_방송을_넣는다();

        LedgerSegment next = ledger.nextAfterSeq(SEQ_ORDER, 1).orElseThrow();
        assertThat(next.seq()).as("벽시계로 고르면 seq3이 온다").isEqualTo(2L);
        assertThat(next.startPtsMs()).isEqualTo(4000L);
        assertThat(next.durationMs()).isEqualTo(4200);
        assertThat(next.discontinuity()).isTrue();

        assertThat(ledger.nextAfterSeq(SEQ_ORDER, 3))
                .as("마지막 조각 뒤에는 아무것도 없다")
                .isEmpty();
    }

    @Test
    void 조각이_하나도_없으면_hasAnySegment가_거짓이다() {
        정상_방송을_넣는다();

        assertThat(ledger.hasAnySegment(NO_SEGMENT)).isFalse();
        assertThat(ledger.hasAnySegment(NORMAL))
                .as("언제나 거짓인 구현을 막는 양성 대조")
                .isTrue();
    }

    /**
     * 조각 둘의 벽시계가 같은 ms일 때 뒤엣것(seq 큰 쪽)이 이긴다.
     * {@code ORDER BY start_wall_utc DESC}만 있고 {@code , seq DESC}가 없으면
     * 어느 행이 올지가 PostgreSQL 마음이다.
     *
     * <p><b>같은 벽시계는 역행이 아니다</b> — 신호 둘이 켜지지 않는 것까지 같이 본다.
     * {@code >}를 {@code >=}로 느슨하게 쓴 EXISTS면 여기서 오탐이 난다.
     */
    @Test
    void 같은_벽시계_시각이면_seq_큰_쪽이다() {
        StreamSegmentsFixture.insert(jdbc, TIE, 1, 0, T0, 4100, false);
        StreamSegmentsFixture.insert(jdbc, TIE, 2, 4000, T0, 4200, false);

        LedgerFloor floor = ledger.floorByWallClock(TIE, T0.plusMillis(1000)).orElseThrow();
        assertThat(floor.segment().seq()).isEqualTo(2L);
        assertThat(floor.segment().startPtsMs()).isEqualTo(4000L);
        assertThat(floor.wallClockInverted()).as("같은 시각은 역행이 아니다").isFalse();
    }

    /**
     * <b>{@code maxCandidateSeq}만으로는 못 잡는 갈래다.</b> floor로 뽑힌 seq2가
     * 후보 중 seq가 가장 큰 행이기도 해서 그 신호는 깨끗하다 — 그런데 seq1이
     * 10초 미래에 있다. 신호 둘을 {@code OR}로 묶어야 여기가 켜진다.
     */
    @Test
    void 앞_조각이_미래에_있으면_earlierIsFuture가_참이다() {
        시계가_역행한_방송을_넣는다();

        LedgerFloor floor = ledger.floorByWallClock(INVERTED, T0.plusMillis(1000)).orElseThrow();
        assertThat(floor.segment().seq()).isEqualTo(2L);
        assertThat(floor.maxCandidateSeq()).as("이 신호는 깨끗하다 — 그래서 둘이 필요하다").isEqualTo(2L);
        assertThat(floor.earlierIsFuture()).isTrue();
        assertThat(floor.wallClockInverted()).isTrue();
    }

    /**
     * 위와 <b>같은 데이터를 seq1 안쪽 시각으로</b> 묻는다. 이번엔 floor가 seq1이고
     * 후보에 seq2가 섞여 있어 {@code maxCandidateSeq}가 floor와 어긋난다 —
     * 이쪽은 {@code earlierIsFuture}가 깨끗하다. 두 검사가 서로의 반대편을 맡는다.
     */
    @Test
    void 뒤_조각이_과거로_튀면_maxCandidateSeq가_floor와_다르다() {
        시계가_역행한_방송을_넣는다();

        LedgerFloor floor = ledger.floorByWallClock(INVERTED, T0.plusMillis(11_000)).orElseThrow();
        assertThat(floor.segment().seq()).isEqualTo(1L);
        assertThat(floor.maxCandidateSeq()).isEqualTo(2L);
        assertThat(floor.earlierIsFuture()).as("이쪽은 깨끗하다 — 그래서 둘이 필요하다").isFalse();
        assertThat(floor.wallClockInverted()).isTrue();
    }

    /**
     * 오탐 0을 재는 자리다. 신호를 「늘 참」으로 만들면 이 검사만 빨간불이고
     * 나머지 일곱은 전부 초록이다 — 그래서 지우지 마라.
     * 첫 조각·중간·마지막 세 지점을 다 본다.
     */
    @Test
    void 정상_방송이면_역행_신호가_둘_다_깨끗하다() {
        정상_방송을_넣는다();

        for (long at : new long[]{0, 5000, 9000}) {
            LedgerFloor floor = ledger.floorByWallClock(NORMAL, T0.plusMillis(at)).orElseThrow();
            assertThat(floor.maxCandidateSeq())
                    .as("%dms 지점", at)
                    .isEqualTo(floor.segment().seq());
            assertThat(floor.earlierIsFuture()).as("%dms 지점", at).isFalse();
            assertThat(floor.wallClockInverted()).as("%dms 지점", at).isFalse();
        }
    }
}
