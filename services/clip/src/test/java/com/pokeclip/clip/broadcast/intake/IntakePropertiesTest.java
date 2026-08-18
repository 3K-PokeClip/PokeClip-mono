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

    /**
     * SQS가 거부하는 값으로 부팅이 성공하면 폴링이 <b>매 회차 실패</b>한다
     * ("Must be between 1 and 10" · "Must be >= 0 and <= 20", 감사자 실측).
     * health가 DOWN으로 드러내긴 하지만, 켜졌는데 큐 주소가 없으면 부팅을 거부하는
     * 것과 같은 논리로 여기서 막는다 — 설정 실수는 배포 전에 걸리는 편이 싸다.
     */
    @Test
    void 한번에_받을_수_있는_상한을_넘으면_부팅이_실패한다() {
        runner.withPropertyValues(
                        "pokeclip.broadcast.intake.enabled=false",
                        "pokeclip.broadcast.intake.region=ap-northeast-2",
                        "pokeclip.broadcast.intake.wait-time=20s",
                        "pokeclip.broadcast.intake.max-messages=11")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void 롱폴링_대기가_상한을_넘으면_부팅이_실패한다() {
        runner.withPropertyValues(
                        "pokeclip.broadcast.intake.enabled=false",
                        "pokeclip.broadcast.intake.region=ap-northeast-2",
                        "pokeclip.broadcast.intake.wait-time=21s",
                        "pokeclip.broadcast.intake.max-messages=10")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void 롱폴링_대기가_음수면_부팅이_실패한다() {
        runner.withPropertyValues(
                        "pokeclip.broadcast.intake.enabled=false",
                        "pokeclip.broadcast.intake.region=ap-northeast-2",
                        "pokeclip.broadcast.intake.wait-time=-1s",
                        "pokeclip.broadcast.intake.max-messages=10")
                .run(context -> assertThat(context).hasFailed());
    }

    /** 상한 자체는 유효한 값이다 — 검증이 지나치게 좁으면 운영 기본값이 안 뜬다. */
    @Test
    void 상한값_그대로는_부팅한다() {
        runner.withPropertyValues(
                        "pokeclip.broadcast.intake.enabled=false",
                        "pokeclip.broadcast.intake.region=ap-northeast-2",
                        "pokeclip.broadcast.intake.wait-time=20s",
                        "pokeclip.broadcast.intake.max-messages=10")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @EnableConfigurationProperties(IntakeProperties.class)
    static class BoundProperties {
    }
}
