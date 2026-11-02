package com.pokeclip.chat.collector.broadcast.reattach;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** 떼기만 켜고 재부착을 끄면 스위치가 조용히 아무것도 안 한다 — 부팅에서 거부한다(codex P2). */
class DetachSwitchGuardTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(DetachSwitchGuard.class)
            .withPropertyValues("pokeclip.reattach.clip-base-url=http://localhost:8081",
                    "pokeclip.reattach.interval=PT1M", "pokeclip.reattach.initial-delay=PT5S",
                    "pokeclip.reattach.detach-grace=PT10M");

    @Test
    void 재부착이_꺼진_채_떼기만_켜면_부팅이_실패한다() {
        runner.withPropertyValues("pokeclip.reattach.enabled=false", "pokeclip.reattach.detach-enabled=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("detach-enabled");
                });
    }

    @Test
    void 둘_다_켜거나_떼기가_꺼져_있으면_뜬다() {
        runner.withPropertyValues("pokeclip.reattach.enabled=true", "pokeclip.reattach.detach-enabled=true")
                .run(context -> assertThat(context).hasNotFailed());
        runner.withPropertyValues("pokeclip.reattach.enabled=false", "pokeclip.reattach.detach-enabled=false")
                .run(context -> assertThat(context).hasNotFailed());
    }
}
