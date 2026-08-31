package com.pokeclip.chat.collector.broadcast.intake;

import com.pokeclip.chat.collector.broadcast.BroadcastEventProcessor;
import com.pokeclip.chat.collector.broadcast.LifecycleEnvelope;
import com.pokeclip.chat.collector.broadcast.ProcessResult;
import com.pokeclip.chat.collector.broadcast.attach.LaneKey;
import com.pokeclip.chat.collector.broadcast.attach.StreamerSerialExecutor;
import com.pokeclip.chat.collector.reconnect.ReconnectPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesResponse;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 큐에서 방송 생명주기 편지를 꺼내 <b>스트리머별 줄에 넣는다.</b> 판정도 삭제도 그 줄 안에서
 * 일어난다 — 이 스레드는 꺼내서 넘기기만 한다(POK-219).
 *
 * <p><b>왜 갈랐나</b>: 예전에는 이 스레드 하나가 꺼내기·판정·auth 왕복·치지직 REST 두 번·
 * 삭제를 전부 직렬로 했다. 방송 둘이 몰리면 5.09초 + 5.02초 = <b>10.11초</b>로 선형 누적된다
 * (POK-127 실측). N번째 방송은 앞의 시한이 다 쌓인 뒤에 붙고, <b>채팅에는 백필이 없어
 * 그 시간이 곧 유실</b>이다.
 *
 * <p>지우는 기준이 「성공」이 아니라 「더 볼 일 없음」이다 — 낡은 편지도, 우리가 못 읽는
 * 편지도 다시 받을 이유가 없으므로 지운다. 반대로 {@code RETRY_LATER}와 <b>판정이 예외로
 * 끝난 것</b>은 안 지운다. 가시성 타임아웃이 지나면 큐가 다시 주고, 판정이 멱등이라
 * 두 번 와도 안전하다.
 *
 * <p><b>회차 중단이 「줄 중단」으로 바뀌었다.</b> 예전에는 못 지운 편지가 나오면 그 회차의
 * 나머지를 통째로 안 봤는데(멀쩡한 남의 방송까지 미뤄졌다), 이제는 <b>그 스트리머의 줄만</b>
 * 멈춘다. 막으려는 사고는 같다 — 뒤 알림이 먼저 반영되면 앞엣것이 「낡음」으로 걸러진 뒤
 * 지워져 재전송으로도 못 고치는 영구 유실이 된다(clip PR #82 P1).
 *
 * <p>🔴 <b>그래서 한 회차의 같은 줄 알림은 「작업 하나」로 넘긴다</b>({@link #handleBatch}).
 * 알림마다 따로 넘기면 앞엣것의 판정과 뒤엣것의 제출이 경쟁해 <b>줄 중단이 뒤엣것을
 * 못 덮는 창</b>이 열린다(감사 G1). 그 창과 남는 갈래의 근거는 {@link #handleBatch}에 적었다.
 *
 * <p><b>조립은 {@code LetterPathConfiguration}이 한다</b>({@code ObjectProvider<SqsClient>}로
 * 받는다 — 거기 주석). 이 서버의 조립 관례도 같은 자리다: {@code CollectorRunner}·
 * {@code EndedStreamSweeper} 둘 다 {@code @Component}가 아니라 {@code @Bean}이 만든다.
 *
 * <p><b>스레드 수명은 {@link SqsIntakeLoop}가 든다.</b> 여기서 안 정하는 이유는 종료 유예
 * 예산이 이 부품의 관심사가 아니기 때문이다 — 세션 여럿을 닫는 시간과 줄을 비우는 시간이
 * 같은 20초를 나눠 쓴다.
 */
public class SqsIntakeRunner {

    private static final Logger log = LoggerFactory.getLogger(SqsIntakeRunner.class);

    /** 재연결·마이그레이션 재시도와 같은 값이다. 상한 60초면 복구가 최악 1분 늦는다. */
    static final Duration FIRST_RETRY_DELAY = Duration.ofSeconds(1);
    static final Duration MAX_RETRY_DELAY = Duration.ofSeconds(60);

    /**
     * 줄이 가득 찬 동안 쉬는 간격. <b>백오프가 아니라 고정이다</b> — 포화는 장애가 아니라
     * 정상적인 밀림이고, 백오프(최대 60초)를 태우면 줄이 비어도 그만큼 아무것도 안 꺼낸다.
     */
    static final Duration SATURATED_PAUSE = Duration.ofMillis(200);

    /**
     * 붙기가 <b>얼마나 오래 걸려도 되는가</b>의 눈금. 이보다 짧으면 아직 붙는 중인 알림을
     * 큐가 다시 주므로 헛일이 는다(동작은 중복 방어가 지킨다 —
     * {@code LinkedSessionStarter}가 이미 걷고 있는 방송이면 auth에 묻지도 않는다).
     * 실측으로 auth 왕복 하나가 5초까지 갔으므로(POK-127) 그 두 배를 눈금으로 잡는다.
     */
    static final int MIN_SAFE_VISIBILITY_SECONDS = 10;

    private final SqsClient sqs;
    private final IntakeProperties properties;
    private final IntakeStatus status;
    private final BroadcastEventProcessor processor;
    private final ObjectMapper mapper;
    /** 스트리머별 직렬 줄. 같은 스트리머는 순서대로, 다른 스트리머는 겹쳐서 붙는다. */
    private final StreamerSerialExecutor lanes;

    /**
     * 재연결과 같은 백오프를 그대로 쓴다. 이 서버에 이미 있는 것을 복사하면 한쪽만 고쳐져
     * 갈라진다 — {@code ChatPersister}/{@code ChatArchiver} 쌍둥이에서 실제로 그랬다.
     */
    private final ReconnectPolicy backoff = new ReconnectPolicy(FIRST_RETRY_DELAY, MAX_RETRY_DELAY);
    private final Sleeper sleeper;
    /** 자는 도중에도 종료에 반응하려면 신호가 필요하다 — {@code Thread.sleep}은 못 깬다. */
    private final CountDownLatch stopSignal = new CountDownLatch(1);

    private volatile boolean running = true;
    /** 마지막 회차에 줄로 넘긴 알림 수. 백프레셔가 실제로 걸렸는지를 검사가 이걸로 본다. */
    private volatile int acceptedInLastPoll;

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
                           BroadcastEventProcessor processor, ObjectMapper mapper,
                           StreamerSerialExecutor lanes) {
        this(sqs, properties, status, processor, mapper, lanes, null);
    }

    /**
     * @param sqs <b>null을 거부한다.</b> 꺼져 있으면 이 부품을 아예 만들지 않는 것이 맞고,
     *            켜졌는데 null이면 주입이 잘못된 것이다({@code Optional} 함정). 껍데기로
     *            받아 조용히 안 도는 것보다 부팅에서 죽는 편이 낫다
     * @param lanes 같은 이유로 null을 거부한다. 줄이 없으면 붙이기가 한 건도 안 돈다
     * @param sleeper null이면 종료 신호를 기다리는 실물. 검사만 가짜를 넣는다
     */
    public SqsIntakeRunner(SqsClient sqs, IntakeProperties properties, IntakeStatus status,
                           BroadcastEventProcessor processor, ObjectMapper mapper,
                           StreamerSerialExecutor lanes, Sleeper sleeper) {
        this.sqs = Objects.requireNonNull(sqs, "SqsClient가 없다 — 켜져 있는데 주입이 안 됐다");
        this.properties = properties;
        this.status = status;
        this.processor = processor;
        this.mapper = mapper;
        this.lanes = Objects.requireNonNull(lanes, "StreamerSerialExecutor가 없다 — 붙이기가 안 돈다");
        this.sleeper = sleeper != null ? sleeper : this::awaitStop;
    }

    /**
     * 큐의 가시성 시한을 <b>부팅에 한 번</b> 읽어 남긴다. 실패해도 조용히 넘어간다 —
     * 이 값은 관측용이지 동작에 안 쓰인다.
     *
     * <p><b>왜 남기나</b>: 붙기가 끝나야 알림을 지우게 됐으므로, 시한이 붙기보다 짧으면
     * 같은 알림이 붙는 도중에 다시 온다. 동작은 중복 방어가 지키지만 헛일이 늘고,
     * 그때 로그에 이 값이 없으면 원인을 큐 설정이 아니라 우리 코드에서 찾게 된다.
     */
    public void reportQueueVisibility() {
        try {
            GetQueueAttributesResponse attributes = sqs.getQueueAttributes(
                    GetQueueAttributesRequest.builder()
                            .queueUrl(properties.queueUrl())
                            .attributeNames(QueueAttributeName.VISIBILITY_TIMEOUT)
                            .build());
            int seconds = Integer.parseInt(
                    attributes.attributes().get(QueueAttributeName.VISIBILITY_TIMEOUT));
            if (seconds < MIN_SAFE_VISIBILITY_SECONDS) {
                log.warn("broadcast.intake.visibility_short visibilityTimeoutSeconds={} minSafe={}",
                        seconds, MIN_SAFE_VISIBILITY_SECONDS);
            } else {
                log.info("broadcast.intake.visibility visibilityTimeoutSeconds={}", seconds);
            }
        } catch (RuntimeException e) {
            // 권한이 없거나(SQS는 GetQueueAttributes를 따로 요구한다) 값이 숫자가 아니면
            // 여기로 온다. 위 poll_failed와 같은 이유로 예외 객체를 안 넘긴다 —
            // SDK 예외 메시지에 큐 주소와 계정 번호가 들어 있다.
            log.warn("broadcast.intake.visibility_unknown reason={}", e.getClass().getSimpleName());
        }
    }

    /**
     * 루프 본체. <b>실패했을 때만 쉰다</b> — 성공하면 롱폴링이 이미 대기 역할을 하므로 곧바로
     * 다음 회차로 간다. 성공하면 간격을 처음으로 되돌린다: 안 되돌리면 한 번 흔들린 뒤로
     * 영영 60초에 한 번씩만 꺼내게 된다.
     */
    public void runLoop() {
        int consecutiveFailures = 0;
        // <b>들어설 때 한 번만 찍는다</b>(감사 라운드 3 H4). SATURATED_PAUSE가 200ms라
        // 회차마다 찍으면 초당 다섯 줄이고, <b>대량 재부착이 정확히 포화를 만드는
        // 시나리오라</b> 복구가 도는 내내 로그가 밀려 그 옆의 chat.reattach.*가 안 보인다.
        //
        // <b>「얼마나 오래 찼나」를 이 줄이 안 져도 된다.</b> 포화 회차는 큐를 두드리지
        // 않으므로 마지막 폴링 성공 시각이 안 움직이고, 2분을 넘기면 health가
        // letterIntake=stalled로 DOWN을 준다. 깊이는 inFlight=가 말한다.
        //
        // 지역 변수인 이유: 이 깃발을 보는 곳이 이 루프뿐이다. 필드로 올리면 pollOnce를
        // 직접 부르는 검사들과 상태를 나눠 갖게 돼 「누가 껐나」가 흐려진다.
        boolean saturationLogged = false;
        while (running) {
            // 🔴 <b>포화를 폴링보다 먼저 본다</b>(계획 검증 M1). 꺼낸 뒤에 거절하면 이미
            // 늦다 — 받아 둔 알림이 가시성 시한 동안 숨겨지고, FIFO라 그 방송들의 뒤
            // 알림이 통째로 막힌다. 그동안 pollSucceeded가 계속 찍혀 health는 초록이다.
            if (lanes.saturated()) {
                if (!saturationLogged) {
                    log.info("broadcast.intake.saturated inFlight={}", lanes.inFlight());
                    saturationLogged = true;
                }
                if (sleeper.sleepOrStop(SATURATED_PAUSE)) {
                    return;
                }
                continue;
            }
            saturationLogged = false;
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
     * 한 회차. <b>꺼내서 줄에 넣기까지</b>가 이 메서드다 — 판정도 삭제도 여기서 안 끝난다.
     * 대부분의 검사는 이 메서드만 부르고 {@link #awaitIdle}로 줄이 비기를 기다린다.
     *
     * @return <b>큐에 닿아 건강하다고 셀 수 있으면 true.</b> 가득 차서 안 두드린 회차는
     *         false다 — true로 세면 2분 {@code stalled} 판정이 안 걸려, 줄이 영영 안
     *         비는 상태가 health에서 초록으로 보인다
     */
    public boolean pollOnce() {
        if (lanes.saturated()) {
            // runLoop가 이미 걸렀지만 검사가 pollOnce를 직접 부른다. 두 자리에 두는
            // 이유는 그것이고, 여기가 없으면 그 검사들이 포화를 안 재게 된다.
            acceptedInLastPoll = 0;
            return false;
        }
        try {
            List<Message> messages = sqs.receiveMessage(ReceiveMessageRequest.builder()
                    .queueUrl(properties.queueUrl())
                    .maxNumberOfMessages(properties.maxMessages())
                    .waitTimeSeconds((int) properties.waitTime().toSeconds())
                    .build()).messages();

            // 1단계는 <b>읽기만 한다.</b> 부작용이 없으므로 2단계의 break가 지키는 범위가
            // 줄지 않는다 — 못 읽는 봉투를 지우는 것도 2단계에서 순서대로 한다.
            List<LifecycleEnvelope> parsed = new ArrayList<>(messages.size());
            Map<String, List<Letter>> byLane = new LinkedHashMap<>();
            for (Message message : messages) {
                LifecycleEnvelope envelope = parseOrNull(message);
                parsed.add(envelope);
                if (envelope != null) {
                    byLane.computeIfAbsent(LaneKey.of(envelope.streamerId()), key -> new ArrayList<>())
                            .add(new Letter(message, envelope));
                }
            }

            // 2단계는 <b>받은 순서대로</b> 처분한다. 줄 하나는 통째로 작업 하나가 된다.
            int accepted = 0;
            Set<String> submitted = new HashSet<>();
            for (int i = 0; i < messages.size(); i++) {
                LifecycleEnvelope envelope = parsed.get(i);
                if (envelope == null) {
                    dropUnreadable(messages.get(i));
                    accepted++;
                    continue;
                }
                String lane = LaneKey.of(envelope.streamerId());
                if (!submitted.add(lane)) {
                    continue;   // 이 줄은 첫 알림에서 통째로 제출됐다
                }
                List<Letter> batch = byLane.get(lane);
                if (!lanes.submit(lane, () -> handleBatch(lane, batch))) {
                    // 줄이 찼다. 남은 것은 안 건드린다 — 지우지 않았으므로
                    // 가시성 시한이 지나면 다시 온다.
                    break;
                }
                accepted += batch.size();
            }
            acceptedInLastPoll = accepted;
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

    /** 한 회차에서 같은 줄로 갈 알림 하나. 봉투를 두 번 파싱하지 않으려고 같이 든다. */
    private record Letter(Message message, LifecycleEnvelope envelope) { }

    /** @return 못 읽으면 {@code null}. <b>여기서는 아무것도 지우지 않는다</b> — 1단계는 읽기만 한다 */
    private LifecycleEnvelope parseOrNull(Message message) {
        try {
            return mapper.readValue(message.body(), LifecycleEnvelope.class);
        } catch (RuntimeException e) {
            // Jackson 3의 JacksonException은 unchecked라 컴파일러가 안 잡아 준다.
            return null;
        }
    }

    /**
     * <b>스트리머를 모르니 줄에 넣을 수 없다.</b> 재시도해도 계속 실패하고, 안 지우면
     * 이 편지가 큐 앞을 영원히 막는다. 본문은 안 찍는다 — 무엇이 깨졌는지는 발행 쪽 로그가 안다.
     */
    private void dropUnreadable(Message message) {
        log.warn("broadcast.intake.unreadable_dropped messageId={}", message.messageId());
        deleteOrReport(message, message.messageId());
    }

    /**
     * 줄 안에서 도는 본체. <b>한 회차에서 이 줄로 온 알림 전부가 한 작업이다.</b>
     *
     * <p>🔴 <b>왜 알림마다가 아니라 줄로 묶는가</b>(감사 G1). 알림마다 따로 제출하면
     * <b>앞엣것의 판정과 뒤엣것의 제출이 경쟁한다.</b> 앞엣것이 먼저 끝나면
     * {@link StreamerSerialExecutor#dropPending}이 버릴 것을 못 찾고, 이어서
     * {@code nextOrRelease}가 줄을 맵에서 지운다 — 그러면 <b>「앞엣것이 실패했다」를 기억할
     * 상태가 아무 데도 안 남고</b>, 뒤늦게 제출된 알림이 새 줄에서 그대로 돈다.
     * 앞 알림은 큐에 남고 뒤 알림이 처리·삭제되므로, 재전송된 앞 알림은
     * {@code IGNORED_STALE}로 걸러진 뒤 지워진다 — <b>되돌릴 길이 없다.</b>
     *
     * <p><b>고치기 전에는 순서 보장이 「폴링이 판정보다 빠르다」에 기대고 있었고, 그 전제가
     * 코드 어디에도 적혀 있지 않았다.</b> 실제로 그 여유는 1밀리초 미만이다 — 실패 갈래는
     * 전부 DB 왕복을 한 번 지나지만({@code EndedStreamStore.find}), 폴링 쪽은 파싱 한 번뿐이라
     * 폴링 스레드가 GC나 스케줄 아웃을 한 번 맞으면 뒤집힌다.
     *
     * <p>묶으면 그 경쟁 자체가 없어진다 — 이 줄의 이번 회차 알림은 <b>이 작업이 다 쥐고 있다.</b>
     * 실패하면 나머지를 손대지 않고 멈추는 것이 <b>옛 러너의 회차 {@code break}를 줄 안으로
     * 옮긴 것</b>이고, 다른 스트리머는 각자의 줄이라 안 밀린다.
     *
     * <p><b>회차를 넘는 창은 남는다 — 해롭지 않은 근거는 발행 계약이다.</b> 이 회차의 작업이
     * 실패해 {@code dropPending}을 부를 때 다음 회차가 아직 제출 전일 수 있다. 그래도 안전한
     * 이유는 <b>같은 방송의 알림이 같은 {@code MessageGroupId}(= {@code streamId})</b>라,
     * 앞엣것이 안 지워진 채 in-flight인 동안 SQS FIFO가 다음 것을 <b>주지 않기</b> 때문이다.
     * 🔴 <b>그것은 우리 코드가 강제하는 것이 아니라 1번의 발행 계약이다</b> — 그룹 열쇠를
     * 방송별에서 스트리머별로 바꾸는 날 이 보장이 <b>조용히</b> 깨진다.
     * (같은 스트리머의 <b>다른 방송</b>은 다른 그룹이지만 같은 줄이다. 둘 사이의 순서는 줄이
     * 아니라 {@code LinkedSessionStarter.isStaleStart}가 보고, 나중 것이 먼저 반영돼도
     * 앞엣것은 안 지워졌으므로 다시 온다 — 유실이 아니라 지연이다.)
     *
     * <p><b>재부착(태스크 7)이 같은 줄의 둘째 제출자다 — 보고 왔고, 같은 모양의 창은 안 열린다.</b>
     * 여기 「그 카드에서 봐야 한다」고만 적혀 있던 자리다. G1이 영구 유실이 되는 이유는
     * <b>뒤엣것이 처리되면서 지워지고, 재전송된 앞엣것이 {@code IGNORED_STALE}로 걸러지는</b>
     * 것인데, 재부착 작업은 <b>알림을 하나도 지우지 않고 알림과의 순서에도 기대지 않는다</b> —
     * 늦게 붙어도 뒤이어 온 시작 알림이 {@code retargetOrSkip}에서 이기고(더 늦게 시작한 방송만
     * 자리를 가져간다), 먼저 붙어도 그 알림은 「이미 걷고 있음」으로 {@code PROCESSED}가 된다.
     * <b>즉 재부착은 이 보호가 지키려는 것을 건드리지 않는다.</b>
     *
     * <p>반대 방향 하나는 실재하고 해롭지 않다: 여기 {@code dropPending}이 그 줄에 대기 중이던
     * <b>재부착 작업까지 버린다.</b> 재부착은 상태가 없고 주기적이라 다음 회차가 같은 목록을
     * 다시 받는다 — 늦어지는 것이 전부이고, 그 지연의 상한이 재부착 주기다.
     */
    private void handleBatch(String lane, List<Letter> batch) {
        for (Letter letter : batch) {
            if (!handleOne(letter)) {
                // 이 배치의 나머지는 손대지 않는다. 그리고 그 줄에 <b>이미 들어와 있던</b>
                // 다른 회차의 배치도 버린다 — 그것들이 먼저 반영되면 막으려던 모양이 된다.
                // 버린 것은 큐에 그대로 있으므로 가시성 시한이 지나면 다시 온다.
                lanes.dropPending(lane);
                return;
            }
        }
    }

    /** @return 이 줄을 계속 처리해도 되면 true. <b>false는 「앞엣것을 못 끝냈다」</b>는 뜻이다 */
    private boolean handleOne(Letter letter) {
        LifecycleEnvelope envelope = letter.envelope();
        ProcessResult result;
        try {
            result = processor.process(envelope);
        } catch (Throwable t) {
            // 🔴 <b>폭이 Throwable이다</b>(계획 검증 T3·I6). RuntimeException으로 두면
            // Error가 실행기의 catch(Throwable)로 새는데 <b>거기서는 뒤엣것을 안 멈춘다</b> —
            // 앞 알림이 실패했는데 뒤 알림이 반영되는, 이 설계가 막으려던 모양이 된다.
            //
            // 판정기는 DB 예외를 일부러 안 삼킨다 — 여기가 유일한 받는 자리다.
            // DB가 잠깐 죽었을 때 종료 편지를 지우면 메모가 영영 안 남고, 뒤늦게 온
            // 시작 편지가 세션을 연다. 그래서 안 지운다.
            log.warn("broadcast.intake.handle_failed eventId={} reason={}",
                    envelope.eventId(), t.getClass().getSimpleName());
            return false;
        }
        if (result == ProcessResult.RETRY_LATER) {
            log.info("broadcast.intake.retry_later eventId={}", envelope.eventId());
            return false;
        }
        // 삭제를 판정의 try 밖에 둔다. 안에 두면 「판정은 됐는데 삭제가 실패」까지
        // handle_failed로 남아 로그가 원인을 반대로 가리킨다.
        // PROCESSED·IGNORED_STALE·LINK_REFUSED·UNREADABLE 넷 다 「더 볼 일 없음」이다.
        // LINK_REFUSED가 POK-219에서 늘었고 이 자리를 실제로 지나간다 — 판정기는 포기 메모를
        // 남긴 뒤 <b>PROCESSED로 바꾸지 않고 그대로 돌려준다</b>(handleStarted). result=가
        // 「왜 지웠나」를 잃지 않게 하려는 것인데, 그 값이 여기까지 온다는 뜻이기도 하다.
        log.info("broadcast.intake.handled eventId={} result={}", envelope.eventId(), result);
        deleteOrReport(letter.message(), envelope.eventId());
        return true;
    }

    /**
     * 지우고, 못 지우면 <b>health에 남긴다.</b>
     *
     * <p>삭제 실패는 큐에 못 닿는 것이다. 여기는 폴링 스레드가 아니라 줄이므로
     * {@code pollOnce}의 catch가 못 받는다 — 그래서 여기서 받는다. 안 지운 알림은
     * 가시성 시한 뒤 다시 오고 판정이 멱등이라 안전하다.
     *
     * <p>🔴 <b>{@code pollFailed}가 아니라 {@code deleteFailed}다</b>(감사 G2).
     * 같은 칸에 담으면 <b>다음 폴링 성공이 그 표시를 지운다</b> — 그런데 삭제만 실패하는
     * 동안에도 수신은 계속 성공하므로, 20초마다 한 번 깜빡일 뿐 health는 대체로 초록이었다.
     */
    private void deleteOrReport(Message message, String label) {
        try {
            delete(message);
            status.deleteSucceeded();
        } catch (RuntimeException e) {
            log.warn("broadcast.intake.delete_failed eventId={} reason={}",
                    label, e.getClass().getSimpleName());
            status.deleteFailed(e.getClass().getSimpleName());
        }
    }

    /** @return 예산 안에 줄의 붙이기가 전부 끝나면 true */
    public boolean awaitIdle(Duration budget) {
        return lanes.awaitIdle(budget);
    }

    /** 돌고 있는 것 + 대기 중인 붙이기 수. */
    public int inFlight() {
        return lanes.inFlight();
    }

    int acceptedInLastPoll() {
        return acceptedInLastPoll;
    }

    private void delete(Message message) {
        sqs.deleteMessage(DeleteMessageRequest.builder()
                .queueUrl(properties.queueUrl())
                .receiptHandle(message.receiptHandle())
                .build());
    }
}
