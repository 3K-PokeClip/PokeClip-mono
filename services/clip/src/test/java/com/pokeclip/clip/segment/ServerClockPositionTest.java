package com.pokeclip.clip.segment;

import com.pokeclip.clip.segment.RoundRows.Row;
import com.pokeclip.clip.segment.ServerClockPosition.Position;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 서버 시계 T · 지난 방송 재생 위치 P → 카드 위치(POK-196 · 설계 r7 8.2). 회차 행 열만 받는 순수 함수라 DB 없이 잰다.
 * 숫자는 epoch ms 대신 작은 값을 쓴다 — 식은 차만 본다.
 */
class ServerClockPositionTest {

    /** 판 NULL 행: 축 = 벽시각. PDT 는 래칫으로 앞설 수 있다. */
    private static Row legacy(long seq, long pts, long wall, Long pdt, int duration) {
        return new Row(seq, pts, duration, wall, pdt, null, true);
    }

    /** 회차 귀속 행 열(지난 방송 floor 의 목록 시각 = PDT). */
    private static final RoundRows.Round MEMBERS = new RoundRows.Members("K", List.of("S"));

    /** 머리 세션 행이 없는 옛 방송의 시간창(목록 시각 = 기준점과 같은 {@code COALESCE}). */
    private static final RoundRows.Round WINDOW = new RoundRows.TimeWindow("K", Instant.EPOCH, null);

    /** 판 2 행: 축 = PDT. */
    private static Row v2(long seq, long pts, long wall, long pdt, int duration) {
        return new Row(seq, pts, duration, wall, pdt, 2, true);
    }

    // ── 서버 시계 → 위치(섞인 floor · 계약 6-6-9) ─────────────────

    @Test
    void 판_NULL_행은_벽시각으로_고르고_파일_머리_기준_거리를_위치에_더한다() {
        List<Row> rows = List.of(legacy(1, 0, 100_000, 100_000L, 4_000), legacy(2, 4_000, 104_900, 104_900L, 4_000));

        assertThat(ServerClockPosition.fromServerClock(rows, 105_400))
                .contains(new Position(4_500, 2, "wall", false, false));
    }

    /** 판 2 행은 PDT 로 고른다 — 벽시각이 늦어도(장 잔차) 그 행이다. */
    @Test
    void 판_2_행은_PDT로_고른다() {
        List<Row> rows = List.of(v2(1, 0, 100_000, 100_000, 4_000), v2(2, 4_000, 104_700, 104_000, 4_000));

        assertThat(ServerClockPosition.fromServerClock(rows, 104_300))
                .contains(new Position(4_300, 2, "pdt", false, false));
    }

    /** 두 판 후보가 다 있으면 seq 큰 쪽(쓰기 스위치 경계 — 계약 6-6-9). */
    @Test
    void 판이_섞이면_판마다_고른_후보_가운데_seq_큰_쪽이다() {
        List<Row> rows = List.of(legacy(1, 0, 100_000, 100_000L, 4_000), v2(2, 4_000, 104_500, 104_000, 4_000));

        assertThat(ServerClockPosition.fromServerClock(rows, 104_200))
                .contains(new Position(4_200, 2, "pdt", false, false));
        assertThat(ServerClockPosition.fromServerClock(rows, 103_900))
                .contains(new Position(3_900, 1, "wall", false, false));
    }

    /** B8 부분 겹침(K1-c 999ms) — 겹친 구간은 나중 조각이다. */
    @Test
    void PDT_부분_겹침_999ms_구간은_나중_조각으로_푼다() {
        List<Row> rows = List.of(v2(1, 0, 100_000, 100_000, 4_000), v2(2, 3_001, 103_001, 103_001, 4_000));

        assertThat(ServerClockPosition.fromServerClock(rows, 103_500))
                .contains(new Position(3_500, 2, "pdt", false, false));
    }

    @Test
    void 마지막_귀속_행_끝을_넘으면_라이브_머리로_늘린다() {
        List<Row> rows = List.of(legacy(1, 0, 100_000, 100_000L, 4_000));

        assertThat(ServerClockPosition.fromServerClock(rows, 110_000))
                .contains(new Position(10_000, 1, "wall", true, false));
    }

    /** 다음 귀속 행이 있으면 채팅 옛 행 식처럼 조각 길이로 접는다(설계 r7 8.2 · P31 (f) — 204000). */
    @Test
    void 다음_귀속_행이_있으면_조각_끝으로_접는다() {
        List<Row> rows = List.of(legacy(10, 200_000, 100_000, 100_000L, 4_000),
                new Row(11, 210_000, 4_000, 110_000, 110_000L, null, true));

        assertThat(ServerClockPosition.fromServerClock(rows, 106_000))
                .contains(new Position(204_000, 10, "wall", false, true));
    }

    /** 첫 귀속 행 앞이면 그 행 시작 — 스트림 전체 위치 0 이 아니다. */
    @Test
    void 첫_귀속_행_앞이면_첫_행_시작으로_접는다() {
        List<Row> rows = List.of(legacy(10, 200_000, 100_000, 100_000L, 4_000));

        assertThat(ServerClockPosition.fromServerClock(rows, 99_000))
                .contains(new Position(200_000, 10, "wall", false, true));
    }

    @Test
    void 행이_없으면_위치가_없다() {
        assertThat(ServerClockPosition.fromServerClock(List.of(), 1_000)).isEmpty();
    }

    // ── 지난 방송 재생 위치 → 위치(목록 PDT floor) ──────────────

    /** 출시 이미지 식 판 NULL — 위치 보폭(4000)과 PDT 보폭(4900)이 다르다. 행마다 풀어야 같은 조각이다(P31 (d)). */
    @Test
    void 재생_위치는_목록_PDT_로_고른_행의_위치에_거리를_더한다() {
        List<Row> rows = List.of(legacy(1, 0, 100_000, 100_000L, 4_000), legacy(2, 4_000, 104_900, 104_900L, 4_000));

        assertThat(ServerClockPosition.fromPlayback(MEMBERS, rows, 105_400))
                .contains(new Position(4_500, 2, "pdt", false, false));
    }

    @Test
    void 재생_위치가_틈_안이면_뒤_조각_시작이다() {
        List<Row> rows = List.of(legacy(10, 200_000, 100_000, 100_000L, 4_000),
                legacy(11, 210_000, 110_000, 110_000L, 4_000));

        assertThat(ServerClockPosition.fromPlayback(MEMBERS, rows, 106_000))
                .contains(new Position(210_000, 11, "pdt", false, true));
    }

    @Test
    void 재생_위치가_마지막_행_끝_뒤면_마지막_화면_쪽_매체_자리다() {
        List<Row> rows = List.of(legacy(10, 200_000, 100_000, 100_000L, 4_000));

        assertThat(ServerClockPosition.fromPlayback(MEMBERS, rows, 120_000))
                .contains(new Position(203_999, 10, "pdt", false, true));
    }

    @Test
    void 재생_위치가_첫_행_앞이면_첫_행_시작이다() {
        List<Row> rows = List.of(legacy(10, 200_000, 100_000, 100_000L, 4_000));

        assertThat(ServerClockPosition.fromPlayback(MEMBERS, rows, 50_000))
                .contains(new Position(200_000, 10, "pdt", false, true));
    }

    /** 옛 방송 시간창의 PDT 가 빈 행(세션 장부 전의 옛 행)은 기준점과 같은 식으로 벽시각을 목록 시각으로 본다. */
    @Test
    void 옛_방송_시간창의_PDT가_빈_행은_벽시각을_목록_시각으로_본다() {
        List<Row> rows = List.of(legacy(1, 0, 100_000, null, 4_000));

        assertThat(ServerClockPosition.fromPlayback(WINDOW, rows, 101_000))
                .contains(new Position(1_000, 1, "pdt", false, false));
    }

    /**
     * 회차 귀속 행은 목록 PDT 로만 floor 한다. PDT 가 빈 귀속 행(DB 제약은 아님)을 벽시각으로 끼우면 목록 시각 축이 섞여 앞 행을
     * 가로챈다 — 벽시각 104,000 은 PDT 200,000 인 앞 행보다 seq 가 커서 P = 201,000 의 floor 가 된다.
     */
    @Test
    void 회차_귀속_행의_빈_PDT는_지난_방송_floor에_끼지_않는다() {
        List<Row> rows = List.of(legacy(1, 0, 100_000, 200_000L, 4_000), legacy(2, 4_000, 104_000, null, 4_000));

        assertThat(ServerClockPosition.fromPlayback(MEMBERS, rows, 201_000))
                .contains(new Position(1_000, 1, "pdt", false, false));
    }

    @Test
    void 회차_귀속_행에_PDT가_하나도_없으면_위치가_없다() {
        List<Row> rows = List.of(legacy(1, 0, 100_000, null, 4_000));

        assertThat(ServerClockPosition.fromPlayback(MEMBERS, rows, 101_000)).isEmpty();
    }

    @Test
    void 재생_위치도_행이_없으면_없다() {
        assertThat(ServerClockPosition.fromPlayback(MEMBERS, List.of(), 1_000)).isEqualTo(Optional.empty());
    }
}
