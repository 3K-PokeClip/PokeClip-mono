package com.pokeclip.clip.jumpcard.stream;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 설정값 하한. <b>0 이하가 통과하면 설정 한 줄로 연결이 불사가 된다</b> —
 * {@code SseEmitter}는 서블릿 규약상 {@code timeout <= 0}을 「시한 없음」으로 읽는다.
 * 만료 토큰으로 여는 경로(컨트롤러 가드)와 <b>뿌리가 같은 두 번째 입구</b>다(인가 2차 감사).
 */
class StreamPropertiesTest {

    @Test
    void timeout이_0이면_기본값이_들어간다() {
        StreamProperties properties = new StreamProperties(
                Duration.ofSeconds(20), Duration.ZERO, 4, 1000, 4, 50, 500);

        assertThat(properties.timeout())
                .as("0이 그대로 가면 SseEmitter가 「시한 없음」이 되어 연결이 안 죽는다")
                .isEqualTo(Duration.ofHours(4));
    }

    @Test
    void timeout이_음수여도_기본값이_들어간다() {
        StreamProperties properties = new StreamProperties(
                Duration.ofSeconds(20), Duration.ofSeconds(-1), 4, 1000, 4, 50, 500);

        assertThat(properties.timeout()).isEqualTo(Duration.ofHours(4));
    }

    /** heartbeat=0은 스케줄러가 기동에서 죽는다. 시끄럽게 실패하지만 막는 것이 대칭이다. */
    @Test
    void heartbeat가_0이하면_기본값이_들어간다() {
        assertThat(new StreamProperties(Duration.ZERO, Duration.ofHours(4), 4, 1000, 4, 50, 500).heartbeat())
                .isEqualTo(Duration.ofSeconds(20));
        assertThat(new StreamProperties(Duration.ofSeconds(-5), Duration.ofHours(4), 4, 1000, 4, 50, 500).heartbeat())
                .isEqualTo(Duration.ofSeconds(20));
    }

    /** 양성 대조. 하한이 지나치게 넓으면 멀쩡한 설정까지 기본값으로 덮는다. */
    @Test
    void 멀쩡한_값은_그대로_쓴다() {
        StreamProperties properties = new StreamProperties(
                Duration.ofSeconds(5), Duration.ofMinutes(30), 8, 500, 2, 20, 200);

        assertThat(properties.heartbeat()).isEqualTo(Duration.ofSeconds(5));
        assertThat(properties.timeout()).isEqualTo(Duration.ofMinutes(30));
        assertThat(properties.stripes()).isEqualTo(8);
        assertThat(properties.maxPerUser()).isEqualTo(2);
    }
}
