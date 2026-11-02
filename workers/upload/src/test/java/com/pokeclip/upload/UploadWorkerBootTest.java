package com.pokeclip.upload;

import com.pokeclip.upload.work.IdleKeeper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 진짜 앱을 띄워 줄이 없을 때의 모양을 잰다(POK-332). 웹 문이 없어 줄 스레드가 없으면 기동하자마자 종료 코드 0으로 끝나 dev compose가
 * 끝없이 다시 띄웠다. 줄 주소가 비면 경고 한 줄을 남기고 붙들어 두고, 있으면 지금처럼 줄을 본다.
 *
 * <p>값은 명령줄 인자로 준다. 기본 속성으로 주면 {@code application.yml}이 이겨서, 기계에 {@code UPLOAD_QUEUE_URL}이 있으면 시험이
 * 그 값을 읽는다.
 */
@ExtendWith(OutputCaptureExtension.class)
class UploadWorkerBootTest {

    private static final String NOWHERE = "http://127.0.0.1:1/000000000000/upload";

    @Test
    void 줄이_없으면_경고하고_끝나지_않는다(CapturedOutput output) throws Exception {
        Thread holder;
        try (ConfigurableApplicationContext context = boot("--pokeclip.upload.queue-url=", "--pokeclip.upload.internal-token=")) {
            IdleKeeper keeper = context.getBean(IdleKeeper.class);
            holder = keeper.holder();
            assertThat(keeper.isRunning()).isTrue();
            assertThat(holder.isAlive()).isTrue();
            assertThat(holder.isDaemon()).as("데몬이면 JVM이 그대로 내린다").isFalse();
            assertThat(output.getOut()).as("경고 줄이 없으면 운영자가 「떠 있는데 일을 안 한다」를 모른다").contains("WARN").contains("upload.worker_idle");
        }
        holder.join(5_000);
        assertThat(holder.isAlive()).as("닫았는데 붙든 스레드가 남으면 끌 때 프로세스가 안 내린다").isFalse();
    }

    @Test
    void 줄이_있으면_붙들지_않는다() {
        try (ConfigurableApplicationContext context = boot("--pokeclip.upload.queue-url=" + NOWHERE,
                "--pokeclip.upload.internal-token=token")) {
            assertThat(context.getBeansOfType(IdleKeeper.class)).isEmpty();
        }
    }

    private static ConfigurableApplicationContext boot(String... args) {
        return new SpringApplicationBuilder(UploadWorkerApplication.class).web(WebApplicationType.NONE).run(args);
    }
}
