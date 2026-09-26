package com.pokeclip.upload;

import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 줄을 보는데 내부 토큰이 비면 부팅을 거부한다(PR #199 codex 3판). 비어도 뜨면 clip이 401을 주고, 쪽지가 세 번 돈 뒤 실패 큐 정리기가
 * 주문을 실패로 닫는다. 설정 실수 하나가 사용자 업로드를 소진한다.
 */
class UploadPropertiesTest {

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
        assertThatCode(() -> 설정("http://queue", "t")).doesNotThrowAnyException();
    }

    private static UploadProperties 설정(String queueUrl, String token) {
        return new UploadProperties(queueUrl, null, null, false, "ap-northeast-2", "http://clip", "http://auth", token,
                "http://yt", Path.of("/tmp"), DataSize.ofMegabytes(8), Duration.ofSeconds(900), Duration.ofSeconds(20),
                List.of());
    }
}
