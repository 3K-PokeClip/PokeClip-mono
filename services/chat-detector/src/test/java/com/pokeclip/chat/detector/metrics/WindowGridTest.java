package com.pokeclip.chat.detector.metrics;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WindowGridTest {

    @Test
    void 눈금으로_내림한다() {
        assertThat(WindowGrid.floorTo(1_000_007_123L, 5_000L)).isEqualTo(1_000_005_000L);
    }

    /** 눈금 위에 정확히 놓인 값은 그대로다. 이게 어긋나면 같은 창이 두 눈금으로 갈린다. */
    @Test
    void 눈금_위의_값은_그대로다() {
        assertThat(WindowGrid.floorTo(1_000_005_000L, 5_000L)).isEqualTo(1_000_005_000L);
    }

    /**
     * 창 시작이 같아야 clip의 중복 방어가 작동한다. 1ms 차이로 부른 두 번이 같은 눈금을
     * 내는지를 잰다 — 이 성질이 깨지면 밀며 보는 것과 같아진다.
     */
    @Test
    void 같은_창_안의_두_시각은_같은_눈금을_낸다() {
        assertThat(WindowGrid.floorTo(1_000_005_001L, 5_000L))
                .isEqualTo(WindowGrid.floorTo(1_000_009_999L, 5_000L));
    }

    @Test
    void 닫힌_창만_돌려준다() {
        // [10000, 15000) 은 15000 에 닫힌다. to=15000 이면 그 창까지 닫혔다.
        assertThat(WindowGrid.closedWindowsBetween(10_000L, 15_000L, 5_000L))
                .containsExactly(10_000L);
    }

    @Test
    void 아직_안_닫힌_창은_빼고_돌려준다() {
        // [15000, 20000) 은 to=19999 시점에 아직 안 닫혔다.
        assertThat(WindowGrid.closedWindowsBetween(10_000L, 19_999L, 5_000L))
                .containsExactly(10_000L);
    }

    @Test
    void 범위가_뒤집혔으면_빈_목록이다() {
        assertThat(WindowGrid.closedWindowsBetween(20_000L, 10_000L, 5_000L)).isEmpty();
    }
}
