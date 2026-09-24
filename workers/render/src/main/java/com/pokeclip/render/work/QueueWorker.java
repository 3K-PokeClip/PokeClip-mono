package com.pokeclip.render.work;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 줄에서 한 번에 <b>한 통</b>만 꺼내 처리한다. 렌더는 CPU를 다 쓰는 일이라 한 일꾼이 둘을 겹쳐 돌리면 둘 다 느려진다. 
 * 늘리려면 일꾼(컨테이너)을 늘린다.
 *
 * <p>처리하는 동안 숨김 시간을 늘린다(계약1 1절): 남은 시간이 2분 아래로 내려가면 1분마다 2분으로 다시 잡는다.
 * 안 늘리면 긴 렌더 도중 메시지가 다시 보여 다른 일꾼이 같은 주문을 시작한다(그러면 STARTED가 내 토큰을 무효로 만든다).
 */
public class QueueWorker implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(QueueWorker.class);

    static final Duration EXTEND_TO = Duration.ofMinutes(2);
    static final Duration EXTEND_EVERY = Duration.ofMinutes(1);

    private final SqsClient sqs;
    private final String queueUrl;
    private final JobProcessor processor;
    private final Duration visibilityTimeout;
    private final Duration pollWait;
    private final ScheduledExecutorService keeper = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "render-visibility");
        t.setDaemon(true);
        return t;
    });

    private volatile boolean running;
    private Thread loop;

    public QueueWorker(SqsClient sqs, String queueUrl, JobProcessor processor, Duration visibilityTimeout,
                       Duration pollWait) {
        this.sqs = sqs;
        this.queueUrl = queueUrl;
        this.processor = processor;
        this.visibilityTimeout = visibilityTimeout;
        this.pollWait = pollWait;
    }

    @Override
    public void start() {
        running = true;
        loop = Thread.ofPlatform().name("render-queue").start(this::loop);
        log.info("render.worker_started queue={}", queueUrl);
    }

    @Override
    public void stop() {
        running = false;
        if (loop != null) {
            loop.interrupt();
        }
        keeper.shutdownNow();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void loop() {
        while (running) {
            try {
                pollOnce();
            } catch (SdkException e) {
                log.warn("render.receive_failed err={}", e.getMessage());
                sleep(Duration.ofSeconds(5));
            } catch (RuntimeException e) {
                log.error("render.loop_error", e);
                sleep(Duration.ofSeconds(5));
            }
        }
    }

    /** 한 통을 받아 끝까지 처리한다. 받은 게 없으면 false. */
    boolean pollOnce() {
        List<Message> messages = sqs.receiveMessage(ReceiveMessageRequest.builder()
                .queueUrl(queueUrl)
                .maxNumberOfMessages(1)
                .waitTimeSeconds((int) pollWait.toSeconds())
                .messageSystemAttributeNames(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT)
                .build()).messages();
        if (messages.isEmpty()) {
            return false;
        }
        handle(messages.getFirst());
        return true;
    }

    private void handle(Message message) {
        String receipt = message.receiptHandle();
        long firstExtendMs = Math.max(0, visibilityTimeout.minus(EXTEND_TO).toMillis());
        ScheduledFuture<?> extender = keeper.scheduleAtFixedRate(() -> extend(receipt), firstExtendMs,
                EXTEND_EVERY.toMillis(), TimeUnit.MILLISECONDS);
        Disposition disposition;
        try {
            disposition = processor.process(message.body());
        } finally {
            extender.cancel(false);
        }
        log.info("render.message_done messageId={} receiveCount={} disposition={}", message.messageId(),
                message.attributes().get(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT), disposition.kind());
        try {
            switch (disposition.kind()) {
                case DELETE -> sqs.deleteMessage(DeleteMessageRequest.builder()
                        .queueUrl(queueUrl).receiptHandle(receipt).build());
                case DELAY -> sqs.changeMessageVisibility(ChangeMessageVisibilityRequest.builder()
                        .queueUrl(queueUrl).receiptHandle(receipt)
                        .visibilityTimeout((int) disposition.delay().toSeconds()).build());
                case LEAVE -> {
                    // 숨김 시간이 끝나면 다시 온다.
                }
            }
        } catch (SdkException e) {
            // 지우기에 실패해도 다음 수신의 STARTED가 proceed:false로 치운다(계약1 4절).
            log.warn("render.disposition_failed messageId={} disposition={} err={}", message.messageId(),
                    disposition.kind(), e.getMessage());
        }
    }

    private void extend(String receipt) {
        try {
            sqs.changeMessageVisibility(ChangeMessageVisibilityRequest.builder()
                    .queueUrl(queueUrl).receiptHandle(receipt)
                    .visibilityTimeout((int) EXTEND_TO.toSeconds()).build());
        } catch (SdkException e) {
            log.warn("render.extend_failed err={}", e.getMessage());
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
