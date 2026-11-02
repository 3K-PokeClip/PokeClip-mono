package com.pokeclip.auth.withdrawal.purge;

import com.pokeclip.auth.withdrawal.purge.PurgeJobRepository.Job;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 장부의 탈퇴 정리 알림을 보낸다(POK-256). 받을 때까지 다시 보낸다(간격 15초부터 두 배씩, 상한 1시간).
 *
 * <p>🔴 <b>자기 스레드 하나에서 돈다.</b> auth의 {@code @Scheduled}는 스레드 하나를 치지직 갱신·유튜브 점검·보관 청소가
 * 나눠 쓴다. 여기 HTTP(줄마다 최대 7초)가 그 스레드에 오르면 상대 서버가 죽은 동안 치지직 갱신이 밀린다.
 * 스케줄러 빈을 따로 등록하지 않는 이유: {@code TaskScheduler} 빈이 하나 더 생기면 Boot의 기본 스케줄러가 사라지고
 * 다른 {@code @Scheduled}가 이 스레드로 옮겨 온다.
 *
 * <p>종료 때 기다리지 않는다. 장부에 남아 있으니 다음 기동이 잇는다. 종료 유예 산수(20초)에 안 걸린다.
 */
@Component
public class PurgeDispatcher {

    private static final Logger log = LoggerFactory.getLogger(PurgeDispatcher.class);

    /** 한 바퀴에 집는 줄 수. 줄마다 최대 7초라 한 바퀴가 70초를 안 넘는다. */
    static final int BATCH = 10;
    /** 집은 줄을 미뤄 두는 시간. 한 바퀴보다 길어야 같은 줄을 두 번 안 집는다. */
    static final Duration LEASE = Duration.ofMinutes(5);
    static final Duration FIRST_BACKOFF = Duration.ofSeconds(15);
    static final Duration MAX_BACKOFF = Duration.ofHours(1);

    private final PurgeJobRepository jobs;
    private final PurgeNotifier notifier;
    private final Clock clock;
    private final ScheduledExecutorService executor;

    PurgeDispatcher(PurgeJobRepository jobs, PurgeNotifier notifier, WithdrawalPurgeProperties properties) {
        this.jobs = jobs;
        this.notifier = notifier;
        this.clock = Clock.systemUTC();
        if (properties.dispatch()) {
            this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "withdrawal-purge");
                thread.setDaemon(true);
                return thread;
            });
            long millis = properties.interval().toMillis();
            executor.scheduleWithFixedDelay(this::dispatchSafely, millis, millis, TimeUnit.MILLISECONDS);
        } else {
            this.executor = null;
        }
    }

    private void dispatchSafely() {
        try {
            dispatchOnce();
        } catch (Throwable t) {
            // 스레드가 예외로 죽으면 다음 주기가 안 돈다(scheduleWithFixedDelay 규약).
            log.warn("auth.withdrawal.purge.dispatch_failed causeType={}", t.getClass().getSimpleName());
        }
    }

    /** 한 바퀴. 시험이 직접 부른다. */
    public void dispatchOnce() {
        Instant now = clock.instant();
        for (Job job : jobs.claim(now, LEASE, BATCH)) {
            try {
                notifier.send(job);
                jobs.done(job.id(), clock.instant());
                log.info("auth.withdrawal.purge.sent userId={} target={}", job.userId(), job.target());
            } catch (Exception e) {
                Instant next = clock.instant().plus(backoff(job.attempts()));
                jobs.retryLater(job.id(), next);
                log.warn("auth.withdrawal.purge.retry userId={} target={} attempts={} causeType={}",
                        job.userId(), job.target(), job.attempts() + 1, e.getClass().getSimpleName());
            }
        }
    }

    static Duration backoff(int attempts) {
        Duration delay = FIRST_BACKOFF.multipliedBy(1L << Math.min(attempts, 20));
        return delay.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : delay;
    }

    @PreDestroy
    void stop() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }
}
