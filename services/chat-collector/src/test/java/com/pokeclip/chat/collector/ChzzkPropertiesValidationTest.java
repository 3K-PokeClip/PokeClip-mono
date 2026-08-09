package com.pokeclip.chat.collector;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 토큰 검증이 <b>{@code enabled} 값에 따라</b> 걸리는지.
 *
 * <p>이 테스트가 없어서 실제 결함이 났다. {@code accessToken}에 {@code @NotBlank}를
 * 그냥 붙였더니 {@code enabled=false}에서도 걸려 <b>기본 설정으로 서버가 아예 못 떴다.</b>
 * 기본값을 false로 둔 이유가 "CI·남의 로컬이 뜰 때마다 치지직에 붙는 것"을 막으려는
 * 것인데, 그러면 그들이 부팅조차 못 한다.
 *
 * <p>단위 테스트로는 안 보였다 — 테스트는 {@code application-test.yml}이 토큰을
 * 채워 주므로 늘 초록이었다. 프로세스를 띄워야만 보였다.
 */
class ChzzkPropertiesValidationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(BoundProperties.class)
            .withPropertyValues("pokeclip.chzzk.base-url=https://openapi.chzzk.naver.com")
            .withPropertyValues("pokeclip.chzzk.establish-timeout=15s")
            .withPropertyValues("pokeclip.chzzk.reconnect-first-delay=35s")
            .withPropertyValues("pokeclip.chzzk.reconnect-max-delay=120s");

    /** PRD 상태표 첫 행이 성립하려면 이 상태로 떠 있어야 한다. */
    @Test
    void 꺼져_있으면_토큰이_비어도_부팅한다() {
        runner.withPropertyValues("pokeclip.chzzk.enabled=false")
                .withPropertyValues("pokeclip.chzzk.access-token=")
                .run(context -> assertThat(context).hasNotFailed());
    }

    /**
     * 이쪽이 없으면 검증을 통째로 지워도 위 테스트가 초록이라 아무도 모른다.
     * 켜놓고 토큰이 비면 "서버는 뜨고 수집만 조용히 실패"가 되는데,
     * services/CLAUDE.md의 규칙이 막으려는 것이 정확히 그 상태다.
     */
    @Test
    void 켜져_있는데_토큰이_비면_부팅이_실패한다() {
        runner.withPropertyValues("pokeclip.chzzk.enabled=true")
                .withPropertyValues("pokeclip.chzzk.access-token=")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void 켜져_있고_토큰이_있으면_부팅한다() {
        runner.withPropertyValues("pokeclip.chzzk.enabled=true")
                .withPropertyValues("pokeclip.chzzk.access-token=test-only-token")
                .run(context -> assertThat(context).hasNotFailed());
    }

    /** baseUrl은 enabled와 무관하게 항상 필요하다. 비면 어느 상태에서도 잘못이다. */
    @Test
    void base_url이_비면_꺼져_있어도_부팅이_실패한다() {
        runner.withPropertyValues("pokeclip.chzzk.enabled=false")
                .withPropertyValues("pokeclip.chzzk.access-token=")
                .withPropertyValues("pokeclip.chzzk.base-url=")
                .run(context -> assertThat(context).hasFailed());
    }

    /**
     * 재연결 간격이 비면 <b>부팅에서</b> 잡는다.
     *
     * <p>안 잡으면 null로 바인딩되고, 아무도 안 읽는 동안은 아무 일도 없다.
     * 재연결 루프가 읽는 날 NPE로 죽는데 그때 원인은 "설정에 값이 없다"가 아니라
     * "왜 여기서 NPE가 나지"로 보인다. 이 줄이 없으면 {@code @NotNull}을 떼도
     * 전부 초록이라, 그 검증이 있는지 없는지를 아무도 모른다.
     */
    @Test
    void 재연결_간격이_비면_부팅이_실패한다() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
                .withUserConfiguration(BoundProperties.class)
                .withPropertyValues("pokeclip.chzzk.base-url=https://openapi.chzzk.naver.com")
                .withPropertyValues("pokeclip.chzzk.establish-timeout=15s")
                .withPropertyValues("pokeclip.chzzk.enabled=false")
                .withPropertyValues("pokeclip.chzzk.access-token=")
                .run(context -> assertThat(context).hasFailed());
    }

    @EnableConfigurationProperties(ChzzkProperties.class)
    static class BoundProperties { }
}
