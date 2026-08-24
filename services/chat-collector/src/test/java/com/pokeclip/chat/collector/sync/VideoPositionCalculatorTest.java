package com.pokeclip.chat.collector.sync;

import com.pokeclip.chat.collector.support.IntegrationTestSupport;
import com.pokeclip.chat.collector.support.StreamSegmentsFixture;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 채팅 시각 → 영상 위치 변환을 <b>실 PostgreSQL</b>에서 잰다. 계산기는 장부 조회 결과 위에서만
 * 돌지만, 판정을 가르는 것이 floor 선택과 역행 신호라 가짜 장부로 재면 그 둘이 사라진다.
 *
 * <p><b>방송 번호 접두는 {@code calc-}다.</b> 조각 장부는 Flyway 밖이라 컨텍스트가 갈려도 표가
 * 하나이고 {@code ledger-}·{@code api-}와 같은 컨테이너를 쓴다 — 겹치면 단독은 통과하고
 * 전체에서만 터진다.
 *
 * <p><b>계산기를 빈으로 안 받고 직접 만든다.</b> 보정값을 시험마다 갈아야 하고(T3),
 * {@code new SyncProperties(0, Map.of())}가 「보정 없음」을 눈에 보이게 한다. 빈 배선 자체는
 * 컨텍스트가 뜨는 것으로 이미 지켜진다.
 *
 * <h2>기준 데이터가 왜 이 숫자인지</h2>
 * {@link #정상_방송을_넣는다()}의 네 조각은 1번 인덱서의 PTS 규칙과 <b>산술적으로 맞물려
 * 있다</b>({@code media/internal/indexer/indexer.go:521-570}). 이어진 조각은
 * {@code pts = 앞 pts + 앞 duration}이고 불연속 조각은 거기에 {@code max(0, drift)}가 더해진다 —
 * 그래서 벽시계 간격 4000·길이 4000이면 pts가 0·4000·8000이 되고, seq3 뒤 12초 공백은
 * drift 8000을 실어 seq5의 pts를 20000으로 만든다. <b>숫자 하나를 바꾸면 나머지가 같이
 * 움직인다</b> — 임의로 고치지 마라.
 */
@SpringBootTest
@ActiveProfiles("test")
class VideoPositionCalculatorTest extends IntegrationTestSupport {

    private static final Instant T0 = Instant.parse("2026-08-24T00:00:00Z");

    private static final String NORMAL = "calc-normal";
    private static final String DRIFT = "calc-drift";
    private static final String EMPTY = "calc-empty";
    private static final String LATE = "calc-late";
    private static final String INVERTED = "calc-inverted";
    private static final String FAR_JUMP = "calc-far-jump";

    private static final List<String> STREAMS = List.of(NORMAL, DRIFT, EMPTY, LATE, INVERTED, FAR_JUMP);

    private final JdbcTemplate jdbc;
    private final VideoPositionCalculator calculator;

    VideoPositionCalculatorTest(SegmentLedger ledger, JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.calculator = new VideoPositionCalculator(ledger, new SyncProperties(0, Map.of()));
    }

    @BeforeEach
    void 표를_세우고_내_방송_행만_비운다() {
        StreamSegmentsFixture.ensureTable(jdbc);
        STREAMS.forEach(streamId -> StreamSegmentsFixture.clear(jdbc, streamId));
    }

    /**
     * 이어진 조각 셋 + 12초 공백 뒤의 불연속 조각 하나.
     *
     * <pre>
     * seq  pts     wall      duration  불연속
     *  1   0       T0        4000      X
     *  2   4000    T0+4s     4000      X
     *  3   8000    T0+8s     4000      X      ← 다음이 불연속이다
     *  5   20000   T0+20s    4000      O      ← 마지막
     * </pre>
     */
    private void 정상_방송을_넣는다() {
        StreamSegmentsFixture.insert(jdbc, NORMAL, 1, 0, T0, 4000, false);
        StreamSegmentsFixture.insert(jdbc, NORMAL, 2, 4000, T0.plusMillis(4000), 4000, false);
        StreamSegmentsFixture.insert(jdbc, NORMAL, 3, 8000, T0.plusMillis(8000), 4000, false);
        StreamSegmentsFixture.insert(jdbc, NORMAL, 5, 20000, T0.plusMillis(20_000), 4000, true);
    }

    /**
     * seq1이 <b>자기가 잰 길이(3900)보다 100ms 늦게</b> 끝난 것으로 보이는 방송.
     * 갭 100ms는 tolerance(1500ms) 이내라 불연속이 안 서고 PTS는 정확히 이어진다 —
     * {@code seq2.pts == seq1.pts + seq1.duration == 3900}.
     */
    private void 드리프트가_있는_방송을_넣는다() {
        StreamSegmentsFixture.insert(jdbc, DRIFT, 1, 0, T0, 3900, false);
        StreamSegmentsFixture.insert(jdbc, DRIFT, 2, 3900, T0.plusMillis(4000), 4000, false);
    }

    /**
     * seq1의 벽시계가 seq2보다 10초 <b>늦다</b>. 1번 인덱서가 실제로 만드는 데이터다 —
     * drift가 음수이고 tolerance를 넘으면 {@code log.Error("negative_drift")}를 찍고
     * <b>그 행을 그대로 INSERT한다</b>({@code indexer.go:538-548}).
     * 같은 데이터를 두 시각으로 물어 역행 신호 둘을 각각 켠다.
     */
    private void 시계가_역행한_방송을_넣는다() {
        StreamSegmentsFixture.insert(jdbc, INVERTED, 1, 0, T0.plusMillis(10_000), 4000, false);
        StreamSegmentsFixture.insert(jdbc, INVERTED, 2, 4000, T0, 4000, false);
    }

    /**
     * seq1~4는 정상인데 <b>seq10 하나만 과거로 튄</b> 방송. 위 {@link #시계가_역행한_방송을_넣는다()}와
     * 같은 종류의 데이터인데 <b>튄 조각이 floor에서 멀다.</b>
     *
     * <pre>
     * seq  pts     wall       비고
     *  1   0       T0
     *  2   4000    T0+4s
     *  3   8000    T0+8s      ← T0+9s를 물으면 여기가 floor다
     *  4   12000   T0+12s     ← floor의 바로 다음. 벽시계가 floor보다 늦다 — 쌍이 멀쩡해 보인다
     * 10   16000   T0+6s      ← 과거로 튄 조각. maxCandidateSeq만 이것을 본다
     * </pre>
     *
     * <p><b>seq4가 이 데이터의 핵심이다.</b> 그것이 없으면 floor의 바로 다음이 튄 seq10 자신이라
     * 「floor와 그 다음만 비교하는」 구현도 잡아낸다 — 그러면 이 검사가 후보 집합 전체의 최댓값을
     * 보는 이유를 못 지킨다. seq4를 넣으면 그 쌍이 비감소라 <b>{@code maxCandidateSeq}만이
     * 유일한 그물</b>이 된다({@link LedgerFloor} javadoc의 그 데이터다).
     */
    private void 먼_조각만_과거로_튄_방송을_넣는다() {
        StreamSegmentsFixture.insert(jdbc, FAR_JUMP, 1, 0, T0, 4000, false);
        StreamSegmentsFixture.insert(jdbc, FAR_JUMP, 2, 4000, T0.plusMillis(4000), 4000, false);
        StreamSegmentsFixture.insert(jdbc, FAR_JUMP, 3, 8000, T0.plusMillis(8000), 4000, false);
        StreamSegmentsFixture.insert(jdbc, FAR_JUMP, 4, 12_000, T0.plusMillis(12_000), 4000, false);
        StreamSegmentsFixture.insert(jdbc, FAR_JUMP, 10, 16_000, T0.plusMillis(6000), 4000, false);
    }

    private VideoPosition 물어본다(String streamId, long afterT0Ms) {
        return calculator.locate(streamId, "calc-channel", T0.plusMillis(afterT0Ms));
    }

    /**
     * <b>seq1과 seq2를 같이 봐야 한다. 어느 한쪽만 보면 결함 하나가 통째로 숨는다.</b>
     *
     * <ul>
     *   <li>seq2가 없으면 — seq1의 {@code start_pts_ms}가 0이라 <b>pts를 안 더하는</b>
     *       구현도 초록이다. 5500은 4000을 실제로 더해야 나온다</li>
     *   <li>seq1이 없으면 — seq2는 {@code start_pts_ms}와 {@code duration_ms}가 <b>둘 다
     *       4000</b>이라 그 둘을 <b>바꿔 읽는</b> 구현도 초록이다({@code 4000 + min(δ,4000)}이
     *       정답과 같다). 태스크 1에서 실제로 초록이 나왔던 위장과 같은 모양이다</li>
     * </ul>
     *
     * <p>두 줄을 다 두면 주입이 잡는다(매퍼 스왑 9건 · 계산기 안 스왑 5건 빨간불).
     * <b>한쪽을 지우거나 seq2만 남기지 마라.</b>
     */
    @Test
    void 조각_안의_시각은_pts로_변환된다() {
        정상_방송을_넣는다();

        VideoPosition first = 물어본다(NORMAL, 1500);
        assertThat(first.state()).isEqualTo(VideoPosition.State.CONVERTED);
        assertThat(first.positionMs()).isEqualTo(1500L);
        assertThat(first.segmentSeq()).isEqualTo(1L);
        assertThat(first.appliedOffsetMs()).as("보정 0으로 만든 계산기다").isZero();

        VideoPosition second = 물어본다(NORMAL, 5500);
        assertThat(second.state()).isEqualTo(VideoPosition.State.CONVERTED);
        assertThat(second.positionMs()).as("pts 4000 + delta 1500").isEqualTo(5500L);
        assertThat(second.segmentSeq()).isEqualTo(2L);
    }

    /** 조각 경계에서 위치가 튀지 않는다 — 3999는 seq1의 끝, 4000은 seq2의 시작이다. */
    @Test
    void 경계_직전과_직후가_연속이다() {
        정상_방송을_넣는다();

        VideoPosition before = 물어본다(NORMAL, 3999);
        VideoPosition after = 물어본다(NORMAL, 4000);

        assertThat(before.positionMs()).isEqualTo(3999L);
        assertThat(before.segmentSeq()).isEqualTo(1L);
        assertThat(after.positionMs()).as("1ms 뒤인데 조각이 바뀐다").isEqualTo(4000L);
        assertThat(after.segmentSeq()).isEqualTo(2L);
    }

    /**
     * <b>미세 어긋남 「연장」 갈래를 재는 유일한 자리다 — 위치는 일부러 안 본다.</b>
     * 3950은 seq1의 길이(3900)를 넘었지만 다음 조각이 이어져 있으므로 여전히 seq1의 몫이다.
     * 연장 갈래를 지우면 여기가 {@code NO_FOOTAGE}가 되고, 그 순간 <b>PRD 성공 기준
     * 「이어진 조각 사이의 ms급 어긋남은 공백으로 오판하지 않는다」가 깨진다.</b>
     *
     * <p><b>위치를 단언하지 않는 것이 이 검사의 설계다.</b> 단언하면 클램프 결함까지 여기서
     * 빨간불이 나고, 그러면 아래 {@link #드리프트_구간은_조각_끝에_머문다()}와 그물이 겹쳐
     * <b>이름이 무엇을 지키는지가 흐려진다</b>. 두 규칙에 그물을 하나씩 준다.
     */
    @Test
    void 드리프트_구간도_공백이_아니다() {
        드리프트가_있는_방송을_넣는다();

        VideoPosition extended = 물어본다(DRIFT, 3950);
        assertThat(extended.state())
                .as("길이를 50ms 넘겼지만 다음 조각이 이어져 있다")
                .isEqualTo(VideoPosition.State.CONVERTED);
        assertThat(extended.segmentSeq()).as("아직 seq1의 몫이다").isEqualTo(1L);
    }

    /**
     * <b>{@code min(delta, duration)} 클램프를 재는 유일한 자리다.</b> 기준 데이터는 드리프트가
     * 0이라 조각 안에서 {@code delta}가 {@code duration}에 절대 못 닿는다 — 클램프를 지워도
     * 다른 검사가 전부 초록이다.
     *
     * <p>그리고 클램프의 값어치가 여기서 보인다: 3900에 머물다가 경계에서 <b>정확히 그 값으로</b>
     * 이어진다. 클램프가 없으면 3950이었다가 4000ms 지점에서 3900으로 <b>뒤로 튄다</b>.
     *
     * <p>이 검사는 연장 갈래가 살아 있어야 성립한다(3950이 그 갈래를 타야 클램프에 닿는다) —
     * 그래서 연장 갈래를 지우면 여기도 같이 빨간불이다. <b>반대 방향은 갈라져 있다</b>:
     * 클램프만 지우면 위 {@link #드리프트_구간도_공백이_아니다()}는 초록이고 여기만 빨갛다.
     */
    @Test
    void 드리프트_구간은_조각_끝에_머문다() {
        드리프트가_있는_방송을_넣는다();

        assertThat(물어본다(DRIFT, 3899).positionMs())
                .as("아직 길이 안이라 클램프가 안 걸린다 — 상수 3900을 돌려주는 구현을 막는다")
                .isEqualTo(3899L);

        assertThat(물어본다(DRIFT, 3950).positionMs())
                .as("delta 3950인데 길이 3900에서 멈춘다")
                .isEqualTo(3900L);

        VideoPosition next = 물어본다(DRIFT, 4000);
        assertThat(next.positionMs()).as("경계에서 정확히 이어진다 — 뒤로 안 튄다").isEqualTo(3900L);
        assertThat(next.segmentSeq()).isEqualTo(2L);
    }

    /**
     * 다음 조각이 불연속이면 「연장」 갈래를 안 타므로 {@code delta < duration}만 남는다.
     * <b>그 안쪽은 여전히 변환된다</b> — 불연속은 조각 <i>사이</i>가 없다는 뜻이지
     * 앞 조각의 내용이 없다는 뜻이 아니다.
     */
    @Test
    void 불연속_직전_조각_안은_변환된다() {
        정상_방송을_넣는다();

        VideoPosition inside = 물어본다(NORMAL, 9500);
        assertThat(inside.state()).isEqualTo(VideoPosition.State.CONVERTED);
        assertThat(inside.positionMs()).isEqualTo(9500L);
        assertThat(inside.segmentSeq()).isEqualTo(3L);

        assertThat(물어본다(NORMAL, 11_999).positionMs()).as("길이 직전까지 변환된다").isEqualTo(11_999L);
    }

    /**
     * seq3이 끝난 뒤 seq5가 시작할 때까지 12초는 <b>녹화에 없다</b>.
     * 12000은 {@code delta == duration} 경계이기도 하다 — 마지막 조각이 아닌 쪽의 경계다.
     */
    @Test
    void 진짜_공백은_NO_FOOTAGE다() {
        정상_방송을_넣는다();

        for (long at : new long[]{12_000, 15_000, 19_999}) {
            VideoPosition gap = 물어본다(NORMAL, at);
            assertThat(gap.state()).as("%dms 지점", at).isEqualTo(VideoPosition.State.NO_FOOTAGE);
        }
    }

    /**
     * 마지막 조각의 길이를 넘은 시각은 <b>「아직 없다」이지 「영영 없다」가 아니다</b> —
     * 다음 조각이 곧 들어온다. 부르는 쪽이 다시 물으면 된다.
     */
    @Test
    void 마지막_조각의_길이를_넘으면_NOT_YET_INDEXED다() {
        정상_방송을_넣는다();

        assertThat(물어본다(NORMAL, 25_000).state()).isEqualTo(VideoPosition.State.NOT_YET_INDEXED);
    }

    /**
     * {@code delta < duration}의 경계다. {@code <}를 {@code <=}로 바꾸면 여기만 빨간불이 된다 —
     * 24000ms는 seq5의 마지막 프레임 <b>다음</b>이라 그 조각 안이 아니다.
     */
    @Test
    void 마지막_조각_길이와_정확히_같으면_NOT_YET_INDEXED다() {
        정상_방송을_넣는다();

        assertThat(물어본다(NORMAL, 24_000).state())
                .as("delta == duration은 조각 밖이다")
                .isEqualTo(VideoPosition.State.NOT_YET_INDEXED);
        assertThat(물어본다(NORMAL, 23_999).state())
                .as("1ms 앞은 조각 안이다 — 양성 대조")
                .isEqualTo(VideoPosition.State.CONVERTED);
    }

    /**
     * 「재시도 가능」이라는 판정의 뜻 자체를 잰다. 같은 시각·같은 방송인데 장부에 조각이
     * 하나 더 들어오는 것만으로 답이 뒤집힌다.
     */
    @Test
    void 다음_조각이_오면_NOT_YET이_CONVERTED로_바뀐다() {
        StreamSegmentsFixture.insert(jdbc, LATE, 1, 0, T0, 4000, false);

        assertThat(물어본다(LATE, 5000).state()).isEqualTo(VideoPosition.State.NOT_YET_INDEXED);

        StreamSegmentsFixture.insert(jdbc, LATE, 2, 4000, T0.plusMillis(4000), 4000, false);

        VideoPosition retried = 물어본다(LATE, 5000);
        assertThat(retried.state()).isEqualTo(VideoPosition.State.CONVERTED);
        assertThat(retried.positionMs()).isEqualTo(5000L);
        assertThat(retried.segmentSeq()).isEqualTo(2L);
    }

    /** floor가 불연속 조각이어도 그 안쪽은 변환된다. */
    @Test
    void 마지막_조각_안이면_변환된다() {
        정상_방송을_넣는다();

        VideoPosition inside = 물어본다(NORMAL, 21_000);
        assertThat(inside.state()).isEqualTo(VideoPosition.State.CONVERTED);
        assertThat(inside.positionMs()).isEqualTo(21_000L);
        assertThat(inside.segmentSeq()).isEqualTo(5L);
    }

    /** 첫 조각보다 이른 시각은 <b>영영 없다</b> — 그때는 녹화가 시작되지 않았다. */
    @Test
    void 첫_조각_이전은_NO_FOOTAGE다() {
        정상_방송을_넣는다();

        VideoPosition before = calculator.locate(NORMAL, "calc-channel", T0.minusMillis(1));
        assertThat(before.state()).isEqualTo(VideoPosition.State.NO_FOOTAGE);
    }

    /**
     * 조각이 <b>하나도</b> 없는 방송은 「장부가 아직」이다. 위 {@code 첫_조각_이전}과 답이
     * 갈리는 것이 이 판정의 존재 이유다 — 같은 「floor 빈손」인데 하나는 영구, 하나는 재시도다.
     */
    @Test
    void 장부가_비면_NOT_YET_INDEXED다() {
        정상_방송을_넣는다();

        assertThat(물어본다(EMPTY, 1000).state()).isEqualTo(VideoPosition.State.NOT_YET_INDEXED);
    }

    /** 조용한 0을 막는다 — 위치가 없는 판정이 {@code positionMs=0}으로 나가면 0초를 가리킨다. */
    @Test
    void CONVERTED가_아니면_위치가_null이다() {
        정상_방송을_넣는다();

        VideoPosition gap = 물어본다(NORMAL, 15_000);
        assertThat(gap.positionMs()).isNull();
        assertThat(gap.segmentSeq()).isNull();

        VideoPosition notYet = 물어본다(NORMAL, 25_000);
        assertThat(notYet.positionMs()).isNull();
        assertThat(notYet.segmentSeq()).isNull();

        VideoPosition converted = 물어본다(NORMAL, 1500);
        assertThat(converted.positionMs()).as("양성 대조 — 늘 null인 구현을 막는다").isNotNull();
        assertThat(converted.segmentSeq()).isNotNull();
    }

    /**
     * 역행이 없었다면 이 시각은 seq2 안이고 {@code position=5000}이 나갔을 것이다.
     * <b>그 답이 나가는 것이 최악이다</b> — 부르는 쪽은 「변환됨」을 믿고 그대로 쓴다.
     */
    @Test
    void 앞_조각이_미래에_있으면_NO_FOOTAGE다() {
        시계가_역행한_방송을_넣는다();

        VideoPosition inverted = 물어본다(INVERTED, 1000);
        assertThat(inverted.state()).isEqualTo(VideoPosition.State.NO_FOOTAGE);
        assertThat(inverted.positionMs()).isNull();
    }

    /** 같은 데이터를 seq1 안쪽 시각으로 묻는다 — 이번엔 다른 신호가 켜진다. */
    @Test
    void 뒤_조각이_과거로_튀면_NO_FOOTAGE다() {
        시계가_역행한_방송을_넣는다();

        VideoPosition inverted = 물어본다(INVERTED, 11_000);
        assertThat(inverted.state()).isEqualTo(VideoPosition.State.NO_FOOTAGE);
        assertThat(inverted.positionMs()).isNull();
    }

    /**
     * <b>역행의 셋째 모양이다 — 튄 조각이 floor에서 멀다.</b> 앞의 둘은 튄 조각이 floor의 바로
     * 이웃이라 「floor와 그 다음만 비교하는」 구현으로도 잡히지만, 이 데이터는 그 쌍이
     * 비감소라({@code seq3@T0+8s} → {@code seq4@T0+12s}) <b>{@code maxCandidateSeq}만이 그물이다.</b>
     *
     * <p>이 검사가 없으면 신호를 「후보 집합 전체의 최댓값」에서 이웃 비교로 바꾸는 「최적화」를
     * 막을 것이 아무것도 없다 — 나머지 역행 검사 둘은 그 구현에서도 초록이다.
     *
     * <p>대조로 튀기 전 구간을 같이 묻는다. <b>같은 방송에서 오탐이 0인 것까지 봐야</b>
     * 「역행 신호가 켜지면 무조건 접는다」가 아니라 「켜질 자리에만 켜진다」가 지켜진다.
     */
    @Test
    void 멀리_떨어진_조각만_과거로_튀어도_NO_FOOTAGE다() {
        먼_조각만_과거로_튄_방송을_넣는다();

        VideoPosition inverted = 물어본다(FAR_JUMP, 9000);
        assertThat(inverted.state())
                .as("floor는 seq3인데 후보 안에 seq10이 있다")
                .isEqualTo(VideoPosition.State.NO_FOOTAGE);
        assertThat(inverted.positionMs()).isNull();

        VideoPosition clean = 물어본다(FAR_JUMP, 5000);
        assertThat(clean.state())
                .as("튄 조각(T0+6s)보다 이른 구간은 후보에 안 들어온다 — 오탐 0")
                .isEqualTo(VideoPosition.State.CONVERTED);
        assertThat(clean.positionMs()).isEqualTo(5000L);
        assertThat(clean.segmentSeq()).isEqualTo(2L);
    }

    /**
     * 접었다는 사실이 <b>우리 로그에</b> 남아야 한다. 1번의 {@code negative_drift} ERROR는
     * 다른 서버의 로그이고 조각을 넣을 때 찍히지 우리가 변환할 때 찍히지 않는다.
     *
     * <p><b>실을 것은 셋뿐이다</b>({@code stream}·{@code floorSeq}·{@code maxCandidateSeq}).
     * 채팅 시각·채널 식별자는 이 서버의 유출 방어선 안쪽이다 — 아래 절반이 그것을 잰다.
     * 정상 방송에서 이 줄이 안 나오는 것까지 같이 본다(늘 경고하는 구현을 막는 양성 대조).
     */
    @Test
    void 시계_역행이면_경고_로그가_남는다() {
        시계가_역행한_방송을_넣는다();
        정상_방송을_넣는다();

        String channelId = "calc-leak-canary-channel";
        Instant messageTime = T0.plusMillis(11_000);

        try (LogCaptor logs = new LogCaptor()) {
            calculator.locate(INVERTED, channelId, messageTime);

            List<String> warned = logs.messages().stream()
                    .filter(line -> line.startsWith("chat.sync.wall_clock_inverted"))
                    .toList();
            assertThat(warned).hasSize(1);
            assertThat(warned.getFirst())
                    .contains("stream=" + INVERTED)
                    .contains("floorSeq=1")
                    .contains("maxCandidateSeq=2");
            assertThat(logs.levelOf("chat.sync.wall_clock_inverted")).isEqualTo(ch.qos.logback.classic.Level.WARN);

            assertThat(logs.messages())
                    .as("채널 식별자·채팅 시각은 어느 줄에도 안 실린다")
                    .noneMatch(line -> line.contains(channelId)
                            || line.contains(String.valueOf(messageTime.toEpochMilli()))
                            || line.contains(messageTime.toString()));
        }

        try (LogCaptor logs = new LogCaptor()) {
            calculator.locate(NORMAL, channelId, T0.plusMillis(1500));

            assertThat(logs.messages())
                    .as("정상 방송은 조용하다")
                    .noneMatch(line -> line.startsWith("chat.sync.wall_clock_inverted"));
        }
    }
}
