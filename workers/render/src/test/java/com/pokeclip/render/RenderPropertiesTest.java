package com.pokeclip.render;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 줄을 보는데 내부 토큰이 비면 부팅을 거부한다(POK-408, upload 일꾼과 같은 검사). 비어도 뜨면 clip 보고가 전부 401이라
 * 주문이 다 실패하는데 일꾼은 멀쩡해 보인다(arm64 이미지에서 빈 토큰으로 떠 있는 것을 확인했다).
 */
class RenderPropertiesTest {

    @Test
    void 줄을_보는데_내부_토큰이_비면_거부한다() {
        for (String token : new String[]{null, "", "  "}) {
            assertThatThrownBy(() -> 설정("http://queue", token))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("INTERNAL_API_TOKEN");
        }
    }

    @Test
    void 줄을_안_보면_토큰이_없어도_뜬다_보면_토큰이_있어야_뜬다() {
        assertThatCode(() -> 설정("", null)).doesNotThrowAnyException();
        assertThatCode(() -> 설정(null, null)).doesNotThrowAnyException();
        assertThatCode(() -> 설정("http://queue", "t")).doesNotThrowAnyException();
    }

    private static RenderProperties 설정(String queueUrl, String token) {
        return new RenderProperties(queueUrl, null, null, false, "ap-northeast-2", "http://clip", token,
                Path.of("/tmp"), "ffmpeg", "ffprobe", Duration.ofMinutes(15), Duration.ofSeconds(600),
                Duration.ofSeconds(20), List.of(), null);
    }
}
