package com.pokeclip.clip.segment;

import com.pokeclip.clip.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 시간 구간 → 세그먼트 목록 변환의 <b>읽기 절반</b>을 잰다. 여기서 정하는 것은
 * 「어느 조각이 구간에 걸리는가」 하나뿐이고, uploaded 거르기·창 판정은 조립기(태스크 3)의 몫이다.
 *
 * <p><b>겹침 조건이 이 시험의 전부다.</b> 계약-세그먼트인덱스 2절이 2026-08-14에
 * {@code BETWEEN}을 겹침 조건으로 정정했다 — {@code BETWEEN}은 클립 시작을 걸치는 첫 조각을
 * 빠뜨려 클립 머리가 최대 4초 잘린다(1초 단위 시작점 기준 약 75%에서 발생).
 * 그 정정을 되돌리는 회귀를 잡는 것이 여기 갈래들이 존재하는 이유다.
 */
class StreamSegmentReaderTest extends IntegrationTestSupport {

    /** 조각의 벽시계 시각은 이 조회에 안 쓰인다 — NOT NULL을 채우려고 둔 값이다. */
    private static final OffsetDateTime 아무_UTC_시각 =
            OffsetDateTime.of(2026, 8, 24, 0, 0, 0, 0, ZoneOffset.UTC);

    private final StreamSegmentReader reader;
    private final JdbcTemplate jdbc;

    StreamSegmentReaderTest(StreamSegmentReader reader, JdbcTemplate jdbc) {
        this.reader = reader;
        this.jdbc = jdbc;
    }

    @BeforeEach
    void 앞_테스트의_흔적을_지운다() {
        방송과_카드를_비운다(jdbc);
    }

    /**
     * 계약 정정(2026-08-14)이 지키려는 바로 그 행. {@code BETWEEN}으로 되돌리면
     * 이 조각이 통째로 빠져 5,000~8,000ms, 즉 <b>클립 머리 3초</b>가 사라진다
     * (주입 1에서 실제로 빈 목록이 나왔다).
     */
    @Test
    void 클립_시작을_걸치는_조각이_목록에_있다() {
        // 조각 [4000,8000) — 5000ms 시작 클립이 이 조각을 필요로 한다 (계약 정정 2026-08-14)
        insertSegment("s-1", 1, 4000, 4000, "uploaded", false);

        List<StreamSegmentRow> rows = reader.findOverlapping("s-1", 5000, 9000);

        assertThat(rows).extracting(StreamSegmentRow::seq).containsExactly(1L);
    }

    @Test
    void 구간_밖_조각은_안_나온다() {
        insertSegment("s-2", 1, 0, 4000, "uploaded", false);      // [0,4000)     — 구간 앞
        insertSegment("s-2", 2, 4000, 4000, "uploaded", false);   // [4000,8000)  — 시작을 걸침
        insertSegment("s-2", 3, 8000, 4000, "uploaded", false);   // [8000,12000) — 끝을 걸침
        insertSegment("s-2", 4, 12000, 4000, "uploaded", false);  // [12000,16000)— 구간 뒤

        List<StreamSegmentRow> rows = reader.findOverlapping("s-2", 5000, 9000);

        assertThat(rows).extracting(StreamSegmentRow::seq).containsExactly(2L, 3L);
    }

    /**
     * 미만/초과 조건이라 <b>경계에 닿기만 한 조각은 안 나온다.</b> 길이가 0인 겹침은
     * 프레임을 하나도 주지 않으므로 실어 봐야 소용이 없다.
     *
     * <p>아래 반대쪽 갈래와 같은 이유로 겹치는 조각을 함께 심는다 — 「빈 목록」만 재면
     * 조회가 통째로 망가져도 초록이다. 감사 1회차 주입 29({@code AND false})에서 이 갈래만
     * 초록으로 남았다.
     */
    @Test
    void 경계가_정확히_닿기만_하면_안_나온다() {
        insertSegment("s-3", 1, 4000, 4000, "uploaded", false);   // [4000,8000)  — 실제로 겹친다
        insertSegment("s-3", 2, 8000, 4000, "uploaded", false);   // [8000,12000) — 시작이 요청 끝에 닿는다

        List<StreamSegmentRow> rows = reader.findOverlapping("s-3", 5000, 8000);

        assertThat(rows).extracting(StreamSegmentRow::seq).containsExactly(1L);
    }

    /**
     * <b>반대쪽 경계.</b> 위 갈래는 「조각 시작이 요청 끝에 닿는」 쪽만 잰다 — 여기는
     * 「조각 끝이 요청 시작에 닿는」 쪽이다.
     *
     * <p>계획의 갈래 다섯에는 이 자리를 재는 것이 없었다. {@code start_pts_ms + duration_ms > ?}를
     * {@code >=}로 바꾸는 주입이 다섯 모두 초록이었다(주입 3) — 그 회귀는 클립 앞에 붙어 있지도
     * 않은 조각을 목록 맨 앞에 실어 보낸다.
     */
    @Test
    void 조각_끝이_요청_시작에_닿기만_하면_안_나온다() {
        insertSegment("s-6", 1, 0, 4000, "uploaded", false);      // [0,4000)    — 끝이 요청 시작에 닿는다
        insertSegment("s-6", 2, 4000, 4000, "uploaded", false);   // [4000,8000) — 실제로 겹친다

        List<StreamSegmentRow> rows = reader.findOverlapping("s-6", 4000, 8000);

        // 겹치는 조각 하나가 실제로 나오는 것까지 함께 단언한다 — 「빈 목록」만 재면
        // 조회가 통째로 망가져도 이 갈래는 초록이 된다.
        assertThat(rows).extracting(StreamSegmentRow::seq).containsExactly(2L);
    }

    /**
     * 조립기가 「앞 조각 끝」·「중간에서 끊기」를 판정하려면 순서가 보장돼야 한다.
     * INSERT 순서를 일부러 뒤집어, 표에 들어간 순서가 아니라 {@code ORDER BY seq}가
     * 순서를 정한다는 것을 잰다.
     */
    @Test
    void seq_순서로_나온다() {
        insertSegment("s-4", 3, 12000, 4000, "uploaded", false);
        insertSegment("s-4", 1, 4000, 4000, "uploaded", false);
        insertSegment("s-4", 2, 8000, 4000, "uploaded", false);

        List<StreamSegmentRow> rows = reader.findOverlapping("s-4", 5000, 16000);

        assertThat(rows).extracting(StreamSegmentRow::seq).containsExactly(1L, 2L, 3L);
    }

    /**
     * 상태로 거르는 것은 <b>여기가 아니다.</b> 조립기가 「중간 pending에서 끊는다」를
     * 판정하려면 pending 조각이 목록에 실려 와야 한다 — 여기서 걸러 버리면 조립기는
     * 구멍을 못 보고 끊기지 않은 창을 만든다.
     */
    @Test
    void 상태와_무관하게_전부_나온다() {
        insertSegment("s-5", 1, 4000, 4000, "uploaded", false);
        insertSegment("s-5", 2, 8000, 4000, "pending", false);
        insertSegment("s-5", 3, 12000, 4000, "failed", false);

        List<StreamSegmentRow> rows = reader.findOverlapping("s-5", 5000, 16000);

        assertThat(rows).extracting(StreamSegmentRow::uploadState)
                .containsExactly("uploaded", "pending", "failed");
    }

    /**
     * 방송을 가르는 것은 이 조회의 <b>경계</b>다 — 남의 방송 조각이 섞여 나오면 그대로
     * 편집 창에 실리고, 그 위에서 만든 클립이 남의 영상을 담는다.
     *
     * <p>계획의 갈래 다섯은 이것도 못 잡았다. 갈래마다 방송을 하나만 심고 그 사이에 표를
     * 비우니, {@code stream_id = ?}를 <b>통째로 지워도</b> 여섯이 다 초록이었다(주입 5).
     * 같은 구간에 걸치는 남의 조각이 한 줄이라도 있어야 그 조건이 재어진다.
     */
    @Test
    void 다른_방송의_조각은_안_나온다() {
        insertSegment("s-7", 1, 4000, 4000, "uploaded", false);
        insertSegment("s-7-남의방송", 2, 4000, 4000, "uploaded", false);

        List<StreamSegmentRow> rows = reader.findOverlapping("s-7", 5000, 9000);

        assertThat(rows).extracting(StreamSegmentRow::seq).containsExactly(1L);
    }

    /**
     * <b>DB 칸 → record 칸의 매핑 자체를 잰다.</b> 다른 갈래는 전부 {@code seq}·{@code uploadState}만
     * 꺼내 보고, 조립기 시험은 {@code StreamSegmentRow}를 손으로 만들어 이 경로를 아예 안 지난다 —
     * 그래서 감사 1회차에서 {@code start_pts_ms}·{@code duration_ms}·{@code s3_key}·
     * {@code is_discontinuity} 넷을 각각 상수로 고정하는 주입(23~26)이 <b>일곱 갈래 전부 초록</b>이었다.
     *
     * <p>record 통째로 단언하는 이유는 <b>여섯 칸이 한 번에 잠기기 때문</b>이다. 값은 서로 겹치지 않게
     * 골랐다 — 어느 칸을 상수로 고정하거나 두 칸을 맞바꿔도 우연히 맞을 수 없다.
     *
     * <p>{@code discontinuity}를 <b>두 방향 다</b> 심는 것도 그래서다. 나머지 갈래가 전부
     * {@code false}만 심으므로, {@code true}인 행이 없으면 {@code false} 고정이 안 잡히고
     * {@code true}인 행만 있으면 {@code true} 고정이 안 잡힌다. 이 칸은 판정에 안 쓰이고
     * <b>값이 응답까지 보존되는 것 자체가 요구사항</b>이라(PRD 성공 기준 6번) 여기서만 잠긴다.
     */
    @Test
    void 읽어_온_행에_여섯_칸이_그대로_실린다() {
        insertSegment("s-8", 1, 7000, 3000, "uploaded", false);   // [7000,10000)
        insertSegment("s-8", 2, 10000, 2000, "pending", true);    // [10000,12000)

        List<StreamSegmentRow> rows = reader.findOverlapping("s-8", 8000, 11000);

        assertThat(rows).containsExactly(
                new StreamSegmentRow(1L, 7000, 3000, "seg/1", "uploaded", false),
                new StreamSegmentRow(2L, 10000, 2000, "seg/2", "pending", true));
    }

    private void insertSegment(String streamId, long seq, long startPtsMs, int durationMs,
                               String state, boolean discontinuity) {
        jdbc.update("""
                        INSERT INTO stream_segments
                            (stream_id, seq, start_pts_ms, start_wall_utc, duration_ms,
                             s3_key, upload_state, is_discontinuity)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                streamId, seq, startPtsMs, 아무_UTC_시각, durationMs,
                "seg/" + seq, state, discontinuity);
    }
}
