package com.pokeclip.auth.chzzk;

import com.pokeclip.auth.AuthApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.FixedDelayTask;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChzzkTokenRefreshSchedulerTest {

    /** 운영에서 조용히 꺼지지 않는다 — 프로퍼티가 없으면 켜진다(matchIfMissing). 꺼짐은 명시적으로만. */
    @Test
    void 프로퍼티가_없으면_켜지고_false면_꺼진다() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(ChzzkTokenRefreshScheduler.class)
                .withBean(ChzzkChannelLinkRepository.class, () -> mock(ChzzkChannelLinkRepository.class))
                .withBean(ChzzkTokenRefresher.class, () -> mock(ChzzkTokenRefresher.class))
                .withBean(ChzzkProperties.class, ChzzkTokenRefreshSchedulerTest::testProps);
        runner.run(c -> assertThat(c).hasSingleBean(ChzzkTokenRefreshScheduler.class));
        runner.withPropertyValues("pokeclip.chzzk.refresh.enabled=false")
                .run(c -> assertThat(c).doesNotHaveBean(ChzzkTokenRefreshScheduler.class));
    }

    /**
     * {@code @Scheduled}의 placeholder(`${pokeclip.chzzk.refresh.interval}`)는 테스트 프로파일에서 빈 자체가 꺼져
     * 어디서도 평가되지 않는다 — 키를 오타내면 운영 부팅에서만 죽는다. 여기서 스케줄링을 켜고 실제로 등록되는지 잰다.
     */
    @Test
    void 스케줄이_interval_프로퍼티로_실제로_등록된다() {
        new ApplicationContextRunner()
                .withUserConfiguration(Scheduling.class, ChzzkTokenRefreshScheduler.class)
                .withBean(ChzzkChannelLinkRepository.class, () -> mock(ChzzkChannelLinkRepository.class))
                .withBean(ChzzkTokenRefresher.class, () -> mock(ChzzkTokenRefresher.class))
                .withBean(ChzzkProperties.class, ChzzkTokenRefreshSchedulerTest::testProps)
                .withPropertyValues("pokeclip.chzzk.refresh.interval=PT10M")
                .run(c -> {
                    assertThat(c).hasNotFailed();
                    var tasks = c.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks();
                    assertThat(tasks).hasSize(1);
                    FixedDelayTask task = (FixedDelayTask) tasks.iterator().next().getTask();
                    assertThat(task.getIntervalDuration()).isEqualTo(Duration.ofMinutes(10));
                    assertThat(task.getInitialDelayDuration()).isEqualTo(Duration.ofMinutes(10));
                });
    }

    @Configuration
    @EnableScheduling
    static class Scheduling {
    }

    /** 위 runner는 우리가 켠 스케줄링으로 잰다 — 운영 배선(AuthApplication의 @EnableScheduling)은 따로 본다. */
    @Test
    void 운영_앱에_EnableScheduling이_붙어_있다() {
        assertThat(AuthApplication.class.isAnnotationPresent(EnableScheduling.class)).isTrue();
    }

    @Test
    void 틱은_임박한_회원만_골라_하나씩_갱신하고_하나가_터져도_다음으로_간다() {
        ChzzkChannelLinkRepository links = mock(ChzzkChannelLinkRepository.class);
        ChzzkTokenRefresher refresher = mock(ChzzkTokenRefresher.class);
        when(links.findUserIdsExpiringBefore(any())).thenReturn(List.of(1L, 2L, 3L));
        when(refresher.refreshIfExpiringWithin(eq(2L), any())).thenThrow(new IllegalStateException("secret 없음"));
        new ChzzkTokenRefreshScheduler(links, refresher, testProps()).tick();
        verify(refresher).refreshIfExpiringWithin(1L, Duration.ofHours(6));
        verify(refresher).refreshIfExpiringWithin(3L, Duration.ofHours(6));
    }

    /** 태스크 4의 props(String)와 같은 값(refreshAhead=6h). */
    private static ChzzkProperties testProps() {
        return new ChzzkProperties(
                new ChzzkProperties.App("cid", "csecret", "http://localhost:8081/oauth/chzzk/callback"),
                "https://chzzk.naver.com/account-interlock", "http://127.0.0.1:1", Duration.ofMinutes(10),
                Duration.ofHours(6), Duration.ofHours(12), new ChzzkProperties.Refresh(true, Duration.ofMinutes(10)));
    }
}
