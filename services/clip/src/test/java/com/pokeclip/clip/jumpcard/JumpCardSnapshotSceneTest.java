package com.pokeclip.clip.jumpcard;

import com.pokeclip.clip.jumpcard.CardPositionReader.Scene;
import com.pokeclip.clip.jumpcard.JumpCardSnapshot.FileHead;
import com.pokeclip.clip.segment.RoundRows.Row;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 카드 응답의 두 칸(POK-196 · 설계 r7 8.4 (가)) — {@code fileHead{atMs, startMs, endMs}} 는 카드 ms · 창 두 끝의 장면에서,
 * {@code playerAtMs} 는 카드 ms 장면에서 나온다. 장면이 없으면 웹은 지금 식으로 떨어지므로 칸이 비어야 한다.
 */
class JumpCardSnapshotSceneTest {

    private static final Row A = new Row(1, 0, 4_000, 100_000, 100_000L, null, true);
    private static final Row B = new Row(2, 4_000, 4_000, 99_000, 104_000L, null, true);

    private static JumpCardSnapshot card(long ts, long start, long end) {
        return new JumpCardSnapshot(1L, "s-1", JumpCardSource.HOTKEY, ts, new JumpCardSnapshot.Window(start, end), null,
                null, false, 1L, Instant.EPOCH, null, null, null);
    }

    @Test
    void 세_값의_장면이_다_있으면_두_칸이_선다() {
        JumpCardSnapshot snapshot = card(2_000, 1_000, 3_000).withScenes(Map.of(
                2_000L, new Scene(A, 2_000), 1_000L, new Scene(A, 1_000), 3_000L, new Scene(A, 3_000)));

        assertThat(snapshot.fileHead()).isEqualTo(new FileHead(102_000, 101_000, 103_000));
        assertThat(snapshot.playerAtMs()).isEqualTo(102_000L);
    }

    /** 창 끝이 라이브 머리 뒤면 편집기 칸은 비고(셋을 함께 내는 칸이다) 플레이어 값은 카드 ms 장면에서 선다. */
    @Test
    void 창_끝_장면이_없으면_fileHead만_빈다() {
        JumpCardSnapshot snapshot = card(2_000, 1_000, 30_000).withScenes(Map.of(
                2_000L, new Scene(A, 2_000), 1_000L, new Scene(A, 1_000)));

        assertThat(snapshot.fileHead()).isNull();
        assertThat(snapshot.playerAtMs()).isEqualTo(102_000L);
    }

    @Test
    void 카드_ms_장면이_없으면_두_칸_모두_빈다() {
        JumpCardSnapshot snapshot = card(2_000, 1_000, 3_000).withScenes(Map.of(
                1_000L, new Scene(A, 1_000), 3_000L, new Scene(A, 3_000)));

        assertThat(snapshot.fileHead()).isNull();
        assertThat(snapshot.playerAtMs()).isNull();
    }

    /** 판 NULL 벽시각 역행(E17)으로 끝이 시작보다 이르면 끝을 시작에 묶는다(설계 r7 8.2). */
    @Test
    void 창_끝이_시작보다_이르면_시작에_묶는다() {
        JumpCardSnapshot snapshot = card(3_500, 3_000, 5_000).withScenes(Map.of(
                3_500L, new Scene(A, 3_500), 3_000L, new Scene(A, 3_000), 5_000L, new Scene(B, 1_000)));

        assertThat(snapshot.fileHead()).isEqualTo(new FileHead(103_500, 103_000, 103_000));
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
