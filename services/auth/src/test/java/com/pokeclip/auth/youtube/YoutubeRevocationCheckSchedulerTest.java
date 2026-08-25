package com.pokeclip.auth.youtube;

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

/**
 * 철회 점검 틱. 치지직의 「만료 임박 선갱신」과 <b>축이 다르다</b> — 구글 access는 1시간이라
 * 선갱신이 성립하지 않는다(모든 살아있는 행이 늘 임박이다). 대신 「오래 확인 안 한 연동」을 골라
 * 갱신을 시도해, 사용자가 구글 쪽에서 권한을 끊은 것을 <b>방송 직전이 아니라 미리</b> 드러낸다.
 */
class YoutubeRevocationCheckSchedulerTest {

    /** 운영에서 조용히 꺼지지 않는다 — 프로퍼티가 없으면 켜진다. 꺼짐은 명시적으로만(테스트 프로파일). */
    @Test
    void 프로퍼티가_없으면_켜지고_false면_꺼진다() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(YoutubeRevocationCheckScheduler.class)
                .withBean(YoutubeChannelLinkRepository.class, () -> mock(YoutubeChannelLinkRepository.class))
                .withBean(YoutubeTokenRefresher.class, () -> mock(YoutubeTokenRefresher.class))
                .withBean(YoutubeProperties.class, YoutubeRevocationCheckSchedulerTest::testProps);

        runner.run(c -> assertThat(c).hasSingleBean(YoutubeRevocationCheckScheduler.class));
        runner.withPropertyValues("pokeclip.youtube.check.enabled=false")
                .run(c -> assertThat(c).doesNotHaveBean(YoutubeRevocationCheckScheduler.class));
    }

    /**
     * {@code @Scheduled}의 placeholder는 테스트 프로파일에서 빈 자체가 꺼져 어디서도 평가되지 않는다 —
     * 키를 오타내면 <b>운영 부팅에서만</b> 죽는다. 여기서 스케줄링을 켜고 실제 등록을 잰다.
     */
    @Test
    void 스케줄이_interval_프로퍼티로_실제로_등록된다() {
        new ApplicationContextRunner()
                .withUserConfiguration(Scheduling.class, YoutubeRevocationCheckScheduler.class)
                .withBean(YoutubeChannelLinkRepository.class, () -> mock(YoutubeChannelLinkRepository.class))
                .withBean(YoutubeTokenRefresher.class, () -> mock(YoutubeTokenRefresher.class))
                .withBean(YoutubeProperties.class, YoutubeRevocationCheckSchedulerTest::testProps)
                .withPropertyValues("pokeclip.youtube.check.interval=PT1H")
                .run(c -> {
                    assertThat(c).hasNotFailed();
                    var tasks = c.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks();
                    assertThat(tasks).hasSize(1);
                    FixedDelayTask task = (FixedDelayTask) tasks.iterator().next().getTask();
                    assertThat(task.getIntervalDuration()).isEqualTo(Duration.ofHours(1));
                    // 부팅 직후 첫 틱이 컨텍스트 로딩과 겹치지 않게 initialDelay도 같은 값을 준다.
                    assertThat(task.getInitialDelayDuration()).isEqualTo(Duration.ofHours(1));
                });
    }

    @Configuration
    @EnableScheduling
    static class Scheduling {
    }

    /**
     * 후보는 「24시간 넘게 확인 안 한 살아있는 행」이고, 갱신 요구 수명은 {@code ZERO}다 —
     * 그 행의 access(1시간)는 이미 만료됐으므로 ZERO로도 실제 갱신이 일어나고, 사용자가 권한을 끊었으면
     * 그 자리에서 REJECTED → BROKEN이 된다. 별도 「강제 갱신」 경로를 만들지 않는 이유다(계획 2절 결정 5).
     */
    @Test
    void 틱은_오래된_회원만_골라_하나씩_갱신하고_하나가_터져도_다음으로_간다() {
        YoutubeChannelLinkRepository links = mock(YoutubeChannelLinkRepository.class);
        YoutubeTokenRefresher refresher = mock(YoutubeTokenRefresher.class);
        when(links.findUserIdsNotRefreshedSince(any())).thenReturn(List.of(1L, 2L, 3L));
        when(refresher.refreshIfExpiringWithin(eq(2L), any())).thenThrow(new IllegalStateException("secret 없음"));

        new YoutubeRevocationCheckScheduler(links, refresher, testProps()).tick();

        verify(refresher).refreshIfExpiringWithin(1L, Duration.ZERO);
        verify(refresher).refreshIfExpiringWithin(3L, Duration.ZERO);
    }

    /**
     * 🔴 틱 하나가 후보를 <b>무한정</b> 처리하면 스프링 기본 스케줄러 스레드(corePoolSize=1, 실측)를 오래 붙잡아
     * <b>치지직 갱신 틱까지 막는다</b> — 구글 장애로 회원마다 read-timeout(5s)이면 후보 100명에 500초다
     * (봇 3판 P2-1). 그래서 틱당 상한을 둔다. 남은 후보는 다음 틱이 가져간다 — 후보 정렬이
     * {@code lastRefreshedAt} 오름차순이라 오래된 것부터 순서대로 소진된다.
     */
    @Test
    void 틱_하나가_처리하는_후보에_상한이_있다() {
        YoutubeChannelLinkRepository links = mock(YoutubeChannelLinkRepository.class);
        YoutubeTokenRefresher refresher = mock(YoutubeTokenRefresher.class);
        List<Long> many = java.util.stream.LongStream.rangeClosed(1, YoutubeRevocationCheckScheduler.BATCH_LIMIT + 20)
                .boxed().toList();
        when(links.findUserIdsNotRefreshedSince(any())).thenReturn(many);

        new YoutubeRevocationCheckScheduler(links, refresher, testProps()).tick();

        verify(refresher, org.mockito.Mockito.times(YoutubeRevocationCheckScheduler.BATCH_LIMIT))
                .refreshIfExpiringWithin(any(), any());
    }

    /** 상한을 넘긴 후보가 <b>다음 틱에</b> 처리된다 — 「상한을 뒀더니 영영 안 돈다」가 되면 안 된다. */
    @Test
    void 상한을_넘긴_후보는_다음_틱이_가져간다() {
        YoutubeChannelLinkRepository links = mock(YoutubeChannelLinkRepository.class);
        YoutubeTokenRefresher refresher = mock(YoutubeTokenRefresher.class);
        // 1틱: 갱신되지 않은 것이 상한+2개 → 2틱에서는 앞 상한개가 빠진 목록이 온다(선별이 lastRefreshedAt 기준이라)
        List<Long> first = java.util.stream.LongStream.rangeClosed(1, YoutubeRevocationCheckScheduler.BATCH_LIMIT + 2)
                .boxed().toList();
        List<Long> second = List.of(9001L, 9002L);
        when(links.findUserIdsNotRefreshedSince(any())).thenReturn(first, second);
        YoutubeRevocationCheckScheduler scheduler = new YoutubeRevocationCheckScheduler(links, refresher, testProps());

        scheduler.tick();
        scheduler.tick();

        verify(refresher).refreshIfExpiringWithin(9001L, Duration.ZERO);
        verify(refresher).refreshIfExpiringWithin(9002L, Duration.ZERO);
    }

    /** 선별 기준 시각이 「지금 − staleness」인지. 부호를 뒤집으면 미래 시각이 되어 살아있는 행이 전부 후보가 된다. */
    @Test
    void 후보_기준_시각은_지금에서_staleness만큼_이전이다() {
        YoutubeChannelLinkRepository links = mock(YoutubeChannelLinkRepository.class);
        when(links.findUserIdsNotRefreshedSince(any())).thenReturn(List.of());
        java.time.Instant before = java.time.Instant.now();

        new YoutubeRevocationCheckScheduler(links, mock(YoutubeTokenRefresher.class), testProps()).tick();

        var captor = org.mockito.ArgumentCaptor.forClass(java.time.Instant.class);
        verify(links).findUserIdsNotRefreshedSince(captor.capture());
        assertThat(captor.getValue())
                .isBetween(before.minus(Duration.ofHours(24)).minusSeconds(5), before.minus(Duration.ofHours(24)).plusSeconds(5));
    }

    private static YoutubeProperties testProps() {
        return new YoutubeProperties(
                new YoutubeProperties.App("cid", "csecret", "http://localhost:8081/oauth/youtube/callback"),
                "https://accounts.google.com/o/oauth2/v2/auth", "http://127.0.0.1:1", "http://127.0.0.1:1",
                "http://127.0.0.1:1", Duration.ofMinutes(10), Duration.ofMinutes(30),
                new YoutubeProperties.Check(true, Duration.ofHours(1), Duration.ofHours(24)));
    }
}
