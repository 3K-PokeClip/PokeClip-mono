package com.pokeclip.clip.upload;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 업로드 줄을 만든 트랜잭션의 커밋 뒤 발행을 <b>요청 스레드가 아닌 전용 스레드</b>에서 돈다(POK-291 로컬 리뷰 1라운드 중대).
 *
 * <p>왜 커밋 뒤 훅 안에서 직접 싣지 않나: JpaTransactionManager는 커밋 뒤 훅을 다 돌린 뒤에야 커넥션을 풀에 돌려준다.
 * 그 안에서 발행 트랜잭션을 새로 열면 원 커넥션을 쥔 채 두 번째 커넥션을 기다린다. 풀 크기(기본 10)만큼 겹치면 서로 두 번째를
 * 기다리며 풀 시한(30초) 동안 clip 전체가 멈춘다. SQS가 느리면 그 절반으로도 바닥난다(발행이 SQS 왕복 동안 커넥션 둘을 쥔다).
 * auth {@code ChzzkCleanupExecutor}가 실측한 것과 같은 모양이다. 그래서 훅 안에서는 <b>제출만</b> 한다.
 *
 * <p>대가와 그 처리: ① 줄이 차면 버리고 WARN {@code clip.upload.publish_rejected}을 남긴다. 업로드 줄은 이미 커밋됐고 outbox가
 * 다시 싣는다(그만큼 늦을 뿐이다) ② 종료 때 대기 중 발행을 짧게 기다린다. 못 끝낸 것도 outbox가 싣는다 ③ 발행이 SQS 왕복(최대 30초)을
 * 기다리므로 스레드 둘이다. 동시에 쥐는 커넥션도 많아야 둘이다.
 */
@Component
public class UploadPublishExecutor {

    private static final Logger log = LoggerFactory.getLogger(UploadPublishExecutor.class);

    static final int THREADS = 2;
    static final int QUEUE_CAPACITY = 1000;
    static final Duration SHUTDOWN_WAIT = Duration.ofSeconds(10);

    private final UploadPublisher publisher;
    private final ThreadPoolExecutor pool;
    /** 제출·완료 수. awaitIdle이 큐·활성 수 대신 이것을 본다: 꺼낸 뒤 돌기 전의 틈에서는 둘 다 0으로 보인다. */
    private final AtomicLong submitted = new AtomicLong();
    private final AtomicLong finished = new AtomicLong();

    UploadPublishExecutor(UploadPublisher publisher) {
        this.publisher = publisher;
        AtomicInteger seq = new AtomicInteger();
        this.pool = new ThreadPoolExecutor(THREADS, THREADS, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(QUEUE_CAPACITY),
                r -> {
                    Thread t = new Thread(r, "upload-publish-" + seq.incrementAndGet());
                    // 종료 유예 동안 대기 중 발행을 끝내려면 데몬이 아니어야 한다.
                    t.setDaemon(false);
                    return t;
                },
                (r, executor) -> {
                    log.warn("clip.upload.publish_rejected uploadId={} reason={}",
                            r instanceof Job job ? job.uploadId : null, executor.isShutdown() ? "shutdown" : "queue_full");
                    // 버림도 「끝」이다. 안 세면 awaitIdle이 영영 기다린다.
                    finished.incrementAndGet();
                });
    }

    /**
     * 지금 트랜잭션이 커밋되면 그 업로드 줄의 발행을 전용 스레드에 제출한다. 롤백되면 아무것도 안 한다. 트랜잭션 밖에서 부르면
     * {@code IllegalStateException}이다(부른 쪽이 트랜잭션 안이라는 약속을 어긴 것을 잡아 준다).
     */
    public void publishAfterCommit(long uploadId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                submit(uploadId);
            }
        });
    }

    void submit(long uploadId) {
        submitted.incrementAndGet();
        pool.execute(new Job(uploadId));
    }

    /** 제출한 것이 전부 끝날(버림 포함) 때까지 기다린다. 시험이 「실렸다」를 재기 전에 부른다. */
    public boolean awaitIdle(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (finished.get() < submitted.get()) {
            if (System.nanoTime() > deadline) {
                return false;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    @PreDestroy
    void shutdown() {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(SHUTDOWN_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("clip.upload.publish_shutdown_timeout pending={}", pool.getQueue().size() + pool.getActiveCount());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private final class Job implements Runnable {
        private final long uploadId;

        private Job(long uploadId) {
            this.uploadId = uploadId;
        }

        @Override
        public void run() {
            try {
                // 실패는 publish가 삼켜 로그로 남긴다(outbox가 다시 싣는다). 여기서 더 잡는 것은 스레드를 지키려는 것이다.
                publisher.publishNow(uploadId);
            } catch (RuntimeException e) {
                log.warn("clip.upload.publish_failed uploadId={} reason={}", uploadId, e.getClass().getSimpleName());
            } finally {
                finished.incrementAndGet();
            }
        }
    }
}
