package com.pokeclip.clip.jumpcard;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 점유 시한의 하한. <b>{@code JumpCardService}가 {@code toSeconds()}로 잘라 SQL에 넘기므로,
 * 자르기 <u>전</u> 값만 검증하면 잘린 뒤가 무방비가 된다</b>(PR #111 봇 지적 ②, 재현됨).
 *
 * <p>재현으로 나온 것 셋 — {@code PT0S}·{@code PT-60S}·{@code PT0.5S} 전부 <b>부팅했고</b>
 * 셋 다 <b>남이 즉시 점유를 탈취</b>했다. {@code PT0.5S}는 {@code toSeconds()=0}이라
 * {@code PT0S}와 완전히 같아지고, {@code PT-60S}는 컷오프가 {@code now()+60초}로 가서
 * <b>모든 점유</b>가 탈취 가능해진다.
 */
class JumpCardPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(BoundProperties.class);

    @Test
    void 점유_시한이_0이면_부팅이_실패한다() {
        runner.withPropertyValues("pokeclip.jump-card.claim-ttl=PT0S")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(stackTraceOf(context.getStartupFailure()))
                            .as("점유 시한이 아닌 다른 이유로 부팅이 실패했다")
                            .contains("pokeclip.jump-card.claim-ttl")
                            .contains("0보다 커야");
                });
    }

    @Test
    void 점유_시한이_음수면_부팅이_실패한다() {
        runner.withPropertyValues("pokeclip.jump-card.claim-ttl=PT-60S")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(stackTraceOf(context.getStartupFailure()))
                            .as("점유 시한이 아닌 다른 이유로 부팅이 실패했다")
                            .contains("pokeclip.jump-card.claim-ttl")
                            .contains("0보다 커야");
                });
    }

    /**
     * 이 갈래가 <b>이번 지적의 핵심</b>이다. 0도 음수도 아니라 「양수 검사」로는 안 걸리는데
     * {@code toSeconds()}가 0으로 잘라 {@code PT0S}와 같아진다.
     */
    @Test
    void 점유_시한이_초_단위로_안_떨어지면_부팅이_실패한다() {
        runner.withPropertyValues("pokeclip.jump-card.claim-ttl=PT0.5S")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(stackTraceOf(context.getStartupFailure()))
                            .as("양수 검사에만 걸리고 자르기 검사에는 안 걸렸다")
                            .contains("pokeclip.jump-card.claim-ttl")
                            .contains("초 단위");
                });
    }

    /** 1초 이상이어도 초 아래 자리가 남으면 같은 자리다 — 0.5초만 막고 끝내면 뚫린다. */
    @Test
    void 초_아래_자리가_남으면_1초를_넘겨도_실패한다() {
        runner.withPropertyValues("pokeclip.jump-card.claim-ttl=PT90.5S")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(stackTraceOf(context.getStartupFailure()))
                            .contains("초 단위");
                });
    }

    /** 양성 대조 — 설정을 안 주면 기본값으로 뜬다. 이 갈래를 깨면 로컬·CI가 통째로 안 뜬다. */
    @Test
    void 설정이_없으면_기본_30분으로_뜬다() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(JumpCardProperties.class).claimTtl())
                    .isEqualTo(Duration.ofMinutes(30));
        });
    }

    /** 양성 대조 — 검증이 넓으면 멀쩡한 설정까지 막는다. */
    @Test
    void 멀쩡한_값은_그대로_쓴다() {
        runner.withPropertyValues("pokeclip.jump-card.claim-ttl=PT45M")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(JumpCardProperties.class).claimTtl())
                            .isEqualTo(Duration.ofMinutes(45));
                });
    }

    /**
     * 생성자에서 막는 것을 못 박는다. {@code @PostConstruct}로 옮기면 Spring을 안 거치는
     * 직접 생성이 검증을 건너뛰는데, 시험이 대부분 직접 생성이라 그 구멍이 안 보인다.
     */
    @Test
    void 직접_생성해도_막힌다() {
        assertThatThrownBy(() -> new JumpCardProperties(Duration.ZERO))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new JumpCardProperties(Duration.ofMillis(500)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(new JumpCardProperties(null).claimTtl()).isEqualTo(Duration.ofMinutes(30));
    }

    private String stackTraceOf(Throwable failure) {
        StringWriter out = new StringWriter();
        failure.printStackTrace(new PrintWriter(out));
        return out.toString();
    }

    @EnableConfigurationProperties(JumpCardProperties.class)
    static class BoundProperties {
    }
}
