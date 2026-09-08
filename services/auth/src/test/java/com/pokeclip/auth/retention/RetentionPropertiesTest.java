package com.pokeclip.auth.retention;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 값이 틀리면 부팅에서 죽어야 한다. 0이나 음수가 조용히 바인딩되면 —
 * 주기 0은 스케줄러가 쉼 없이 돌고, 보관 0은 방금 회수한 토큰을 바로 지워 재사용 감지가 죽고,
 * 상한 0은 아무것도 안 지우면서 초록이다.
 */
class RetentionPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Enable.class)
            .withPropertyValues(
                    "pokeclip.retention.enabled=true",
                    "pokeclip.retention.interval=PT10M",
                    "pokeclip.retention.batch-limit=1000",
                    "pokeclip.retention.refresh-tokens-keep-for=P14D",
                    "pokeclip.retention.pairing-attempts-keep-for=PT1H",
                    "pokeclip.retention.pairing-codes-keep-for=PT1H");

    @Test
    void 정상값이_그대로_바인딩된다() {
        runner.run(c -> {
            assertThat(c).hasNotFailed();
            RetentionProperties p = c.getBean(RetentionProperties.class);
            assertThat(p.enabled()).isTrue();
            assertThat(p.interval()).isEqualTo(Duration.ofMinutes(10));
            assertThat(p.batchLimit()).isEqualTo(1000);
            assertThat(p.refreshTokensKeepFor()).isEqualTo(Duration.ofDays(14));
            assertThat(p.pairingAttemptsKeepFor()).isEqualTo(Duration.ofHours(1));
            assertThat(p.pairingCodesKeepFor()).isEqualTo(Duration.ofHours(1));
        });
    }

    @Test
    void 주기가_0이면_부팅이_거부된다() {
        runner.withPropertyValues("pokeclip.retention.interval=PT0S")
                .run(c -> assertThat(c).hasFailed());
    }

    @Test
    void 보관_기간이_음수면_부팅이_거부된다() {
        runner.withPropertyValues("pokeclip.retention.refresh-tokens-keep-for=-PT1H")
                .run(c -> assertThat(c).hasFailed());
        runner.withPropertyValues("pokeclip.retention.pairing-attempts-keep-for=-PT1H")
                .run(c -> assertThat(c).hasFailed());
        runner.withPropertyValues("pokeclip.retention.pairing-codes-keep-for=-PT1H")
                .run(c -> assertThat(c).hasFailed());
    }

    @Test
    void 상한이_0이면_부팅이_거부된다() {
        runner.withPropertyValues("pokeclip.retention.batch-limit=0")
                .run(c -> assertThat(c).hasFailed());
    }

    @Configuration
    @EnableConfigurationProperties(RetentionProperties.class)
    static class Enable {
    }
}
