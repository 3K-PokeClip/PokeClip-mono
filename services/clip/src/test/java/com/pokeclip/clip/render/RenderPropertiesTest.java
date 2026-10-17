package com.pokeclip.clip.render;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 완성 영상 주소 수명(POK-247)은 S3가 받는 범위(0초 초과 7일 이하)여야 부팅한다. 범위 밖이면 부팅은 살고 주소를 줄 때마다
 * 500이 난다(PR #197 codex). 주문줄이 꺼져 있어도 잰다: 켜는 날 처음 드러나면 늦다.
 */
class RenderPropertiesTest {

    @Test
    void 수명이_0_이하거나_7일을_넘으면_부팅을_거부한다() {
        for (Duration bad : new Duration[]{Duration.ZERO, Duration.ofSeconds(-1), Duration.ofDays(7).plusSeconds(1)}) {
            assertThatThrownBy(() -> 꺼진_설정(bad).validateWhenEnabled())
                    .as(bad.toString())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("file-url-ttl");
        }
    }

    @Test
    void 경계값_1초와_7일은_받는다() {
        assertThatCode(() -> 꺼진_설정(Duration.ofSeconds(1)).validateWhenEnabled()).doesNotThrowAnyException();
        assertThatCode(() -> 꺼진_설정(Duration.ofDays(7)).validateWhenEnabled()).doesNotThrowAnyException();
    }

    private static RenderProperties 꺼진_설정(Duration fileUrlTtl) {
        return new RenderProperties(false, null, null, "ap-northeast-2", null, null, null,
                Duration.ofSeconds(30), Duration.ofMinutes(1), 3, 200_000, fileUrlTtl);
    }
}
