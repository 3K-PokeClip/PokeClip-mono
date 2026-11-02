package com.pokeclip.auth.soop;

import ch.qos.logback.classic.Level;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.FixedDelayTask;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SoopTokenRefreshSchedulerTest {

    private static final RefreshResult REJ = RefreshResult.of(RefreshOutcome.REJECTED);
    private static final RefreshResult OK = RefreshResult.of(RefreshOutcome.REFRESHED);
    private static final RefreshResult UNAVAILABLE = RefreshResult.of(RefreshOutcome.UNAVAILABLE);

    private final SoopChannelLinkRepository links = mock(SoopChannelLinkRepository.class);
    private final SoopTokenRefresher refresher = mock(SoopTokenRefresher.class);
    private final SoopRefreshBrake brake = new SoopRefreshBrake(testProps());
    private final SoopTokenRefreshScheduler scheduler = new SoopTokenRefreshScheduler(links, refresher, testProps(), brake);

    /** 운영에서 조용히 꺼지지 않는다: 프로퍼티가 없으면 켜진다(matchIfMissing). 꺼짐은 명시적으로만. */
    @Test
    void 프로퍼티가_없으면_켜지고_false면_꺼진다() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(SoopTokenRefreshScheduler.class)
                .withBean(SoopChannelLinkRepository.class, () -> mock(SoopChannelLinkRepository.class))
                .withBean(SoopTokenRefresher.class, () -> mock(SoopTokenRefresher.class))
                .withBean(SoopProperties.class, SoopTokenRefreshSchedulerTest::testProps)
                .withBean(SoopRefreshBrake.class, () -> new SoopRefreshBrake(testProps()));
        runner.run(c -> assertThat(c).hasSingleBean(SoopTokenRefreshScheduler.class));
        runner.withPropertyValues("pokeclip.soop.check.enabled=false")
                .run(c -> assertThat(c).doesNotHaveBean(SoopTokenRefreshScheduler.class));
    }

    /** {@code @Scheduled}의 자리표시는 시험 프로파일에서 빈이 꺼져 평가되지 않는다. 여기서 스케줄링을 켜고 등록을 잰다. */
    @Test
    void 스케줄이_interval_프로퍼티로_실제로_등록된다() {
        new ApplicationContextRunner()
                .withUserConfiguration(Scheduling.class, SoopTokenRefreshScheduler.class)
                .withBean(SoopChannelLinkRepository.class, () -> mock(SoopChannelLinkRepository.class))
                .withBean(SoopTokenRefresher.class, () -> mock(SoopTokenRefresher.class))
                .withBean(SoopProperties.class, SoopTokenRefreshSchedulerTest::testProps)
                .withBean(SoopRefreshBrake.class, () -> new SoopRefreshBrake(testProps()))
                .withPropertyValues("pokeclip.soop.check.interval=PT1H")
                .run(c -> {
                    assertThat(c).hasNotFailed();
                    var tasks = c.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks();
                    assertThat(tasks).hasSize(1);
                    FixedDelayTask task = (FixedDelayTask) tasks.iterator().next().getTask();
                    assertThat(task.getIntervalDuration()).isEqualTo(Duration.ofHours(1));
                    assertThat(task.getInitialDelayDuration()).isEqualTo(Duration.ofHours(1));
                });
    }

    @Configuration
    @EnableScheduling
    static class Scheduling {
    }

    /** 후보 = 만료 임박(지금+refreshAhead 전) 또는 마지막 확인이 staleness 지난 행. 회원마다 점검 갱신을 부른다. */
    @Test
    void 틱은_임박하거나_확인이_오래된_후보를_골라_하나씩_점검하고_하나가_터져도_다음으로_간다() {
        when(links.findIdsExpiringOrStale(any(), any(), any())).thenReturn(List.of(1L, 2L, 3L));
        when(refresher.refreshForCheck(1L)).thenReturn(OK);
        when(refresher.refreshForCheck(2L)).thenThrow(new IllegalStateException("secret 없음"));
        when(refresher.refreshForCheck(3L)).thenReturn(OK);
        Instant now = Instant.now();

        scheduler.tick();

        ArgumentCaptor<Instant> expiring = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> checked = ArgumentCaptor.forClass(Instant.class);
        verify(links).findIdsExpiringOrStale(expiring.capture(), checked.capture(), any(Pageable.class));
        assertThat(expiring.getValue()).isCloseTo(now.plus(Duration.ofHours(2)), within(Duration.ofSeconds(5)));
        assertThat(checked.getValue()).isCloseTo(now.minus(Duration.ofHours(24)), within(Duration.ofSeconds(5)));
        verify(refresher).refreshForCheck(1L);
        verify(refresher).refreshForCheck(3L);
    }

    /**
     * 한 틱의 후보에 상한(batch-limit 25)을 둔다(PR #329 봇 지적): 스프링 기본 스케줄러는 스레드 하나라 치지직·유튜브·청소
     * 틱과 공유한다. 후보가 많을 때 한 틱이 그 스레드를 오래 쥐면 다른 틱이 밀린다. 오래 확인 안 한 순 앞 25만 본다.
     */
    @Test
    void 한_틱의_후보는_batch_limit까지만_묻는다() {
        when(links.findIdsExpiringOrStale(any(), any(), any())).thenReturn(List.of());

        scheduler.tick();

        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(links).findIdsExpiringOrStale(any(), any(), page.capture());
        assertThat(page.getValue().isPaged()).as("상한 없이 전부 읽는다").isTrue();
        assertThat(page.getValue().getPageNumber()).isZero();
        assertThat(page.getValue().getPageSize()).isEqualTo(25);
    }

    /**
     * 브레이크가 서면(세는 것은 갱신기와 {@link SoopRefreshBrake}가 한다) 그 틱의 남은 회원을 안 부르고, 다음 틱도 서지 않는다.
     * 연속 거절 셈 자체는 {@code SoopRefreshBrakeTest}가 잰다.
     */
    @Test
    void 브레이크가_서면_그_틱과_이후_틱을_멈춘다() {
        when(links.findIdsExpiringOrStale(any(), any(), any())).thenReturn(List.of(1L, 2L, 3L, 4L, 5L));
        when(refresher.refreshForCheck(anyLong())).thenReturn(OK);
        when(refresher.refreshForCheck(3L)).thenAnswer(call -> {
            brake.engage();   // 갱신기가 셋째 거절을 세며 브레이크를 세운 것과 같다
            return UNAVAILABLE;
        });

        scheduler.tick();

        verify(refresher).refreshForCheck(3L);
        verify(refresher, never()).refreshForCheck(4L);
        verify(refresher, never()).refreshForCheck(5L);
        // 한 번만 막고 풀리는 구현을 떨어뜨리려고 세 번 더 친다(프로세스 수명 동안 서 있어야 한다).
        scheduler.tick();
        scheduler.tick();
        scheduler.tick();
        verify(links, times(1)).findIdsExpiringOrStale(any(), any(), any());
    }

    @Test
    void 브레이크가_안_서면_후보를_다_본다() {
        when(links.findIdsExpiringOrStale(any(), any(), any())).thenReturn(List.of(1L, 2L, 3L));
        when(refresher.refreshForCheck(anyLong())).thenReturn(REJ);

        scheduler.tick();

        verify(refresher).refreshForCheck(3L);
    }

    static SoopProperties testProps() {
        return new SoopProperties(
                new SoopProperties.App("cid", "csecret", "http://localhost:3000/oauth/soop/callback"),
                "https://openapi.sooplive.com/auth/code", "http://127.0.0.1:1",
                Duration.ofMinutes(10), Duration.ofHours(2), Duration.ofHours(1),
                new SoopProperties.Check(true, Duration.ofHours(1), Duration.ofHours(24), 3, 25));
    }
}
