package com.pokeclip.clip.purge;

import com.pokeclip.clip.render.RenderProperties;
import com.pokeclip.clip.upload.UploadProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 창고 지우기 빈이 언제 생기나(POK-291 로컬 리뷰 1라운드). 렌더는 끄고 업로드만 켠 배포도 사용자가 올린 썸네일 그림을 출력 창고에
 * 둔다: 그때 빈이 없으면 탈퇴 정리가 그 접두사를 명부에서 빼기만 하고 「완료」로 닫아 그림이 영구히 남는다.
 */
class PurgeConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Properties.class, PurgeConfiguration.class)
            .withPropertyValues(
                    "pokeclip.render.region=ap-northeast-2",
                    "pokeclip.render.outbox-retry-interval=PT30S",
                    "pokeclip.render.reconcile-interval=PT1M",
                    "pokeclip.render.max-attempts=3",
                    "pokeclip.render.max-message-bytes=204800",
                    "pokeclip.render.file-url-ttl=PT60M",
                    "pokeclip.upload.region=ap-northeast-2",
                    "pokeclip.upload.outbox-retry-interval=PT30S",
                    "pokeclip.upload.reconcile-interval=PT1M",
                    "pokeclip.purge.interval=PT15S");

    @Configuration
    @EnableConfigurationProperties({RenderProperties.class, UploadProperties.class})
    static class Properties {
    }

    @Test
    void 렌더가_꺼져도_업로드가_켜졌으면_출력_창고를_지우고_조각은_모른다() {
        runner.withPropertyValues("pokeclip.render.enabled=false", "pokeclip.render.output-bucket=clips",
                        "pokeclip.upload.enabled=true", "pokeclip.upload.queue-url=q", "pokeclip.upload.dlq-url=d")
                .run(context -> {
                    assertThat(context).hasSingleBean(PurgeStorage.class);
                    assertThat(context.getBean(PurgeStorage.class).deletesSegments()).isFalse();
                });
    }

    @Test
    void 렌더가_켜졌으면_조각_창고도_지운다() {
        runner.withPropertyValues("pokeclip.render.enabled=true", "pokeclip.render.queue-url=q",
                        "pokeclip.render.dlq-url=d", "pokeclip.render.output-bucket=clips",
                        "pokeclip.render.segment-bucket=segments")
                .run(context -> {
                    assertThat(context).hasSingleBean(PurgeStorage.class);
                    assertThat(context.getBean(PurgeStorage.class).deletesSegments()).isTrue();
                });
    }

    @Test
    void 둘_다_꺼졌으면_빈이_없다() {
        runner.withPropertyValues("pokeclip.render.enabled=false", "pokeclip.upload.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(PurgeStorage.class));
    }
}
