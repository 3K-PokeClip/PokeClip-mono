package com.pokeclip.chat.collector.sync;

import com.pokeclip.chat.collector.broadcast.IngestKeyStore;
import com.pokeclip.chat.collector.support.IntegrationTestSupport;
import com.pokeclip.chat.collector.support.StreamSegmentsFixture;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 판 2 행이 섞인 장부의 채팅 → 영상 위치(계약 세그먼트인덱스 6-6-9 · 설계 r6 7절 · r7 10절)를 <b>실 PostgreSQL</b>에서 잰다.
 *
 * <p>판 2 행(`timing_version = 2`)은 floor를 {@code playback_pdt}(영상 첫 표본)로 고르고 위치를 F(6-6-6)로 낸다. 판 NULL
 * 행은 지금 식(파일명 시각 floor · 조각 길이로 접음) 그대로다. 둘 다 있으면 판마다 고른 후보 가운데 seq 큰 쪽이다.
 *
 * <p><b>방송 번호 접두는 {@code v2-}다.</b> 조각 장부는 Flyway 밖이라 다른 검사 클래스와 표 하나를 같이 쓴다.
 *
 * <p>판 2 행의 파일명 시각은 PDT보다 앞에 둔다(파일 머리가 영상 시작보다 이르다 — 6-6-10 I11). 둘을 같은 값으로 두면
 * 「판 2 도 파일명 시각으로 floor」 인 옛 구현이 초록이 된다.
 */
@SpringBootTest
@ActiveProfiles("test")
class VideoPositionV2Test extends IntegrationTestSupport {

    private static final Instant T0 = Instant.parse("2026-11-13T00:00:00Z");

    private static final String HEAD = "v2-head";
    private static final String LEGACY_HEAD = "v2-legacy-head";
    private static final String MIXED = "v2-mixed";
    private static final String RESET = "v2-reset";
    private static final String LEGACY_SIGNAL = "v2-legacy-signal";
    private static final String SKIP = "v2-skip";
    private static final String RECONNECT = "v2-reconnect";
    private static final String TAIL = "v2-tail";
    private static final String LEGACY_FOLD = "v2-legacy-fold";
    private static final String OVERLAP = "v2-overlap";
    private static final String INVERTED = "v2-inverted";
    private static final String CORRECTED = "v2-corrected";

    private static final List<String> STREAMS = List.of(HEAD, LEGACY_HEAD, MIXED, RESET, LEGACY_SIGNAL, SKIP,
            RECONNECT, TAIL, LEGACY_FOLD, OVERLAP, INVERTED, CORRECTED);

    private final JdbcTemplate jdbc;
    private final SegmentLedger ledger;
    private final VideoPositionCalculator calculator;

    VideoPositionV2Test(SegmentLedger ledger, JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.calculator =
                new VideoPositionCalculator(ledger, new IngestKeyStore(jdbc), new SyncProperties(0, Map.of(), 0));
    }

    @BeforeEach
    void 표를_세우고_내_방송_행만_비운다() {
        StreamSegmentsFixture.ensureTable(jdbc);
        STREAMS.forEach(streamId -> StreamSegmentsFixture.clear(jdbc, streamId));
    }

    private VideoPosition 물어본다(String streamId, long afterT0Ms) {
        return calculator.locate(streamId, "v2-channel", T0.plusMillis(afterT0Ms));
    }

    private static Instant at(long afterT0Ms) {
        return T0.plusMillis(afterT0Ms);
    }

    /**
     * 위치 축({@code start_pts_ms})에서 그 위치를 다시 풀면 어느 조각인가 — 「{@code start_pts ≤ x} 인 행 가운데 seq 최대」
     * (계약 6-6-6 · 경계 표 B8). 제품 코드를 거치지 않고 장부에서 바로 읽는다.
     */
    private long 위치_축으로_되돌린_조각(String streamId, long positionMs) {
        return jdbc.queryForObject("""
                SELECT seq FROM public.stream_segments
                 WHERE stream_id = ? AND start_pts_ms <= ?
                 ORDER BY seq DESC LIMIT 1
                """, Long.class, streamId, positionMs);
    }

    private static void 변환됐다(VideoPosition position, long positionMs, long seq) {
        assertThat(position.state()).isEqualTo(VideoPosition.State.CONVERTED);
        assertThat(position.positionMs()).isEqualTo(positionMs);
        assertThat(position.segmentSeq()).isEqualTo(seq);
    }

    /**
     * I12 — 소리가 200ms 먼저 시작한 조각(파일 머리 = PDT − 200). 영상 시작 1초 뒤 채팅은 판 2 행이면 1,000ms, 같은 꼴의
     * 판 NULL 행이면 파일 머리부터 재 1,200ms 다.
     */
    @Test
    void 판_2_행은_PDT_로_floor를_고르고_영상_시작부터_잰다() {
        StreamSegmentsFixture.insertV2(jdbc, HEAD, 1, 0, at(0), at(200), 4000, false);
        StreamSegmentsFixture.insert(jdbc, LEGACY_HEAD, 1, 0, at(0), 4000, false);

        변환됐다(물어본다(HEAD, 1200), 1000, 1);
        변환됐다(물어본다(LEGACY_HEAD, 1200), 1200, 1);
    }

    /** 섞인 장부(판 NULL 뒤 판 2) — 판마다 후보를 찾고 seq 큰 쪽을 고른다. 앞 행 안 시각은 옛 행 그대로다. */
    @Test
    void 섞인_장부는_판마다_고른_후보_가운데_seq_큰_쪽이다() {
        StreamSegmentsFixture.insert(jdbc, MIXED, 1, 0, at(0), 4000, false);
        StreamSegmentsFixture.insertV2(jdbc, MIXED, 2, 4000, at(4000), at(4200), 4000, false);

        변환됐다(물어본다(MIXED, 5200), 5000, 2);
        변환됐다(물어본다(MIXED, 3000), 3000, 1);
    }

    /**
     * cx R17-4 반례 — 판 2 → NULL → 2. 앞 판 2 회차(seq 100)는 PDT가 앞서 있고 뒤 판 2 회차(seq 102)는 리셋됐다. 판 2 후보를
     * 「PDT 최대」 로 찾으면 seq 100 이 나오고 옛 후보 seq 101 과 겨뤄 엉뚱한 회차가 된다. seq 최대라야 (102, 3,500) 이다.
     */
    @Test
    void 판_2_후보는_PDT_최대가_아니라_seq_최대다() {
        StreamSegmentsFixture.insertV2(jdbc, RESET, 100, 0, at(10_000), at(20_000), 4000, false);
        StreamSegmentsFixture.insert(jdbc, RESET, 101, 4000, at(15_000), 1000, true);
        StreamSegmentsFixture.insertV2(jdbc, RESET, 102, 5000, at(17_000), at(17_000), 4000, true);

        변환됐다(물어본다(RESET, 20_500), 5000 + 3500, 102);
    }

    /**
     * cc AC-M1 반례 — 옛 후보의 역행 신호는 옛 행만으로 센다. 판 2 행 seq 11 의 파일명 시각(20,900)이 채팅 시각보다 이르지만 PDT
     * (21,100)는 늦다. 신호가 판 2 행까지 세면 옛 후보 seq 10 이 「뒤 조각이 과거로 튐」 으로 버려져 영상 없음이 된다.
     */
    @Test
    void 옛_후보의_역행_신호는_옛_행만으로_센다() {
        StreamSegmentsFixture.insert(jdbc, LEGACY_SIGNAL, 10, 0, at(17_000), 4000, false);
        StreamSegmentsFixture.insertV2(jdbc, LEGACY_SIGNAL, 11, 4000, at(20_900), at(21_100), 4000, false);

        변환됐다(물어본다(LEGACY_SIGNAL, 21_000), 4000, 10);
    }

    /**
     * 장 안 건너뜀(경계 표 B7 — 같은 녹화 · PDT 1,200ms 건너뜀)과 이어진 이웃 사이 틈은 뒤 조각 시작이다(F 3항). 공유 표
     * {@code F-pdt-chapter-skip-B7} 과 같은 숫자다. 옛 행 식(조각 끝으로 접음)이면 4,000 이다.
     */
    @Test
    void 판_2_이어진_틈_안_채팅은_뒤_조각_시작이다() {
        StreamSegmentsFixture.insertV2(jdbc, SKIP, 1, 0, at(-100), at(0), 4000, false);
        StreamSegmentsFixture.insertV2(jdbc, SKIP, 2, 5200, at(5100), at(5200), 4000, false);

        변환됐다(물어본다(SKIP, 4600), 5200, 2);
    }

    @Test
    void 판_2_끊긴_틈_안_채팅은_영상_없음이다() {
        StreamSegmentsFixture.insertV2(jdbc, RECONNECT, 1, 0, at(-100), at(0), 4000, false);
        StreamSegmentsFixture.insertV2(jdbc, RECONNECT, 2, 9000, at(8900), at(9000), 4000, true);

        assertThat(물어본다(RECONNECT, 7000).state()).isEqualTo(VideoPosition.State.NO_FOOTAGE);
    }

    /** cx R16-6 — 판 2 마지막 행 끝 뒤는 「아직」 이다(다음 행이 오면 바뀐다). */
    @Test
    void 판_2_다음_행이_없으면_아직이다() {
        StreamSegmentsFixture.insertV2(jdbc, TAIL, 1, 0, at(-100), at(0), 4000, false);

        assertThat(물어본다(TAIL, 4000).state()).isEqualTo(VideoPosition.State.NOT_YET_INDEXED);
        변환됐다(물어본다(TAIL, 3999), 3999, 1);
    }

    /** I15 — 옛 행은 이어진 이웃 사이가 5,001ms 벌어져도 조각 끝으로 접는다(지금 식 그대로 · 판 2 와 다른 자리). */
    @Test
    void 옛_행은_이어진_틈을_조각_끝으로_접는다() {
        StreamSegmentsFixture.insert(jdbc, LEGACY_FOLD, 1, 0, at(0), 4000, false);
        StreamSegmentsFixture.insert(jdbc, LEGACY_FOLD, 2, 4000, at(9001), 4000, false);

        변환됐다(물어본다(LEGACY_FOLD, 9000), 4000, 1);
    }

    /**
     * K1-c 999ms 부분 겹침(경계 표 B8 · 계획 r7 7.5) — 뒤 조각이 앞 조각 끝보다 999ms 이르게 시작한다(PDT · 위치 모두 3,001).
     * 겹침 시작 · 안 · 끝의 채팅 셋이 PDT 축에서 나중 조각으로 풀리고, 낸 위치를 위치 축에서 되돌려도 같은 나중 조각이다. 위치
     * 값만 보면 앞 조각으로 풀어도 같은 숫자라(회차 안 PDT − 위치가 상수) 조각 번호와 되돌린 조각까지 단언한다.
     */
    @ParameterizedTest(name = "겹침 {0} — {1}ms")
    @CsvSource({"시작, 3001", "안, 3500", "끝, 3999"})
    void 겹침_999ms_구간의_채팅은_두_축_모두_나중_조각이다(String where, long t) {
        StreamSegmentsFixture.insertV2(jdbc, OVERLAP, 1, 0, at(-200), at(0), 4000, false);
        StreamSegmentsFixture.insertV2(jdbc, OVERLAP, 2, 3001, at(2801), at(3001), 4000, false);

        VideoPosition position = 물어본다(OVERLAP, t);

        변환됐다(position, t, 2);
        assertThat(위치_축으로_되돌린_조각(OVERLAP, position.positionMs()))
                .as("위치 축으로 되돌려도 같은 조각")
                .isEqualTo(position.segmentSeq());
    }

    /**
     * 옛 후보가 벽시각 역행이면 옛 후보만 버린다(경고는 그대로). 판 2 후보가 있으면 그것으로 변환한다 — 옛 구현처럼 통째로
     * 영상 없음이 아니다.
     */
    @Test
    void 옛_후보가_역행이면_옛_후보만_버리고_판_2_후보를_쓴다() {
        StreamSegmentsFixture.insert(jdbc, INVERTED, 1, 0, at(10_000), 4000, false);
        StreamSegmentsFixture.insert(jdbc, INVERTED, 2, 4000, at(0), 4000, false);
        StreamSegmentsFixture.insertV2(jdbc, INVERTED, 3, 8000, at(7800), at(8000), 4000, false);

        try (LogCaptor logs = new LogCaptor()) {
            변환됐다(물어본다(INVERTED, 8500), 8500, 3);

            assertThat(logs.messages())
                    .filteredOn(line -> line.startsWith("chat.sync.wall_clock_inverted"))
                    .singleElement()
                    .asString()
                    .contains("floorSeq=2");
        }
    }

    /**
     * 판마다 자기 보정값으로 후보를 찾은 뒤 고른다(r13 판별 순서 예). 옛 보정 3,900 · 판 2 차감 150(판 2 보정 3,750) · 판 2 첫 행
     * PDT 4,000 · 메시지 7,800 이면 옛 시각 3,900 은 판 2 첫 행 앞이지만 판 2 시각 4,050 은 그 행 안이다 — 판 2 행이다. 같은
     * 보정값 하나로 찾으면 옛 행 3,900 이 된다.
     */
    @Test
    void 판마다_자기_보정값으로_후보를_찾는다() {
        StreamSegmentsFixture.insert(jdbc, CORRECTED, 1, 0, at(0), 4000, false);
        StreamSegmentsFixture.insertV2(jdbc, CORRECTED, 2, 4000, at(3800), at(4000), 4000, false);
        VideoPositionCalculator corrected =
                new VideoPositionCalculator(ledger, new IngestKeyStore(jdbc), new SyncProperties(3900, Map.of(), 150));

        VideoPosition position = corrected.locate(CORRECTED, "v2-channel", at(7800));

        변환됐다(position, 4050, 2);
        assertThat(position.appliedOffsetMs()).as("고른 후보(판 2)의 보정값").isEqualTo(3750L);
    }
}
