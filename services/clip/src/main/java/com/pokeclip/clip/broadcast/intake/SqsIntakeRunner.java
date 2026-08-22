package com.pokeclip.clip.broadcast.intake;

import com.pokeclip.clip.broadcast.BroadcastEventProcessor;
import com.pokeclip.clip.broadcast.LifecycleEnvelope;
import com.pokeclip.clip.broadcast.LifecycleEventType;
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

    /**
     * 식별자 칸의 폭. <b>{@code V201__create_broadcasts_and_broadcast_events.sql}의
     * {@code VARCHAR(128)}과 같은 값이어야 한다</b> — 어긋나면 조용히 깨진다.
     * 마이그레이션에서 폭을 바꾸면 여기도 바꾼다. 대상은 셋:
     * {@code broadcasts.streamer_id} · {@code broadcast_events.event_id} ·
     * {@code broadcast_events.stream_id}.
     */
    private static final int MAX_IDENTIFIER_LENGTH = 128;

    /** chat-collector 재연결과 같은 값이다. 상한 60초면 복구가 최악 1분 늦는다. */
    private static final Duration FIRST_RETRY_DELAY = Duration.ofSeconds(1);
    private static final Duration MAX_RETRY_DELAY = Duration.ofSeconds(60);

    /** 꺼져 있으면 null이다 — 빈을 아예 등록하지 않으므로. */
    private final SqsClient sqs;
    private final IntakeProperties properties;
    private final IntakeStatus status;
    private final BroadcastEventProcessor processor;
    private final ObjectMapper mapper;
    /** null이면 안 부른다 — 리스너 없이 만드는 기존 생성자 경로(검사 넷)가 그대로 산다. */
    private final EndedListener endedListener;

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
    /**
     * 리스너도 {@code ObjectProvider}로 받는다. 필수로 받으면 러너만 올리는 얇은 컨텍스트가
     * {@code EndedListener} 빈이 없어 통째로 안 뜬다 — {@code SqsClient}와 같은 이유이고,
     * 실제로 {@code 켜진_컨텍스트에서_러너가_큐_클라이언트를_받는다}가 그렇게 깨졌다.
     */
    @Autowired
    SqsIntakeRunner(ObjectProvider<SqsClient> sqsProvider, IntakeProperties properties,
                    IntakeStatus status, BroadcastEventProcessor processor, ObjectMapper mapper,
                    ObjectProvider<EndedListener> endedListenerProvider) {
        this(sqsProvider.getIfAvailable(), properties, status, processor, mapper, null,
                endedListenerProvider.getIfAvailable());
    }

    SqsIntakeRunner(SqsClient sqs, IntakeProperties properties, IntakeStatus status,
                    BroadcastEventProcessor processor, ObjectMapper mapper) {
        this(sqs, properties, status, processor, mapper, null, null);
    }

    SqsIntakeRunner(SqsClient sqs, IntakeProperties properties, IntakeStatus status,
                    BroadcastEventProcessor processor, ObjectMapper mapper, Sleeper sleeper) {
        this(sqs, properties, status, processor, mapper, sleeper, null);
    }

    SqsIntakeRunner(SqsClient sqs, IntakeProperties properties, IntakeStatus status,
                    BroadcastEventProcessor processor, ObjectMapper mapper, Sleeper sleeper,
                    EndedListener endedListener) {
        this.sqs = sqs;
        this.properties = properties;
        this.status = status;
        this.processor = processor;
        this.mapper = mapper;
        // 기본은 종료 신호를 기다리는 실물이다. 검사만 가짜를 넣는다.
        this.sleeper = sleeper != null ? sleeper : this::awaitStop;
        this.endedListener = endedListener;
    }

    /**
     * 폴링은 웹 요청과 무관하게 돌아야 하므로 데몬 스레드 하나에 맡긴다.
     * 꺼져 있으면(클라이언트가 없으면) 시작하지 않는다.
     */
    /** 큐 클라이언트를 받았는지 — 곧 켜졌는지다. 배선이 실제로 닿았는지 검사가 여기를 본다. */
    boolean hasQueueClient() {
        return sqs != null;
    }

    /**
     * 종료 알림 리스너를 받았는지. {@link #hasQueueClient()}와 같은 이유로 있다 —
     * <b>없으면 방송 종료 알림이 통째로 죽는데 아무 시험도 안 깨진다.</b>
     *
     * <p>관통 시험이 러너를 손수 만들며 리스너를 넘기기 때문에 그 시험만으로는
     * <b>운영 배선</b>({@code @Autowired} 생성자의 {@code ObjectProvider})이 닿았는지 증명되지 않는다 —
     * 실제로 그 자리를 null로 바꿔도 177건이 전부 초록이었다(비동기 2차 감사).
     */
    boolean hasEndedListener() {
        return endedListener != null;
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

        // 칸이 빠진 봉투는 파싱을 통과한다 — 글자 칸은 null로 바인딩되기 때문이다.
        // 그대로 넘기면 저장에서 NOT NULL 위반이 나 handle_failed가 되고, 삭제되지
        // 않으므로 영구 반복이며 FIFO라 같은 그룹의 뒤 편지가 막힌다(PR #82 P2).
        // occurredAt은 다르게 아프다 — 그냥 통과해 started_at이 빈 줄을 만들고
        // 역순 도착 placeholder와 구분되지 않는다.
        //
        // 길이도 같은 자리에서 본다 — 128자를 넘는 식별자는 저장에서 거부되는데
        // 값이 안 바뀌어 재전송으로도 안 풀린다(checkIdentifier 주석 참고).
        //
        // 숫자 칸(schemaVersion·sequence)은 여기 없다. Jackson 3가 기본형에 null을
        // 매핑할 때 MismatchedInputException을 던져 이미 파싱 갈래가 막는다(실측).
        FieldRejection rejected = firstInvalidField(envelope);
        if (rejected != null) {
            log.warn("broadcast.intake.incomplete_dropped messageId={} eventId={} field={} reason={}",
                    message.messageId(), envelope.eventId(), rejected.field(), rejected.reason());
            delete(message);
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
        notifyEnded(envelope, result);
        return true;
    }

    /**
     * 방송이 정말 끝났을 때만 알린다 — DUPLICATE·IGNORED_STALE은 명부를 안 바꿨으므로
     * 알리면 붙어 있는 화면이 멀쩡한 방송에서 쫓겨난다.
     *
     * <p>삭제 뒤에 부른다. 알림 실패는 편지를 되돌리지 않는다 — 이미 지웠고 명부는 반영됐다.
     * 붙어 있는 화면은 재연결 때 {@code ended}를 받는다(알려진 구멍).
     */
    private void notifyEnded(LifecycleEnvelope envelope, ProcessResult result) {
        if (endedListener == null
                || envelope.type() != LifecycleEventType.BROADCAST_ENDED
                || result != ProcessResult.PROCESSED) {
            return;
        }
        try {
            endedListener.broadcastEnded(envelope.streamId());
        } catch (RuntimeException e) {
            log.warn("broadcast.intake.ended_listener_failed streamId={} causeType={}",
                    envelope.streamId(), e.getClass().getSimpleName());
        }
    }

    /**
     * 없으면 처리할 수 없는 칸의 이름. 다 있으면 null.
     *
     * <p>어느 칸인지 로그에 남겨야 발행 쪽(1번)이 고칠 자리를 안다 —
     * "봉투가 이상하다"만으로는 아무도 못 고친다.
     */
    private FieldRejection firstInvalidField(LifecycleEnvelope envelope) {
        FieldRejection eventId = checkIdentifier("eventId", envelope.eventId());
        if (eventId != null) {
            return eventId;
        }
        FieldRejection streamId = checkIdentifier("streamId", envelope.streamId());
        if (streamId != null) {
            return streamId;
        }
        FieldRejection streamerId = checkIdentifier("streamerId", envelope.streamerId());
        if (streamerId != null) {
            return streamerId;
        }
        if (envelope.occurredAt() == null) {
            return new FieldRejection("occurredAt", "missing");
        }
        return null;
    }

    /**
     * 없거나, 있어도 칸에 안 들어가는 식별자를 걸러낸다.
     *
     * <p><b>PostgreSQL은 긴 값을 자르지 않고 거부한다</b>
     * ({@code value too long for type character varying(128)}). 그대로 넘기면 저장에서
     * 터져 {@code handle_failed}가 되는데, 값이 안 바뀌므로 <b>재전송해도 영영 안 풀리고</b>
     * FIFO라 뒤 이벤트는 시도조차 못 한다 — 그동안 헬스체크는 큐에 닿고 있어 초록이다.
     * 러너가 "재시도로 언젠가 풀리는 실패"와 "값이 안 바뀌어 영영 안 풀리는 실패"를
     * 갈라야 하고, 후자는 빠진 칸과 같은 갈래다(PR #89 codex 지적, 재현됨).
     */
    private FieldRejection checkIdentifier(String field, String value) {
        if (value == null || value.isBlank()) {
            return new FieldRejection(field, "missing");
        }
        if (value.length() > MAX_IDENTIFIER_LENGTH) {
            return new FieldRejection(field, "too_long");
        }
        return null;
    }

    /** 어느 칸이 왜 걸렸는지. "빠졌다"와 "너무 길다"는 발행자에게 다른 신호다. */
    private record FieldRejection(String field, String reason) {
    }

    private void delete(Message message) {
        sqs.deleteMessage(DeleteMessageRequest.builder()
                .queueUrl(properties.queueUrl())
                .receiptHandle(message.receiptHandle())
                .build());
    }
}
