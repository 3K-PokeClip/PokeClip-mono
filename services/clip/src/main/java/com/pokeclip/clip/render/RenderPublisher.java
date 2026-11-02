package com.pokeclip.clip.render;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 주문서를 큐에 싣는다 — 즉시 한 번, 못 실으면 주기적으로 다시(계약1 4절 outbox).
 *
 * <p><b>재전송은 같은 jobId·같은 본문이다.</b> 새 주문을 만들지 않는다. 그래서 큐가 실은 뒤 응답만 못 받은 경우
 * 같은 주문서가 두 통 실릴 수 있는데, 그것은 SQS의 at-least-once와 같은 모양이라 일꾼의 STARTED 판정(토큰)이 거른다.
 */
@Component
public class RenderPublisher {

    private static final Logger log = LoggerFactory.getLogger(RenderPublisher.class);

    private final ObjectProvider<RenderQueueClient> queue;
    private final RenderProperties properties;
    private final RenderJobRepository jobs;
    private final TransactionTemplate transactions;

    RenderPublisher(ObjectProvider<RenderQueueClient> queue, RenderProperties properties,
                    RenderJobRepository jobs, TransactionTemplate transactions) {
        this.queue = queue;
        this.properties = properties;
        this.jobs = jobs;
        this.transactions = transactions;
    }

    /** 방금 커밋된 주문을 싣는다. 실패는 로그로만 — 부르는 쪽은 이미 201을 돌려줄 참이고 outbox가 이어받는다. */
    public void publishNow(long clipId) {
        jobs.findByClipId(clipId).ifPresent(job -> publish(job.getId()));
    }

    /**
     * 못 실은 주문을 다시 보낸다. 방금 만든 것(첫 발행이 진행 중일 수 있음)은 {@code outboxRetryInterval}만큼 기다린다.
     * 꺼져 있으면 아무것도 안 한다 — 큐 클라이언트가 없다.
     */
    @Scheduled(fixedDelayString = "${pokeclip.render.outbox-retry-interval}")
    public void resendUnpublished() {
        if (queue.getIfAvailable() == null) {
            return;
        }
        try {
            List<RenderJob> pending = jobs.findUnpublishedBefore(Instant.now().minus(properties.outboxRetryInterval()));
            for (RenderJob job : pending) {
                publish(job.getId());
            }
        } catch (Throwable t) {
            // 스케줄러 스레드가 죽으면 다음 회차가 없다.
            log.error("clip.render.outbox_tick_failed", t);
        }
    }

    /** 잡을 잠그고 아직 미발행이면 싣고 발행 시각을 적는다. 잠그는 이유는 즉시 발행과 outbox 회차가 겹칠 수 있어서다. */
    void publish(UUID jobId) {
        try {
            transactions.executeWithoutResult(status -> {
                RenderJob job = jobs.findByIdForUpdate(jobId).orElseThrow();
                if (job.getPublishedAt() != null) {
                    return;
                }
                RenderQueueClient client = queue.getIfAvailable();
                if (client == null) {
                    return;
                }
                String messageId = client.send(job.getPayload());
                job.published();
                log.info("clip.render.published jobId={} clipId={} messageId={}", jobId, job.getClipId(), messageId);
            });
        } catch (RuntimeException e) {
            // 큐 왕복 실패. 줄은 미발행으로 남고 다음 회차가 다시 보낸다.
            log.warn("clip.render.publish_failed jobId={} reason={}", jobId, e.getClass().getSimpleName());
        }
    }
}
