package com.pokeclip.auth.retention;

import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.FixedDelayTask;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RetentionCleanupSchedulerTest {

    /** 운영에서 조용히 꺼지지 않는다 — 프로퍼티가 없으면 켜진다. 꺼짐은 명시적으로만(테스트 프로파일). */
    @Test
    void 프로퍼티가_없으면_켜지고_false면_꺼진다() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(RetentionCleanupScheduler.class)
                .withBean(RetentionCleaner.class, () -> mock(RetentionCleaner.class));
        runner.run(c -> assertThat(c).hasSingleBean(RetentionCleanupScheduler.class));
        runner.withPropertyValues("pokeclip.retention.enabled=false")
                .run(c -> assertThat(c).doesNotHaveBean(RetentionCleanupScheduler.class));
    }

    /** placeholder 키를 오타 내면 운영 부팅에서만 죽는다 — 여기서 스케줄링을 켜고 실제 등록을 잰다. */
    @Test
    void 스케줄이_interval_프로퍼티로_실제로_등록된다() {
        new ApplicationContextRunner()
                .withUserConfiguration(Scheduling.class, RetentionCleanupScheduler.class)
                .withBean(RetentionCleaner.class, () -> mock(RetentionCleaner.class))
                .withPropertyValues("pokeclip.retention.interval=PT10M")
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

    @Test
    void 틱은_표_셋을_순서대로_부르고_하나가_터져도_다음으로_간다() {
        RetentionCleaner cleaner = mock(RetentionCleaner.class);
        when(cleaner.cleanPairingAttempts(any())).thenThrow(new IllegalStateException("표 잠김"));
        when(cleaner.cleanPairingCodes(any())).thenReturn(new RetentionCleaner.Result(3, false));
        when(cleaner.cleanRefreshTokens(any())).thenReturn(new RetentionCleaner.Result(7, false));

        try (LogCaptor logs = new LogCaptor()) {
            new RetentionCleanupScheduler(cleaner).tick();

            var order = inOrder(cleaner);
            order.verify(cleaner).cleanPairingAttempts(any());
            order.verify(cleaner).cleanPairingCodes(any());
            order.verify(cleaner).cleanRefreshTokens(any());
            assertThat(logs.messages())
                    .contains("auth.retention.failed table=pairing_exchange_attempts deleted=0 rounds=0 causeType=IllegalStateException")
                    .contains("auth.retention.cleaned table=pairing_codes deleted=3 rounds=1 capped=false")
                    .contains("auth.retention.cleaned table=refresh_tokens deleted=7 rounds=1 capped=false");
        }
    }

    /**
     * 상한에 걸리면 같은 틱이 이어서 부른다(사용자 결정 2026-09-03). 교환 창구는 로그인이 없고 429도 행을 남겨
     * 한 IP가 초당 수백 행을 만들 수 있다: 10분에 1,000행이면 폭주 중 표가 자란다. 로그는 바퀴마다가 아니라
     * 표당 한 줄(합계·바퀴 수·마지막 바퀴가 상한에 걸렸나)이다.
     */
    @Test
    void 상한에_걸리면_같은_틱에서_이어_부른다() {
        RetentionCleaner cleaner = mock(RetentionCleaner.class);
        when(cleaner.cleanPairingAttempts(any())).thenReturn(
                new RetentionCleaner.Result(1000, true),
                new RetentionCleaner.Result(1000, true),
                new RetentionCleaner.Result(1000, true),
                new RetentionCleaner.Result(250, false));
        when(cleaner.cleanPairingCodes(any())).thenReturn(new RetentionCleaner.Result(0, false));
        when(cleaner.cleanRefreshTokens(any())).thenReturn(new RetentionCleaner.Result(0, false));

        try (LogCaptor logs = new LogCaptor()) {
            new RetentionCleanupScheduler(cleaner).tick();

            verify(cleaner, times(4)).cleanPairingAttempts(any());
            assertThat(logs.messages())
                    .contains("auth.retention.cleaned table=pairing_exchange_attempts deleted=3250 rounds=4 capped=false");
        }
    }

    /** 바퀴 상한이 있어야 한 표가 스케줄러 스레드(치지직·유튜브 틱과 공유)를 독점하지 않는다. */
    @Test
    void 열_바퀴_전부_상한이면_열한_번째를_안_부른다() {
        RetentionCleaner cleaner = mock(RetentionCleaner.class);
        when(cleaner.cleanPairingAttempts(any())).thenReturn(new RetentionCleaner.Result(1000, true));
        when(cleaner.cleanPairingCodes(any())).thenReturn(new RetentionCleaner.Result(0, false));
        when(cleaner.cleanRefreshTokens(any())).thenReturn(new RetentionCleaner.Result(0, false));

        try (LogCaptor logs = new LogCaptor()) {
            new RetentionCleanupScheduler(cleaner).tick();

            verify(cleaner, times(10)).cleanPairingAttempts(any());
            assertThat(logs.messages())
                    .contains("auth.retention.cleaned table=pairing_exchange_attempts deleted=10000 rounds=10 capped=true");
        }
    }

    /**
     * 바퀴 중간에 던져도 앞 바퀴의 삭제는 <b>이미 커밋됐다</b>(각 바퀴가 자기 트랜잭션). 그래서 실패 줄이 그 실적을
     * 같이 싣는다 — 없으면 운영자가 「청소가 통째로 안 돌았다」로 읽고, 폭주 중에 표가 실제로는 줄고 있는데
     * batch-limit을 올릴 판단을 못 한다. 바퀴 반복 전에는 예외 = 삭제 0이라 그 오독이 참이었다(리뷰 라운드 2).
     */
    @Test
    void 둘째_바퀴가_던지면_failed_한_줄이고_cleaned는_없다() {
        RetentionCleaner cleaner = mock(RetentionCleaner.class);
        when(cleaner.cleanPairingAttempts(any()))
                .thenReturn(new RetentionCleaner.Result(1000, true))
                .thenThrow(new IllegalStateException("표 잠김"));
        when(cleaner.cleanPairingCodes(any())).thenReturn(new RetentionCleaner.Result(0, false));
        when(cleaner.cleanRefreshTokens(any())).thenReturn(new RetentionCleaner.Result(0, false));

        try (LogCaptor logs = new LogCaptor()) {
            new RetentionCleanupScheduler(cleaner).tick();

            verify(cleaner, times(2)).cleanPairingAttempts(any());
            assertThat(logs.messages())
                    .contains("auth.retention.failed table=pairing_exchange_attempts deleted=1000 rounds=1 "
                            + "causeType=IllegalStateException")
                    .noneMatch(m -> m.startsWith("auth.retention.cleaned table=pairing_exchange_attempts"));
        }
        verify(cleaner).cleanRefreshTokens(any());
    }

    /** 10분마다 빈 줄 셋을 안 남긴다. */
    @Test
    void 지운_것이_없으면_로그를_안_찍는다() {
        RetentionCleaner cleaner = mock(RetentionCleaner.class);
        when(cleaner.cleanPairingAttempts(any())).thenReturn(new RetentionCleaner.Result(0, false));
        when(cleaner.cleanPairingCodes(any())).thenReturn(new RetentionCleaner.Result(0, false));
        when(cleaner.cleanRefreshTokens(any())).thenReturn(new RetentionCleaner.Result(0, false));

        try (LogCaptor logs = new LogCaptor()) {
            new RetentionCleanupScheduler(cleaner).tick();

            assertThat(logs.messages()).noneMatch(m -> m.startsWith("auth.retention."));
        }
        verify(cleaner).cleanRefreshTokens(any());
    }
}
