package com.pokeclip.render;

import com.pokeclip.render.work.IdleKeeper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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
@ExtendWith(OutputCaptureExtension.class)
class RenderWorkerBootTest {

    private static final String NOWHERE = "http://127.0.0.1:1/000000000000/";

    @Test
    void 사진_줄만_보는데_내부_토큰이_비면_부팅을_거부한다() {
        assertThatThrownBy(() -> bootAndClose("--pokeclip.render.queue-url=", "--pokeclip.thumbnail.queue-url=" + NOWHERE + "thumb",
                "--pokeclip.render.internal-token="))
                .rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INTERNAL_API_TOKEN");
    }

    @Test
    void 렌더_줄을_보는데_내부_토큰이_비면_부팅을_거부한다() {
        assertThatThrownBy(() -> bootAndClose("--pokeclip.render.queue-url=" + NOWHERE + "render", "--pokeclip.thumbnail.queue-url=",
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

    /**
     * 줄이 하나도 없으면 경고 한 줄을 남기고 끝나지 않는다(POK-332). 웹 문이 없어 이것이 없으면 기동하자마자 종료 코드 0으로 끝나
     * dev compose가 끝없이 다시 띄웠다. 붙드는 스레드가 데몬이 아니어야 JVM이 안 내린다. 닫으면 놓아준다.
     */
    @Test
    void 줄이_하나도_없으면_경고하고_끝나지_않는다(CapturedOutput output) throws Exception {
        Thread holder;
        try (ConfigurableApplicationContext context = boot("--pokeclip.render.queue-url=", "--pokeclip.thumbnail.queue-url=",
                "--pokeclip.render.internal-token=")) {
            IdleKeeper keeper = context.getBean(IdleKeeper.class);
            holder = keeper.holder();
            assertThat(keeper.isRunning()).isTrue();
            assertThat(holder.isAlive()).isTrue();
            assertThat(holder.isDaemon()).as("데몬이면 JVM이 그대로 내린다").isFalse();
            assertThat(output.getOut()).as("경고 줄이 없으면 운영자가 「떠 있는데 일을 안 한다」를 모른다").contains("WARN").contains("render.worker_idle");
        }
        holder.join(5_000);
        assertThat(holder.isAlive()).as("닫았는데 붙든 스레드가 남으면 끌 때 프로세스가 안 내린다").isFalse();
    }

    @Test
    void 줄이_하나라도_있으면_붙들지_않는다() {
        try (ConfigurableApplicationContext context = boot("--pokeclip.render.queue-url=", "--pokeclip.thumbnail.queue-url=" + NOWHERE + "thumb",
                "--pokeclip.render.internal-token=token")) {
            assertThat(context.getBeansOfType(IdleKeeper.class)).isEmpty();
        }
    }

    /** 거부해야 할 경우에 쓴다. 검사가 깨져 떠 버리면 닫아서, 가짜 주소를 두드리는 일꾼 스레드가 뒤 시험까지 남지 않게 한다. */
    private static void bootAndClose(String... args) {
        boot(args).close();
    }

    private static ConfigurableApplicationContext boot(String... args) {
        return new SpringApplicationBuilder(RenderWorkerApplication.class).web(WebApplicationType.NONE).run(args);
    }
}
