package com.pokeclip.chat.collector.broadcast.intake;

import com.pokeclip.chat.collector.broadcast.BroadcastEventProcessor;
import com.pokeclip.chat.collector.broadcast.LifecycleEnvelope;
import com.pokeclip.chat.collector.broadcast.ProcessResult;
import com.pokeclip.chat.collector.reconnect.ReconnectPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 큐에서 방송 생명주기 편지를 꺼내 판정기에 넘기고, <b>더 볼 일이 없어진 것만</b> 지운다.
 *
 * <p>지우는 기준이 「성공」이 아니라 「더 볼 일 없음」이다 — 낡은 편지도, 우리가 못 읽는
 * 편지도 다시 받을 이유가 없으므로 지운다. 반대로 {@code RETRY_LATER}와 <b>판정이 예외로
 * 끝난 것</b>은 안 지운다. 가시성 타임아웃이 지나면 큐가 다시 주고, 판정이 멱등이라
 * 두 번 와도 안전하다.
 *
 * <p><b>이 부품은 아직 스프링 빈이 아니다.</b> 조립은 태스크 10이 한다 —
 * {@code BroadcastStarter}의 실물이 없어 {@code BroadcastEventProcessor}를 지금 빈으로
 * 올리면 컨텍스트가 죽고, 가짜 구현으로 때우면 「켜도 방송이 하나도 안 열리는데 health는
 * 초록」이 된다. 이 서버의 조립 관례도 같은 자리다 — {@code CollectorRunner}·
 * {@code EndedStreamSweeper} 둘 다 {@code @Component}가 아니라
 * {@code CollectorApplication}의 {@code @Bean}이 만든다.
 *
 * <p><b>태스크 10이 붙일 것 셋:</b> ① {@code @Bean} 등록({@code ObjectProvider<SqsClient>}로
 * 받는다 — {@code IntakeConfiguration} 주석) · ② 부팅이 끝나면 {@link #runLoop()}를 데몬
 * 스레드에서 시작 · ③ 종료 때 {@link #stop()}을 부르고 그 스레드를 join. 스레드 수명을
 * 여기서 안 정하는 이유는 <b>종료 유예 예산이 태스크 11에서 정해지기 때문</b>이다 —
 * 세션 여럿을 닫는 시간과 마지막 회차를 기다리는 시간이 같은 20초를 나눠 쓴다.
 */
public class SqsIntakeRunner {

    private static final Logger log = LoggerFactory.getLogger(SqsIntakeRunner.class);

    /** 재연결·마이그레이션 재시도와 같은 값이다. 상한 60초면 복구가 최악 1분 늦는다. */
    static final Duration FIRST_RETRY_DELAY = Duration.ofSeconds(1);
    static final Duration MAX_RETRY_DELAY = Duration.ofSeconds(60);

    private final SqsClient sqs;
    private final IntakeProperties properties;
    private final IntakeStatus status;
    private final BroadcastEventProcessor processor;
    private final ObjectMapper mapper;

    /**
     * 재연결과 같은 백오프를 그대로 쓴다. 이 서버에 이미 있는 것을 복사하면 한쪽만 고쳐져
     * 갈라진다 — {@code ChatPersister}/{@code ChatArchiver} 쌍둥이에서 실제로 그랬다.
     */
    private final ReconnectPolicy backoff = new ReconnectPolicy(FIRST_RETRY_DELAY, MAX_RETRY_DELAY);
    private final Sleeper sleeper;
    /** 자는 도중에도 종료에 반응하려면 신호가 필요하다 — {@code Thread.sleep}은 못 깬다. */
    private final CountDownLatch stopSignal = new CountDownLatch(1);

    private volatile boolean running = true;

    /**
     * 자는 동작. 검사가 <b>실제로 자지 않고</b> 「얼마나 자라고 했는지」를 재려면 주입
     * 가능해야 한다 — 시간에 기대는 검사는 간헐 실패를 부른다.
     */
    @FunctionalInterface
    public interface Sleeper {
        /** @return 종료 신호로 깼으면 true — 루프를 끝낸다 */
        boolean sleepOrStop(Duration duration);
    }

    public SqsIntakeRunner(SqsClient sqs, IntakeProperties properties, IntakeStatus status,
                           BroadcastEventProcessor processor, ObjectMapper mapper) {
        this(sqs, properties, status, processor, mapper, null);
    }

    /**
     * @param sqs <b>null을 거부한다.</b> 꺼져 있으면 이 부품을 아예 만들지 않는 것이 맞고,
     *            켜졌는데 null이면 주입이 잘못된 것이다({@code Optional} 함정). 껍데기로
     *            받아 조용히 안 도는 것보다 부팅에서 죽는 편이 낫다
     * @param sleeper null이면 종료 신호를 기다리는 실물. 검사만 가짜를 넣는다
     */
    public SqsIntakeRunner(SqsClient sqs, IntakeProperties properties, IntakeStatus status,
                           BroadcastEventProcessor processor, ObjectMapper mapper, Sleeper sleeper) {
        this.sqs = Objects.requireNonNull(sqs, "SqsClient가 없다 — 켜져 있는데 주입이 안 됐다");
        this.properties = properties;
        this.status = status;
        this.processor = processor;
        this.mapper = mapper;
        this.sleeper = sleeper != null ? sleeper : this::awaitStop;
    }

    /**
     * 루프 본체. <b>실패했을 때만 쉰다</b> — 성공하면 롱폴링이 이미 대기 역할을 하므로 곧바로
     * 다음 회차로 간다. 성공하면 간격을 처음으로 되돌린다: 안 되돌리면 한 번 흔들린 뒤로
     * 영영 60초에 한 번씩만 꺼내게 된다.
     */
    public void runLoop() {
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
     * 종료 신호가 오거나 시간이 찰 때까지 기다린다. {@code Thread.sleep}을 쓰면 종료가
     * 백오프만큼 늦어진다 — 이 서버의 종료 유예는 20초인데 백오프는 60초까지 간다.
     *
     * @return 종료 신호로 깼으면 true
     */
    boolean awaitStop(Duration duration) {
        try {
            return stopSignal.await(duration.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return true;   // 인터럽트도 멈추라는 뜻이다
        }
    }

    /** 루프를 멈춘다. 자고 있는 회차도 깨운다 — 안 깨우면 종료가 최대 60초 늦는다. */
    public void stop() {
        running = false;
        stopSignal.countDown();
    }

    /**
     * 한 회차. 대부분의 검사는 이 메서드만 부른다.
     *
     * @return 큐에 닿았으면 true. 루프가 백오프를 걸지 정하는 값이다
     */
    public boolean pollOnce() {
        try {
            ReceiveMessageResponse response = sqs.receiveMessage(ReceiveMessageRequest.builder()
                    .queueUrl(properties.queueUrl())
                    .maxNumberOfMessages(properties.maxMessages())
                    .waitTimeSeconds((int) properties.waitTime().toSeconds())
                    .build());

            for (Message message : response.messages()) {
                if (!handle(message)) {
                    // 못 지운 편지가 나왔다. 계속 돌면 같은 방송의 뒤 편지가 앞질러 처리돼
                    // 메모의 lastSequence를 올리고, 재전송된 앞 편지가 IGNORED_STALE로
                    // 걸러진 뒤 「더 볼 일 없음」으로 삭제된다 — 재전송으로도 못 고치는
                    // 영구 유실이다(clip PR #82 P1).
                    //
                    // 규칙은 clip과 같지만 <b>근거는 다르다.</b> clip은 "FIFO 배치가 보통
                    // 소수 그룹이라 회차 중단의 비용이 작다"고 했는데, 우리 쪽
                    // MessageGroupId는 방송별(streamId)이라 그룹이 스트리머 수보다 많다 —
                    // 멀쩡한 남의 방송이 같이 미뤄지므로 비용이 clip보다 크다.
                    // 그래도 중단을 고르는 이유는 <b>유실이 지연보다 비싸기 때문</b>이고,
                    // 지연 폭은 큐의 가시성 타임아웃 한 번이다(1번 확인 전, 30초로 가정).
                    // 그 가정이 크게 틀리면(예: 몇 분) 그룹만 건너뛰는 쪽을 다시 본다.
                    break;
                }
            }
            status.pollSucceeded(Instant.now());
            return true;
        } catch (RuntimeException e) {
            // 큐에 못 닿는 것은 우리가 고칠 수 없다. 예외를 밖으로 던지면 루프가 죽어
            // 편지를 영영 못 받는다 — 서버는 UP인 채로. 여기서 삼키고 health가 드러낸다.
            // <b>예외 타입만 남긴다.</b> SDK 예외의 메시지에는 큐 주소와 계정 번호가
            // 들어 있고 이 로그는 운영에서 수집된다. <b>예외 객체를 인자로 넘기지 마라</b> —
            // SLF4J가 그것을 throwable로 인식해 메시지와 스택트레이스를 통째로 렌더한다
            // (정제한다고 적어 놓고 정반대로 동작하던 자리다. codex P2, 재현함).
            log.warn("broadcast.intake.poll_failed reason={}", e.getClass().getSimpleName());
            status.pollFailed(e.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * 편지 하나를 처리한다.
     *
     * @return 이 회차를 계속해도 되면 true. <b>false는 「이 편지를 못 지웠다」</b>는 뜻이고,
     *         그때는 뒤 편지를 건드리면 안 된다 — 위 for 루프 주석 참고
     */
    private boolean handle(Message message) {
        LifecycleEnvelope envelope;
        try {
            envelope = mapper.readValue(message.body(), LifecycleEnvelope.class);
        } catch (RuntimeException e) {
            // Jackson 3의 JacksonException은 unchecked라 컴파일러가 안 잡아 준다.
            // 재시도해도 계속 실패한다. 안 지우면 이 편지가 큐 앞을 영원히 막는다.
            // 본문은 안 찍는다 — 무엇이 깨졌는지는 발행 쪽 로그가 안다.
            log.warn("broadcast.intake.unreadable_dropped messageId={} reason={}",
                    message.messageId(), e.getClass().getSimpleName());
            delete(message);
            return true;   // 지웠으므로 큐 앞을 막지 않는다 — 중단 사유가 아니다
        }

        ProcessResult result;
        try {
            result = processor.process(envelope);
        } catch (RuntimeException e) {
            // 🔴 판정기는 DB 예외를 일부러 안 삼킨다(태스크 5) — 여기가 유일한 받는 자리다.
            // DB가 잠깐 죽었을 때 종료 편지를 지우면 메모가 영영 안 남고, 뒤늦게 온 시작
            // 편지가 세션을 연다. 그래서 안 지우고 회차도 멈춘다.
            // 위 poll_failed와 같은 이유로 예외 객체를 안 넘긴다 — 판정기가 던지는
            // 예외의 메시지에 무엇이 실릴지 이쪽에서 알 수 없다.
            log.warn("broadcast.intake.handle_failed eventId={} reason={}",
                    envelope.eventId(), e.getClass().getSimpleName());
            return false;
        }

        if (result == ProcessResult.RETRY_LATER) {
            log.info("broadcast.intake.retry_later eventId={}", envelope.eventId());
            return false;
        }

        // 삭제를 판정의 try 밖에 둔다. 안에 두면 「판정은 됐는데 삭제가 실패」까지
        // handle_failed로 남아 로그가 원인을 반대로 가리킨다. 삭제 실패는 큐에 못 닿는
        // 것이므로 pollOnce의 catch가 poll_failed로 받는 것이 맞다(clip 감사 2차 지적).
        // PROCESSED·IGNORED_STALE·UNREADABLE 셋 다 「더 볼 일 없음」이다.
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
