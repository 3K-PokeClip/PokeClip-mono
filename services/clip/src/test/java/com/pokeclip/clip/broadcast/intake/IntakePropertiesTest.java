package com.pokeclip.clip.broadcast.intake;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 켜는 값을 따로 두는 이유: 큐 주소가 비었다고 저절로 꺼지면 "로컬에서 일부러 안 켬"과
 * "운영에서 깜빡함"이 구분되지 않는다. chat-collector의 CHZZK_ENABLED와 같은 규칙이다.
 */
class IntakePropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(BoundProperties.class);

    @Test
    void 켜져_있는데_큐_주소가_없으면_부팅이_실패한다() {
        runner.withPropertyValues(
                        "pokeclip.broadcast.intake.enabled=true",
                        "pokeclip.broadcast.intake.queue-url=",
                        "pokeclip.broadcast.intake.region=ap-northeast-2",
                        "pokeclip.broadcast.intake.wait-time=20s",
                        "pokeclip.broadcast.intake.max-messages=10")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void 꺼져_있으면_큐_주소가_없어도_뜬다() {
        runner.withPropertyValues(
                        "pokeclip.broadcast.intake.enabled=false",
                        "pokeclip.broadcast.intake.queue-url=",
                        "pokeclip.broadcast.intake.region=ap-northeast-2",
                        "pokeclip.broadcast.intake.wait-time=20s",
                        "pokeclip.broadcast.intake.max-messages=10")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void 리전이_비면_켜짐과_무관하게_부팅이_실패한다() {
        runner.withPropertyValues(
                        "pokeclip.broadcast.intake.enabled=false",
                        "pokeclip.broadcast.intake.region=",
                        "pokeclip.broadcast.intake.wait-time=20s",
                        "pokeclip.broadcast.intake.max-messages=10")
                .run(context -> assertThat(context).hasFailed());
    }

    @EnableConfigurationProperties(IntakeProperties.class)
    static class BoundProperties {
    }
}
