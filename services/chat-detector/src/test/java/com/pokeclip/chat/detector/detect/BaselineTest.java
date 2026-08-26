package com.pokeclip.chat.detector.detect;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BaselineTest {

    @Test
    void 홀수개면_가운데_값이다() {
        assertThat(Baseline.median(new int[]{1, 5, 3})).isEqualTo(3.0);
    }

    @Test
    void 짝수개면_가운데_둘의_평균이다() {
        assertThat(Baseline.median(new int[]{1, 2, 3, 4})).isEqualTo(2.5);
    }

    /**
     * 중앙값을 쓰는 이유가 이것이다. 스파이크 하나가 섞여도 기준선이 안 끌려간다 —
     * 평균이면 이 배열의 기준선이 208.6으로 뛰어 다음 하이라이트를 놓친다
     * (연구노트 4절 「스파이크의 자기 오염」).
     */
    @Test
    void 스파이크_하나에_기준선이_안_끌려간다() {
        assertThat(Baseline.median(new int[]{10, 12, 11, 1000, 10})).isEqualTo(11.0);
    }

    @Test
    void 원본_배열을_안_건드린다() {
        int[] values = {3, 1, 2};
        Baseline.median(values);
        assertThat(values).containsExactly(3, 1, 2);
    }

    @Test
    void 빈_배열은_예외다() {
        assertThatThrownBy(() -> Baseline.median(new int[0]))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
