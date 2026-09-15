package com.pokeclip.clip.broadcast.reaper;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** 유예 하한(15분)은 계약9의 정산 창이다 — 그보다 짧으면 정상 편지와 경주한다. */
class ReaperPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(Bound.class);

    @Test
    void 기본값_모양은_뜬다() {
        runner.withPropertyValues(values("PT1M", "PT30M")).run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void 유예가_15분_미만이면_켜짐과_무관하게_부팅이_실패한다() {
        runner.withPropertyValues(values("PT1M", "PT14M")).run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues(values("PT1M", "PT15M")).run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void 주기가_0이거나_유예_이상이면_부팅이_실패한다() {
        runner.withPropertyValues(values("PT0S", "PT30M")).run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues(values("PT30M", "PT30M")).run(context -> assertThat(context).hasFailed());
    }

    private static String[] values(String interval, String grace) {
        return new String[] {
                "pokeclip.broadcast.reaper.enabled=false",
                "pokeclip.broadcast.reaper.interval=" + interval,
                "pokeclip.broadcast.reaper.grace=" + grace};
    }

    @EnableConfigurationProperties(ReaperProperties.class)
    static class Bound {
    }
}
