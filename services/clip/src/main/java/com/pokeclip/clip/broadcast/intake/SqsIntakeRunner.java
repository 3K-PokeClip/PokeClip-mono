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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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

    /** chat-collector 재연결과 같은 값이다. 상한 60초면 복구가 최악 1분 늦는다. */
    private static final Duration FIRST_RETRY_DELAY = Duration.ofSeconds(1);
    private static final Duration MAX_RETRY_DELAY = Duration.ofSeconds(60);

    /** 꺼져 있으면 null이다 — 빈을 아예 등록하지 않으므로. */
    private final SqsClient sqs;
    private final IntakeProperties properties;
    private final IntakeStatus status;
    private final BroadcastEventProcessor processor;
    private final ObjectMapper mapper;

    private final PollBackoff backoff = new PollBackoff(FIRST_RETRY_DELAY, MAX_RETRY_DELAY);
    private final Sleeper sleeper;
    /** 자는 도중에도 종료에 반응하려면 신호가 필요하다 — Thread.sleep은 못 깬다. */
    private final CountDownLatch stopSignal = new CountDownLatch(1);

    private volatile boolean running = true;
    private Thread loop;

    /**
     * 자는 동작. 검사가 <b>실제로 자지 않고</b> "얼마나 자라고 했는지"를 재려면
     * 주입 가능해야 한다 — 시간에 기대는 검사는 간헐 실패를 부른다.
     */
    @FunctionalInterface
    interface Sleeper {
        /** @return 종료 신호로 깼으면 true — 루프를 끝낸다 */
        boolean sleepOrStop(Duration duration);
    }

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
        this(sqs, properties, status, processor, mapper, null);
    }

    SqsIntakeRunner(SqsClient sqs, IntakeProperties properties, IntakeStatus status,
                    BroadcastEventProcessor processor, ObjectMapper mapper, Sleeper sleeper) {
        this.sqs = sqs;
        this.properties = properties;
        this.status = status;
        this.processor = processor;
        this.mapper = mapper;
        // 기본은 종료 신호를 기다리는 실물이다. 검사만 가짜를 넣는다.
        this.sleeper = sleeper != null ? sleeper : this::awaitStop;
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
        // 여기서 알린다 — 빈 생성 시점이 아니라 실제로 도는 시점이어야 health가
        // "기동 중"과 "돌다가 멈춤"을 가를 수 있다.
        status.loopStarted(Instant.now());
        loop = Thread.ofPlatform().daemon().name("broadcast-intake").start(this::runLoop);
    }

    /**
     * 루프 본체. <b>실패했을 때만 쉰다</b> — 성공하면 롱폴링이 이미 대기 역할을 하므로
     * 곧바로 다음 회차로 간다. 성공하면 간격을 처음으로 되돌린다: 안 되돌리면 한 번
     * 흔들린 뒤로 영영 60초마다 한 번씩만 꺼내게 된다.
     */
    void runLoop() {
        int consecutiveFailures = 0;
        while (running) {
            if (pollOnce()) {
                consecutiveFailures = 0;
                continue;
            }
            consecutiveFailures++;
            if (sleeper.sleepOrStop(backoff.delayFor(consecutiveFailures))) {
                return;
            }
        }
    }

    /**
     * 종료 신호가 오거나 시간이 찰 때까지 기다린다. {@code Thread.sleep}을 쓰면
     * 종료가 백오프만큼 늦어진다 — stop()은 25초만 기다리는데 백오프는 60초까지 간다.
     *
     * @return 종료 신호로 깼으면 true
     */
    boolean awaitStop(Duration duration) {
        try {
            return stopSignal.await(duration.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // 인터럽트도 멈추라는 뜻이다.
            return true;
        }
    }

    @PreDestroy
    void stop() throws InterruptedException {
        running = false;
        // 백오프로 자고 있는 회차를 깨운다. 안 깨우면 종료가 최대 60초 늦는다.
        stopSignal.countDown();
        if (loop != null) {
            // 마지막 회차가 편지를 처리하는 중일 수 있다. 기다리지 않고 죽이면
            // 처리는 됐는데 삭제를 못 한 편지가 생겨 재전송으로 다시 온다.
            loop.join(SHUTDOWN_GRACE.toMillis());
        }
    }

    /**
     * 한 회차. 루프는 runLoop()가 돌린다 — 대부분의 검사는 이 메서드만 부른다.
     *
     * @return 큐에 닿았으면 true. 루프가 백오프를 걸지 정하는 값이다
     */
    boolean pollOnce() {
        try {
            ReceiveMessageResponse response = sqs.receiveMessage(ReceiveMessageRequest.builder()
                    .queueUrl(properties.queueUrl())
                    .maxNumberOfMessages(properties.maxMessages())
                    .waitTimeSeconds((int) properties.waitTime().toSeconds())
                    .build());

            for (Message message : response.messages()) {
                if (!handle(message)) {
                    // 못 지운 편지가 나왔다. 계속 돌면 같은 그룹의 뒤 편지가 명부를
                    // 앞질러 lastSequence를 올리고, 재전송된 앞 편지가 IGNORED_STALE로
                    // 걸러진 뒤 "더 볼 일 없음"으로 삭제된다 — 큐가 비고 편지 기록이
                    // 남아 재전송으로도 못 고치는 영구 유실이다(PR #82 P1).
                    //
                    // 그룹만 건너뛰는 방법도 있으나(MessageGroupId를 시스템 속성으로
                    // 받아 그룹별 상태를 둔다) 회차 중단을 골랐다: 정확성은 같고,
                    // FIFO 배치는 보통 소수 그룹이라 이득이 작은 반면 상태가 하나 는다.
                    // 안 지운 것들은 가시성 타임아웃 뒤 같은 순서로 다시 온다.
                    break;
                }
            }
            status.pollSucceeded(Instant.now());
            return true;
        } catch (RuntimeException e) {
            // 큐에 못 닿는 것은 우리가 고칠 수 없다. 예외를 밖으로 던지면 루프가 죽으므로
            // 여기서 삼키고, health가 DOWN으로 드러낸다. 루프는 반환값을 보고 쉰다.
            log.warn("broadcast.intake.poll_failed reason={}", e.getClass().getSimpleName(), e);
            status.pollFailed(e.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * 편지 하나를 처리한다.
     *
     * @return 이 회차를 계속해도 되면 true. <b>false는 "이 편지를 못 지웠다"</b>는
     *         뜻이고, 그때는 뒤 편지를 건드리면 안 된다 — 위 for 루프 주석 참고
     */
    private boolean handle(Message message) {
        LifecycleEnvelope envelope;
        try {
            envelope = mapper.readValue(message.body(), LifecycleEnvelope.class);
        } catch (Exception e) {
            // 재시도해도 계속 실패한다. 안 지우면 이 편지가 큐 앞을 영원히 막는다
            // (FIFO는 같은 그룹의 뒤 메시지를 못 넘어간다).
            log.warn("broadcast.intake.unreadable_dropped messageId={} reason={}",
                    message.messageId(), e.getClass().getSimpleName());
            delete(message);
            // 지웠으므로 큐 앞을 막지 않는다 — 중단 사유가 아니다.
            return true;
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
            return true;
        }

        ProcessResult result;
        try {
            result = processor.process(envelope);
        } catch (RuntimeException e) {
            // 지우지 않는다 — 가시성 타임아웃이 지나면 다시 온다. 멱등이라 안전하다.
            log.warn("broadcast.intake.handle_failed eventId={} reason={}",
                    envelope.eventId(), e.getClass().getSimpleName(), e);
            // 이 편지가 큐에 남는다. 뒤 편지를 처리하면 순서가 뒤집힌다.
            return false;
        }

        // 삭제를 try 밖으로 뺐다. 안에 두면 "처리는 됐는데 삭제가 실패"까지
        // handle_failed로 남아 로그가 원인을 반대로 가리킨다 — 나중에 이 줄을 보는
        // 사람이 처리 쪽을 뒤진다(감사 2차 지적). 삭제 실패는 큐에 못 닿는 것이므로
        // pollOnce의 catch가 poll_failed로 받는 것이 맞다.
        // PROCESSED · DUPLICATE · IGNORED_STALE 셋 다 "더 볼 일 없음"이다.
        log.info("broadcast.intake.handled eventId={} result={}", envelope.eventId(), result);
        delete(message);
        return true;
    }

    private void delete(Message message) {
        sqs.deleteMessage(DeleteMessageRequest.builder()
                .queueUrl(properties.queueUrl())
                .receiptHandle(message.receiptHandle())
                .build());
    }
}
