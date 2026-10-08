package com.pokeclip.clip.upload;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;

/**
 * 업로드 주문서를 줄에 싣는다: 즉시 한 번, 못 실으면 주기적으로 다시({@code RenderPublisher}와 같은 outbox).
 * 같은 주문서가 두 통 실릴 수 있다(큐가 받고 응답만 못 받은 경우): 그래도 영상은 하나다. 바이트는 표에 적힌 주소 하나로만 간다.
 */
@Component
public class UploadPublisher {

    private static final Logger log = LoggerFactory.getLogger(UploadPublisher.class);

    private final ObjectProvider<UploadQueueClient> queue;
    private final UploadProperties properties;
    private final ClipUploadRepository uploads;
    private final TransactionTemplate transactions;

    UploadPublisher(ObjectProvider<UploadQueueClient> queue, UploadProperties properties, ClipUploadRepository uploads,
                    TransactionTemplate transactions) {
        this.queue = queue;
        this.properties = properties;
        this.uploads = uploads;
        // 🔴 늘 새 트랜잭션이다(POK-291). 업로드 줄을 만든 트랜잭션의 커밋 뒤 훅(afterCommit)에서도 부르는데, 그 자리에서 기본 전파
        // (REQUIRED)로 열면 이미 커밋된 바깥 트랜잭션에 「참여」해 잠금 조회가 트랜잭션 없이 돌다 실패하고 즉시 발행이 조용히 빠진다
        // (outbox가 30초 뒤에 싣는다). 스프링 TransactionSynchronization#afterCommit 문서의 경고 그대로다.
        this.transactions = new TransactionTemplate(transactions.getTransactionManager());
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void publishNow(long uploadId) {
        publish(uploadId);
    }

    @Scheduled(fixedDelayString = "${pokeclip.upload.outbox-retry-interval}")
    public void resendUnpublished() {
        if (queue.getIfAvailable() == null) {
            return;
        }
        try {
            for (ClipUpload upload : uploads.findUnpublishedBefore(Instant.now().minus(properties.outboxRetryInterval()))) {
                publish(upload.getId());
            }
        } catch (Throwable t) {
            log.error("clip.upload.outbox_tick_failed", t);
        }
    }

    /** 줄을 잠그고 아직 미발행이면 싣는다. 즉시 발행과 outbox 회차가 겹칠 수 있어 잠근다. */
    void publish(long uploadId) {
        try {
            transactions.executeWithoutResult(status -> {
                ClipUpload upload = uploads.findByIdForUpdate(uploadId).orElseThrow();
                UploadQueueClient client = queue.getIfAvailable();
                if (upload.getPublishedAt() != null || client == null) {
                    return;
                }
                String messageId = client.send(upload.getPayload());
                upload.published();
                log.info("clip.upload.published uploadId={} clipId={} messageId={}", uploadId, upload.getClipId(), messageId);
            });
        } catch (RuntimeException e) {
            log.warn("clip.upload.publish_failed uploadId={} reason={}", uploadId, e.getClass().getSimpleName());
        }
    }
}
