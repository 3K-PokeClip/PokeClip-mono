package com.pokeclip.clip.render;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.sqs.model.Message;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 실패 큐(DLQ)에 떨어진 주문을 종국 실패로 정리한다(POK-147, 계약1 4절 Reconciler). 재시도를 다 쓴 주문은 아무도 안
 * 만져 화면에 「만드는 중」이 영원히 남는다 — 그것을 {@code SWEPT}로 닫는다.
 *
 * <p><b>{@code SWEPT}는 열린 주문에만 쓴다(CAS).</b> 이미 끝난(성공·실패) 주문의 쪽지는 상태를 안 바꾸고 지우기만 한다 —
 * 성공이 「실패, 재요청」으로 덮이면 안 된다. 쪽지를 못 읽는(jobId 없음) 경우도 지운다: 남겨 두면 매 회차 같은 쪽지를 읽는다.
 */
@Component
public class DlqReconciler {

    private static final Logger log = LoggerFactory.getLogger(DlqReconciler.class);

    private final ObjectProvider<RenderQueueClient> queue;
    private final RenderJobRepository jobs;
    private final ClipRepository clips;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;

    DlqReconciler(ObjectProvider<RenderQueueClient> queue, RenderJobRepository jobs, ClipRepository clips,
                  TransactionTemplate transactions, ObjectMapper mapper) {
        this.queue = queue;
        this.jobs = jobs;
        this.clips = clips;
        this.transactions = transactions;
        this.mapper = mapper;
    }

    @Scheduled(fixedDelayString = "${pokeclip.render.reconcile-interval}")
    public void tick() {
        try {
            reconcileOnce();
        } catch (Throwable t) {
            log.error("clip.render.reconcile_tick_failed", t);
        }
    }

    /** 한 회차 — 최대 열 통. @return 정리한(SWEPT로 닫은) 주문 수 */
    public int reconcileOnce() {
        RenderQueueClient client = queue.getIfAvailable();
        if (client == null) {
            return 0;
        }
        List<Message> dead = client.receiveDeadLetters();
        int swept = 0;
        for (Message message : dead) {
            UUID jobId = jobIdOf(message.body());
            if (jobId != null && sweep(jobId)) {
                swept++;
            }
            client.deleteDeadLetter(message.receiptHandle());
        }
        return swept;
    }

    private UUID jobIdOf(String body) {
        try {
            return UUID.fromString(mapper.readTree(body).path("jobId").asString());
        } catch (RuntimeException e) {
            log.warn("clip.render.dlq_unreadable reason={}", e.getClass().getSimpleName());
            return null;
        }
    }

    /** @return 실제로 닫았으면 true. 모르는 잡·이미 끝난 잡은 false(멱등 소비) */
    private boolean sweep(UUID jobId) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            RenderJob job = jobs.findByIdForUpdate(jobId).orElse(null);
            if (job == null || job.getStatus().terminal()) {
                return false;
            }
            job.failed(Instant.now());
            clips.findByIdForUpdate(job.getClipId()).ifPresent(c -> c.failed("SWEPT", "주문줄에서 처리에 거듭 실패했다"));
            log.warn("clip.render.swept jobId={} clipId={} attempts={}", jobId, job.getClipId(), job.getAttemptOrdinal());
            return true;
        }));
    }
}
