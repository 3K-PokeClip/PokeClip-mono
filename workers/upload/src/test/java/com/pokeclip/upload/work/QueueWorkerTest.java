package com.pokeclip.upload.work;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityRequest;
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityResponse;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageResponse;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 처리하는 동안 heartbeat를 보내고(POK-333), 끝나면 멈춘다. heartbeat가 던져도 처리 결과(쪽지 지우기)는 그대로다.
 * 처리기는 시간만 쓰는 가짜다: 여기서 재는 것은 일정이고, heartbeat 한 번의 내용은 {@code UploadProcessorTest}가 잰다.
 */
class QueueWorkerTest {

    private final AtomicInteger beats = new AtomicInteger();
    private final AtomicInteger deletes = new AtomicInteger();

    @Test
    void 처리하는_동안_heartbeat를_보내고_끝나면_멈춘다() throws Exception {
        QueueWorker worker = new QueueWorker(sqs(), "q", slowProcessor(300, false), Duration.ofSeconds(900),
                Duration.ZERO, Duration.ofMillis(50));
        try {
            assertThat(worker.pollOnce()).isTrue();
            int during = beats.get();
            assertThat(during).as("처리하는 300ms 동안 50ms마다").isGreaterThanOrEqualTo(3);
            Thread.sleep(200);
            assertThat(beats.get()).as("처리가 끝나면 멈춘다").isEqualTo(during);
            assertThat(deletes.get()).isOne();
        } finally {
            worker.stop();
        }
    }

    /** heartbeat가 던져도(그래서는 안 되지만) 처리는 끝까지 가고 쪽지는 지워진다. 일정이 멈출 뿐이다. */
    @Test
    void heartbeat가_던져도_처리_결과는_그대로다() throws Exception {
        QueueWorker worker = new QueueWorker(sqs(), "q", slowProcessor(200, true), Duration.ofSeconds(900),
                Duration.ZERO, Duration.ofMillis(30));
        try {
            assertThat(worker.pollOnce()).isTrue();
            assertThat(deletes.get()).isOne();
            assertThat(beats.get()).isPositive();
        } finally {
            worker.stop();
        }
    }

    private UploadProcessor slowProcessor(long millis, boolean beatThrows) {
        return new UploadProcessor(null, null, null, null, null, null, null, 0, List.of(), d -> { }) {
            @Override
            public void heartbeat(String body) {
                beats.incrementAndGet();
                if (beatThrows) {
                    throw new IllegalStateException("heartbeat 고장");
                }
            }

            @Override
            public Disposition process(String body) {
                try {
                    Thread.sleep(millis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return Disposition.DELETE;
            }
        };
    }

    private SqsClient sqs() {
        return new SqsClient() {
            @Override
            public ReceiveMessageResponse receiveMessage(ReceiveMessageRequest request) {
                return ReceiveMessageResponse.builder()
                        .messages(Message.builder().messageId("m1").receiptHandle("r1").body("{}").build()).build();
            }

            @Override
            public DeleteMessageResponse deleteMessage(DeleteMessageRequest request) {
                deletes.incrementAndGet();
                return DeleteMessageResponse.builder().build();
            }

            @Override
            public ChangeMessageVisibilityResponse changeMessageVisibility(ChangeMessageVisibilityRequest request) {
                return ChangeMessageVisibilityResponse.builder().build();
            }

            @Override
            public String serviceName() {
                return "sqs";
            }

            @Override
            public void close() {
            }
        };
    }
}
