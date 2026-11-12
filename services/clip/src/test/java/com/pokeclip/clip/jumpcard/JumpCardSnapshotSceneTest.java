package com.pokeclip.clip.jumpcard;

import com.pokeclip.clip.jumpcard.CardPositionReader.Scene;
import com.pokeclip.clip.jumpcard.JumpCardSnapshot.PlayerWindow;
import com.pokeclip.clip.segment.RoundRows.Row;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 카드 응답의 두 칸(POK-196 · 설계 r7 8.4 (가) · 계획 델타 7.4.1 D2) — {@code playerAtMs} 는 카드 ms 장면에서,
 * {@code playerWindow{startMs, endMs}} 는 창 두 끝의 장면에서 나온다(둘 다 목록 PDT 축). 장면이 없으면 웹은 지금 식으로
 * 떨어지므로 칸이 비어야 한다.
 */
class JumpCardSnapshotSceneTest {

    private static final Row A = new Row(1, 0, 4_000, 100_000, 100_000L, null, true, null);
    private static final Row B = new Row(2, 4_000, 4_000, 104_000, 104_000L, null, true, null);

    private static JumpCardSnapshot card(long ts, long start, long end) {
        return new JumpCardSnapshot(1L, "s-1", JumpCardSource.HOTKEY, ts, new JumpCardSnapshot.Window(start, end), null,
                null, false, 1L, Instant.EPOCH, null, null, null);
    }

    @Test
    void 세_값의_장면이_다_있으면_두_칸이_선다() {
        JumpCardSnapshot snapshot = card(2_000, 1_000, 3_000).withScenes(Map.of(
                2_000L, new Scene(A, 2_000), 1_000L, new Scene(A, 1_000), 3_000L, new Scene(A, 3_000)));

        assertThat(snapshot.playerWindow()).isEqualTo(new PlayerWindow(101_000, 103_000));
        assertThat(snapshot.playerAtMs()).isEqualTo(102_000L);
    }

    /**
     * P37(계획 델타 7절) — 판 NULL 행은 회차 안 {@code PDT − start_pts} 가 상수가 아니라 창 끝은 끝마다 그 행에서 풀어야 한다.
     * 카드 값 {@code playerAtMs} 에 창 ms 차를 더해 늘리면 끝이 109,000 으로 6초 짧다.
     */
    @Test
    void P37_창_두_끝은_끝마다_장면_행의_PDT로_푼다() {
        List<Row> rows = List.of(new Row(1, 0, 4_000, 100_000, 100_000L, null, true, null),
                new Row(2, 4_000, 4_000, 107_000, 107_000L, null, true, null),
                new Row(3, 8_000, 4_000, 114_000, 114_000L, null, true, null));
        JumpCardSnapshot card = card(1_500, 1_000, 9_000);

        JumpCardSnapshot snapshot = card.withScenes(CardPositionReader.scenesOf(rows, card.sceneValues()));

        assertThat(snapshot.playerAtMs()).isEqualTo(101_500L);
        assertThat(snapshot.playerWindow()).isEqualTo(new PlayerWindow(101_000, 115_000));
    }

    /** 창 끝이 라이브 머리 뒤면 창 칸은 비고(두 끝을 함께 내는 칸이다) 플레이어 값은 카드 ms 장면에서 선다. */
    @Test
    void 창_끝_장면이_없으면_playerWindow만_빈다() {
        JumpCardSnapshot snapshot = card(2_000, 1_000, 30_000).withScenes(Map.of(
                2_000L, new Scene(A, 2_000), 1_000L, new Scene(A, 1_000)));

        assertThat(snapshot.playerWindow()).isNull();
        assertThat(snapshot.playerAtMs()).isEqualTo(102_000L);
    }

    @Test
    void 카드_ms_장면이_없으면_두_칸_모두_빈다() {
        JumpCardSnapshot snapshot = card(2_000, 1_000, 3_000).withScenes(Map.of(
                1_000L, new Scene(A, 1_000), 3_000L, new Scene(A, 3_000)));

        assertThat(snapshot.playerWindow()).isNull();
        assertThat(snapshot.playerAtMs()).isNull();
    }

    /** 두 끝 장면 가운데 하나라도 고른 행의 PDT 가 비면 창 칸이 빈다 — 반쪽을 싣지 않는다. */
    @Test
    void 창_끝_장면_행의_PDT가_비면_playerWindow만_빈다() {
        Row noPdt = new Row(2, 4_000, 4_000, 104_000, null, null, true, null);

        JumpCardSnapshot endEmpty = card(2_000, 1_000, 5_000).withScenes(Map.of(
                2_000L, new Scene(A, 2_000), 1_000L, new Scene(A, 1_000), 5_000L, new Scene(noPdt, 1_000)));
        JumpCardSnapshot startEmpty = card(6_000, 5_000, 6_500).withScenes(Map.of(
                6_000L, new Scene(B, 2_000), 5_000L, new Scene(noPdt, 1_000), 6_500L, new Scene(B, 2_500)));

        assertThat(endEmpty.playerWindow()).as("끝 행 PDT 없음").isNull();
        assertThat(endEmpty.playerAtMs()).isEqualTo(102_000L);
        assertThat(startEmpty.playerWindow()).as("시작 행 PDT 없음").isNull();
        assertThat(startEmpty.playerAtMs()).isEqualTo(106_000L);
    }

    /** 끝이 시작보다 이른 PDT 로 풀리면(판 NULL 시각 역행 · 설계 r7 8.2) 끝을 시작에 묶는다. */
    @Test
    void 창_끝이_시작보다_이르면_시작에_묶는다() {
        Row earlier = new Row(2, 4_000, 4_000, 99_000, 99_000L, null, true, null);

        JumpCardSnapshot snapshot = card(3_500, 3_000, 5_000).withScenes(Map.of(
                3_500L, new Scene(A, 3_500), 3_000L, new Scene(A, 3_000), 5_000L, new Scene(earlier, 1_000)));

        assertThat(snapshot.playerWindow()).isEqualTo(new PlayerWindow(103_000, 103_000));
    }

    @Test
    void 장면을_입혀도_다른_칸은_그대로다() {
        JumpCardSnapshot before = card(2_000, 1_000, 3_000).withThumbnailUrl("https://img");

        JumpCardSnapshot after = before.withScenes(Map.of(2_000L, new Scene(A, 2_000)));

        assertThat(after.thumbnailUrl()).isEqualTo("https://img");
        assertThat(after.window()).isEqualTo(before.window());
        assertThat(after.streamTimestampMs()).isEqualTo(2_000);
    }
}
