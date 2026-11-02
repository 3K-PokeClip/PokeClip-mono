package com.pokeclip.chat.collector.broadcast.intake;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 큐 설정이 잘못된 채로 뜨지 않는지를 <b>부팅으로</b> 잰다.
 *
 * <p><b>계획의 검사 골격을 바꿨다.</b> 초안은 {@code new IntakeProperties(...).validateWhenEnabled()}를
 * 직접 부르는 모양이었는데, 그러면 이름이 말하는 「부팅이 실패한다」를 안 잰다 —
 * {@code @PostConstruct}를 통째로 떼도 그 검사는 초록이고 서버는 잘못된 설정으로 뜬다
 * (문항 5로 실제 확인: 애노테이션 둘을 떼면 직접 호출 검사는 초록, 아래 검사들은 빨간불).
 * 그래서 스프링이 실제로 바인딩하고 검증하는 경로로 잰다.
 *
 * <p><b>다중 세션 문항</b> — 이 부품에는 세션도 스레드도 없다. 문항 1·3은 잴 대상이 없어
 * 해당하지 않는다(재 보지 않은 것이 아니다).
 */
class IntakePropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(BoundProperties.class);

    /**
     * 켜는 값을 큐 주소와 따로 두는 이유: 주소가 비었다고 저절로 꺼지면 「로컬에서 일부러
     * 안 켬」과 「운영에서 깜빡함」이 똑같이 보인다. {@code CHZZK_ENABLED}와 같은 규칙이다.
     *
     * <p>문항 2: 아래 「꺼져 있으면 뜬다」가 양성 대조다 — 무조건 실패시켜도 통과하는 것을 막는다.
     * <p>문항 5: {@code validateWhenEnabled}의 {@code @PostConstruct}를 떼면 초록(확인함).
     */
    @Test
    void 켜졌는데_큐_주소가_비면_부팅이_실패한다() {
        runner.withPropertyValues(
                        "pokeclip.broadcast.intake.enabled=true",
                        "pokeclip.broadcast.intake.queue-url=",
                        "pokeclip.broadcast.intake.region=ap-northeast-2",
                        "pokeclip.broadcast.intake.wait-time=20s",
                        "pokeclip.broadcast.intake.max-messages=10")
                .run(context -> assertThat(context).hasFailed());
    }

    /** 양성 대조. 꺼져 있으면 큐 주소가 없는 것이 정상이다 — CI·팀원 로컬의 기본 상태다. */
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

    /**
     * 상한을 넘겨도 <b>부팅은 성공하고 호출만 거부된다</b> — 폴링이 매 회차 실패해 편지를
     * 영영 못 받는데 서버는 UP이다(clip 감사자 실측 2026-08-18: "Must be >= 0 and <= 20").
     * 그래서 배포 전에 걸리도록 부팅에서 막는다.
     *
     * <p>문항 5: {@code validateSqsLimits}의 {@code @PostConstruct}를 떼면 초록(확인함).
     */
    @Test
    void 대기_시간이_SQS_상한을_넘으면_부팅이_실패한다() {
        runner.withPropertyValues(
                        "pokeclip.broadcast.intake.enabled=false",
                        "pokeclip.broadcast.intake.region=ap-northeast-2",
                        "pokeclip.broadcast.intake.wait-time=21s",
                        "pokeclip.broadcast.intake.max-messages=10")
                .run(context -> assertThat(context).hasFailed());
    }

    /** 상한 자체는 유효한 값이다. 이게 없으면 「무조건 거부」로 바꿔도 위 검사가 통과한다(문항 4). */
    @Test
    void 대기_시간이_상한과_같으면_부팅한다() {
        runner.withPropertyValues(
                        "pokeclip.broadcast.intake.enabled=false",
                        "pokeclip.broadcast.intake.region=ap-northeast-2",
                        "pokeclip.broadcast.intake.wait-time=20s",
                        "pokeclip.broadcast.intake.max-messages=10")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void 한_번에_받을_수_있는_상한을_넘으면_부팅이_실패한다() {
        runner.withPropertyValues(
                        "pokeclip.broadcast.intake.enabled=false",
                        "pokeclip.broadcast.intake.region=ap-northeast-2",
                        "pokeclip.broadcast.intake.wait-time=20s",
                        "pokeclip.broadcast.intake.max-messages=11")
                .run(context -> assertThat(context).hasFailed());
    }

    @EnableConfigurationProperties(IntakeProperties.class)
    static class BoundProperties {
    }
}
