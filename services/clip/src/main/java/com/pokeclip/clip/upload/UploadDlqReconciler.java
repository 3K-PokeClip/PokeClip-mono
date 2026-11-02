package com.pokeclip.clip.upload;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.sqs.model.Message;
import tools.jackson.databind.ObjectMapper;

/**
 * 실패 큐에 떨어진 업로드 주문을 닫는다. 렌더의 정리기와 다른 점이 이 카드의 핵심이다.
 *
 * <p><b>🔴 결과 불명을 「실패」로 닫지 않는다.</b> 이어 올리기 주소가 한 번이라도 적혔으면 바이트가 다 갔을 수 있다: 그러면
 * 채널에 영상이 이미 있다. 그것을 {@code failed}로 닫으면 자리가 비어 사람이 다시 누르고, 채널에 같은 영상이 둘 뜬다.
 * 그래서 주소가 있으면 {@code checking}(사람이 확인), 없으면 {@code failed}(영상이 생길 수 없었다)다.
 */
@Component
public class UploadDlqReconciler {

    private static final Logger log = LoggerFactory.getLogger(UploadDlqReconciler.class);

    private final ObjectProvider<UploadQueueClient> queue;
    private final ClipUploadRepository uploads;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;

    UploadDlqReconciler(ObjectProvider<UploadQueueClient> queue, ClipUploadRepository uploads,
                        TransactionTemplate transactions, ObjectMapper mapper) {
        this.queue = queue;
        this.uploads = uploads;
        this.transactions = transactions;
        this.mapper = mapper;
    }

    @Scheduled(fixedDelayString = "${pokeclip.upload.reconcile-interval}")
    public void tick() {
        try {
            reconcileOnce();
        } catch (Throwable t) {
            log.error("clip.upload.reconcile_tick_failed", t);
        }
    }

    /** 한 회차(최대 열 통). @return 닫은 주문 수 */
    public int reconcileOnce() {
        UploadQueueClient client = queue.getIfAvailable();
        if (client == null) {
            return 0;
        }
        int closed = 0;
        for (Message message : client.receiveDeadLetters()) {
            Long uploadId = uploadIdOf(message.body());
            if (uploadId != null && close(uploadId)) {
                closed++;
            }
            client.deleteDeadLetter(message.receiptHandle());
        }
        return closed;
    }

    private Long uploadIdOf(String body) {
        try {
            return Long.parseLong(mapper.readTree(body).path("uploadId").asString());
        } catch (RuntimeException e) {
            log.warn("clip.upload.dlq_unreadable reason={}", e.getClass().getSimpleName());
            return null;
        }
    }

    private boolean close(long uploadId) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            ClipUpload upload = uploads.findByIdForUpdate(uploadId).orElse(null);
            if (upload == null || upload.getStatus().settled()) {
                return false;
            }
            if (upload.getSessionUri() == null) {
                upload.failed("SWEPT", "주문줄에서 처리에 거듭 실패했다. 올리기를 시작하지 않았다");
            } else {
                upload.checking("SWEPT_AFTER_SESSION", "올리던 중 처리가 거듭 실패했다. 채널에서 올라갔는지 확인해야 한다");
            }
            log.warn("clip.upload.swept uploadId={} status={} attempts={}",
                    uploadId, upload.getStatus().dbValue(), upload.getAttemptOrdinal());
            return true;
        }));
    }
}
