package com.pokeclip.render;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 진짜 앱을 띄워 열쇠 검사를 잰다(POK-408). 사진 줄 주소는 {@code ThumbnailProperties}에, 열쇠는 {@code RenderProperties}에
 * 있어 레코드 하나로는 「사진 줄만 켰는데 열쇠가 비었다」를 못 본다. 그 경우가 실제로 부팅에서 막히는지 여기서 본다.
 *
 * <p>값은 명령줄 인자로 준다. 기본 속성으로 주면 {@code application.yml}이 이겨서, 기계에 {@code INTERNAL_API_TOKEN}이
 * 있으면 시험이 그 값을 읽는다.
 */
class RenderWorkerBootTest {

    private static final String NOWHERE = "http://127.0.0.1:1/000000000000/";

    @Test
    void 사진_줄만_보는데_내부_토큰이_비면_부팅을_거부한다() {
        assertThatThrownBy(() -> boot("--pokeclip.render.queue-url=", "--pokeclip.thumbnail.queue-url=" + NOWHERE + "thumb",
                "--pokeclip.render.internal-token="))
                .rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INTERNAL_API_TOKEN");
    }

    @Test
    void 렌더_줄을_보는데_내부_토큰이_비면_부팅을_거부한다() {
        assertThatThrownBy(() -> boot("--pokeclip.render.queue-url=" + NOWHERE + "render", "--pokeclip.thumbnail.queue-url=",
                "--pokeclip.render.internal-token="))
                .rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INTERNAL_API_TOKEN");
    }

    @Test
    void 줄을_안_보면_토큰이_없어도_뜬다() {
        try (ConfigurableApplicationContext context = boot("--pokeclip.render.queue-url=", "--pokeclip.thumbnail.queue-url=",
                "--pokeclip.render.internal-token=")) {
            assertThat(context.isActive()).isTrue();
        }
    }

    private static ConfigurableApplicationContext boot(String... args) {
        return new SpringApplicationBuilder(RenderWorkerApplication.class).web(WebApplicationType.NONE).run(args);
    }
}
