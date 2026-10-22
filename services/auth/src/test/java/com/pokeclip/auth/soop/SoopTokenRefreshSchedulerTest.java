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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SoopTokenRefreshSchedulerTest {

    private static final RefreshResult REJ = RefreshResult.of(RefreshOutcome.REJECTED);
    private static final RefreshResult OK = RefreshResult.of(RefreshOutcome.REFRESHED);
    private static final RefreshResult UNAVAILABLE = RefreshResult.of(RefreshOutcome.UNAVAILABLE);

    private final SoopChannelLinkRepository links = mock(SoopChannelLinkRepository.class);
    private final SoopTokenRefresher refresher = mock(SoopTokenRefresher.class);
    private final SoopTokenRefreshScheduler scheduler = new SoopTokenRefreshScheduler(links, refresher, testProps());

    /** 운영에서 조용히 꺼지지 않는다: 프로퍼티가 없으면 켜진다(matchIfMissing). 꺼짐은 명시적으로만. */
    @Test
    void 프로퍼티가_없으면_켜지고_false면_꺼진다() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(SoopTokenRefreshScheduler.class)
                .withBean(SoopChannelLinkRepository.class, () -> mock(SoopChannelLinkRepository.class))
                .withBean(SoopTokenRefresher.class, () -> mock(SoopTokenRefresher.class))
                .withBean(SoopProperties.class, SoopTokenRefreshSchedulerTest::testProps);
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

    /** 총합이 아니라 「연속」을 센다(계획 검증 M9): 사이에 성공이 끼면 0으로 돌아가 다섯 다 처리된다. */
    @Test
    void 거절_사이에_성공이_끼면_멈추지_않는다() {
        when(links.findIdsExpiringOrStale(any(), any(), any())).thenReturn(List.of(1L, 2L, 3L, 4L, 5L));
        when(refresher.refreshForCheck(1L)).thenReturn(REJ);
        when(refresher.refreshForCheck(2L)).thenReturn(REJ);
        when(refresher.refreshForCheck(3L)).thenReturn(OK);
        when(refresher.refreshForCheck(4L)).thenReturn(REJ);
        when(refresher.refreshForCheck(5L)).thenReturn(REJ);

        try (LogCaptor logs = new LogCaptor()) {
            scheduler.tick();
            assertThat(logs.messages()).noneMatch(m -> m.startsWith("auth.soop.link.refresh_reject_burst"));
        }

        verify(refresher).refreshForCheck(5L);
        scheduler.tick();
        verify(links, org.mockito.Mockito.times(2)).findIdsExpiringOrStale(any(), any(), any());
    }

    /**
     * 일시 장애(UNAVAILABLE)는 연속 수를 0으로 되돌리지 않는다(그대로 둔다, 계획 검증 M9): 앱 비밀값이 틀리면
     * 5xx가 섞여 와도 멈춰야 한다. 셋째 거절에서 멈추고 그 뒤 회원은 안 부르며, 다음 틱도 서지 않는다.
     */
    @Test
    void 거절이_연달아_브레이크_수에_닿으면_그_틱과_이후_틱을_멈춘다() {
        when(links.findIdsExpiringOrStale(any(), any(), any())).thenReturn(List.of(1L, 2L, 3L, 4L, 5L));
        when(refresher.refreshForCheck(1L)).thenReturn(REJ);
        when(refresher.refreshForCheck(2L)).thenReturn(UNAVAILABLE);
        when(refresher.refreshForCheck(3L)).thenReturn(REJ);
        when(refresher.refreshForCheck(4L)).thenReturn(REJ);
        when(refresher.refreshForCheck(5L)).thenReturn(OK);

        try (LogCaptor logs = new LogCaptor()) {
            scheduler.tick();
            assertThat(logs.messages()).anyMatch(m -> m.startsWith("auth.soop.link.refresh_reject_burst"));
            assertThat(logs.levelOf("auth.soop.link.refresh_reject_burst")).isEqualTo(Level.ERROR);
        }

        verify(refresher).refreshForCheck(4L);
        verify(refresher, never()).refreshForCheck(5L);

        // 한 번만 막고 풀리는 구현을 떨어뜨리려고 세 번 더 친다(프로세스 수명 동안 서 있어야 한다).
        scheduler.tick();
        scheduler.tick();
        scheduler.tick();
        verify(links, org.mockito.Mockito.times(1)).findIdsExpiringOrStale(any(), any(), any());
        verify(refresher, never()).refreshForCheck(5L);
    }

    /** 틱 안에서만 센다: 앞 틱 끝의 거절 둘이 다음 틱 첫 거절과 이어지지 않는다. */
    @Test
    void 연속_수는_틱마다_새로_센다() {
        when(links.findIdsExpiringOrStale(any(), any(), any())).thenReturn(List.of(1L, 2L));
        when(refresher.refreshForCheck(anyLong())).thenReturn(REJ);

        scheduler.tick();
        scheduler.tick();
        scheduler.tick();

        verify(links, org.mockito.Mockito.times(3)).findIdsExpiringOrStale(any(), any(), any());
    }

    static SoopProperties testProps() {
        return new SoopProperties(
                new SoopProperties.App("cid", "csecret", "http://localhost:3000/oauth/soop/callback"),
                "https://openapi.sooplive.com/auth/code", "http://127.0.0.1:1",
                Duration.ofMinutes(10), Duration.ofHours(2), Duration.ofHours(1),
                new SoopProperties.Check(true, Duration.ofHours(1), Duration.ofHours(24), 3));
    }
}
