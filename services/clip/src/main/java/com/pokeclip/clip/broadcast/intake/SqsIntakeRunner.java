package com.pokeclip.clip.broadcast.intake;

import com.pokeclip.clip.broadcast.BroadcastEventProcessor;
import com.pokeclip.clip.broadcast.LifecycleEnvelope;
import com.pokeclip.clip.broadcast.ProcessResult;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;

/**
 * 큐에서 생명주기 편지를 꺼내 프로세서에 넘기고, <b>더 볼 일이 없어진 것만</b> 지운다.
 *
 * <p>지우는 기준이 "성공"이 아니라 "더 볼 일 없음"이다 — 중복·낡은 편지도 다시 받을
 * 이유가 없으므로 지운다. 반대로 <b>처리가 예외로 끝나면 안 지운다</b>. 가시성
 * 타임아웃이 지나면 큐가 다시 주고, 처리가 멱등이라 두 번 와도 안전하다.
 */
@Component
class SqsIntakeRunner {

    private static final Logger log = LoggerFactory.getLogger(SqsIntakeRunner.class);

    /** 종료 시 마지막 회차가 끝나기를 기다리는 시간. 롱폴링 20초에 여유를 더했다. */
    private static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(25);

    /** 꺼져 있으면 null이다 — 빈을 아예 등록하지 않으므로. */
    private final SqsClient sqs;
    private final IntakeProperties properties;
    private final IntakeStatus status;
    private final BroadcastEventProcessor processor;
    private final ObjectMapper mapper;

    private volatile boolean running;
    private Thread loop;

    /**
     * 생성자가 둘이면 Spring은 가시성으로 가르지 않는다 — 어느 것을 쓸지 못 박는다.
     *
     * <p>{@code ObjectProvider}로 받는 이유: {@code Optional<SqsClient>}를 주입받으면
     * Spring이 그 지점을 "SqsClient 빈을 optional로 찾기"로 가로채, 빈이 실제로 있어도
     * 껍데기만 오는 함정이 있다(IntakeConfiguration 주석 참고).
     */
    @Autowired
    SqsIntakeRunner(ObjectProvider<SqsClient> sqsProvider, IntakeProperties properties,
                    IntakeStatus status, BroadcastEventProcessor processor, ObjectMapper mapper) {
        this(sqsProvider.getIfAvailable(), properties, status, processor, mapper);
    }

    SqsIntakeRunner(SqsClient sqs, IntakeProperties properties, IntakeStatus status,
                    BroadcastEventProcessor processor, ObjectMapper mapper) {
        this.sqs = sqs;
        this.properties = properties;
        this.status = status;
        this.processor = processor;
        this.mapper = mapper;
    }

    /**
     * 폴링은 웹 요청과 무관하게 돌아야 하므로 데몬 스레드 하나에 맡긴다.
     * 꺼져 있으면(클라이언트가 없으면) 시작하지 않는다.
     */
    /** 큐 클라이언트를 받았는지 — 곧 켜졌는지다. 배선이 실제로 닿았는지 검사가 여기를 본다. */
    boolean hasQueueClient() {
        return sqs != null;
    }

    @EventListener(ApplicationReadyEvent.class)
    void startLoop() {
        if (!hasQueueClient()) {
            log.info("broadcast.intake.disabled");
            return;
        }
        running = true;
        loop = Thread.ofPlatform().daemon().name("broadcast-intake").start(() -> {
            while (running) {
                pollOnce();
            }
        });
    }

    @PreDestroy
    void stop() throws InterruptedException {
        running = false;
        if (loop != null) {
            // 마지막 회차가 편지를 처리하는 중일 수 있다. 기다리지 않고 죽이면
            // 처리는 됐는데 삭제를 못 한 편지가 생겨 재전송으로 다시 온다.
            loop.join(SHUTDOWN_GRACE.toMillis());
        }
    }

    /** 한 회차. 루프는 startLoop()가 돌린다 — 테스트는 이 메서드만 부른다. */
    void pollOnce() {
        try {
            ReceiveMessageResponse response = sqs.receiveMessage(ReceiveMessageRequest.builder()
                    .queueUrl(properties.queueUrl())
                    .maxNumberOfMessages(properties.maxMessages())
                    .waitTimeSeconds((int) properties.waitTime().toSeconds())
                    .build());

            for (Message message : response.messages()) {
                handle(message);
            }
            status.pollSucceeded(Instant.now());
        } catch (RuntimeException e) {
            // 큐에 못 닿는 것은 우리가 고칠 수 없다. 예외를 밖으로 던지면 루프가 죽으므로
            // 여기서 삼키고, health가 DOWN으로 드러낸다.
            log.warn("broadcast.intake.poll_failed reason={}", e.getClass().getSimpleName(), e);
            status.pollFailed(e.getClass().getSimpleName());
        }
    }

    private void handle(Message message) {
        LifecycleEnvelope envelope;
        try {
            envelope = mapper.readValue(message.body(), LifecycleEnvelope.class);
        } catch (Exception e) {
            // 재시도해도 계속 실패한다. 안 지우면 이 편지가 큐 앞을 영원히 막는다
            // (FIFO는 같은 그룹의 뒤 메시지를 못 넘어간다).
            log.warn("broadcast.intake.unreadable_dropped messageId={} reason={}",
                    message.messageId(), e.getClass().getSimpleName());
            delete(message);
            return;
        }

        // 모르는 종류는 재시도해도 계속 모른다. 안 지우면 FIFO 같은 그룹의 뒤 편지가
        // 영원히 못 넘어온다 — 줄이 막히는 피해가 소식 하나를 놓치는 것보다 크다.
        //
        // LifecycleEventType.from은 계속 던진다(태스크 2의 "모르는 종류는 거부한다").
        // 그것을 재시도 불가로 <b>분류</b>하는 것이 러너의 판단이고, 그 자리가 여기다.
        // 로그 키를 파싱 실패와 나눈 이유: 이쪽은 1번이 새 이벤트를 냈다는 신호라
        // "형식이 깨졌다"와 섞이면 안 된다.
        try {
            envelope.type();
        } catch (IllegalArgumentException e) {
            log.warn("broadcast.intake.unknown_type_dropped messageId={} eventId={} eventType={}",
                    message.messageId(), envelope.eventId(), envelope.eventType());
            delete(message);
            return;
        }

        ProcessResult result;
        try {
            result = processor.process(envelope);
        } catch (RuntimeException e) {
            // 지우지 않는다 — 가시성 타임아웃이 지나면 다시 온다. 멱등이라 안전하다.
            log.warn("broadcast.intake.handle_failed eventId={} reason={}",
                    envelope.eventId(), e.getClass().getSimpleName(), e);
            return;
        }

        // 삭제를 try 밖으로 뺐다. 안에 두면 "처리는 됐는데 삭제가 실패"까지
        // handle_failed로 남아 로그가 원인을 반대로 가리킨다 — 나중에 이 줄을 보는
        // 사람이 처리 쪽을 뒤진다(감사 2차 지적). 삭제 실패는 큐에 못 닿는 것이므로
        // pollOnce의 catch가 poll_failed로 받는 것이 맞다.
        // PROCESSED · DUPLICATE · IGNORED_STALE 셋 다 "더 볼 일 없음"이다.
        log.info("broadcast.intake.handled eventId={} result={}", envelope.eventId(), result);
        delete(message);
    }

    private void delete(Message message) {
        sqs.deleteMessage(DeleteMessageRequest.builder()
                .queueUrl(properties.queueUrl())
                .receiptHandle(message.receiptHandle())
                .build());
    }
}
