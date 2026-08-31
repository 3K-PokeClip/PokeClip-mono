package com.pokeclip.chat.collector.broadcast.intake;

import ch.qos.logback.classic.Level;
import com.pokeclip.chat.collector.broadcast.BroadcastEventProcessor;
import com.pokeclip.chat.collector.broadcast.LifecycleEnvelope;
import com.pokeclip.chat.collector.broadcast.ProcessResult;
import com.pokeclip.chat.collector.broadcast.attach.StreamerSerialExecutor;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageResponse;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesResponse;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;
import software.amazon.awssdk.services.sqs.model.SqsException;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * <b>꺼내는 일과 붙이는 일을 가른다</b> (POK-219 태스크 3).
 *
 * <p>지금까지 알림 하나를 꺼내는 스레드가 auth 왕복과 치지직 REST 두 번을 <b>직렬로</b> 했다.
 * 방송 둘이 몰리면 5.09초 + 5.02초 = 10.11초로 선형 누적된다(POK-127 실측).
 * 이 검사들은 그것이 <b>실제로</b> 갈렸는지를 잰다.
 *
 * <p><b>가장 위험한 것은 알림 삭제가 줄로 옮겨간 것이다.</b> 붙기가 끝나기 전에 지우면
 * 그 방송은 되돌아올 트리거가 없다 — 채팅에는 백필이 없다.
 *
 * <p><b>{@code attach-test-reality} 문항에 대한 답</b>
 * <ul>
 *   <li>문항 3(의도한 동시성이 환경에 막히나): 가짜 큐가 <b>동기</b>로 답하고
 *       {@code maxNumberOfMessages}를 지킨다. 겹침의 증거는 {@code CyclicBarrier}다 —
 *       셋이 다 도착해야 풀리므로, 하나라도 직렬이면 시한 초과다</li>
 *   <li>문항 4(직렬 검사가 너무 빨라서 통과하나): 순서 검사는 첫 작업에 체류 시간을 넣고,
 *       그것을 <b>반대 방향</b>으로도 쟀다 — 아래 {@code 줄을_안_쓰면_순서가_실제로_깨진다}</li>
 *   <li>문항 5(비동기 검사가 안 기다려서 통과하나): 「아직 안 지웠다」는 <b>붙기가 실제로
 *       시작된 것을 확인한 뒤</b>에만 단언하고, 풀어 준 뒤 <b>지워지는 것까지</b> 본다</li>
 * </ul>
 */
class AsyncIntakeTest {

    private static final String QUEUE_URL = "http://localhost:4566/000000000000/broadcast.fifo";
    private static final Duration IDLE_BUDGET = Duration.ofSeconds(5);

    // ── 삭제 시점 ──────────────────────────────────────────────────────────

    /**
     * <b>붙기가 끝나야 지운다.</b> 먼저 지우면 그 방송은 되돌아올 트리거가 없다.
     *
     * <p>문항 5(가): {@code isEmpty()}만 보면 <b>10ms 뒤에 지우는 구현도 통과한다.</b>
     * 그래서 ① 붙이기가 실제로 <b>들어간 것</b>을 확인한 뒤 단언하고 ② 풀어 준 뒤
     * <b>실제로 지워지는 것</b>까지 본다 — 긍정 경로가 없으면 「영영 안 지운다」와 구분되지 않는다.
     */
    @Test
    @Timeout(20)
    void 붙이기가_끝나기_전에는_알림을_안_지운다() throws Exception {
        CountDownLatch hold = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1);
        FakeQueue queue = new FakeQueue();
        queue.enqueue("evt-1", "live-A-001", "7");
        SqsIntakeRunner runner = newRunner(queue, envelope -> {
            entered.countDown();
            await(hold);
            return ProcessResult.PROCESSED;
        });

        runner.pollOnce();

        assertThat(entered.await(5, TimeUnit.SECONDS))
                .as("붙이기가 시작조차 안 했으면 아래 단언은 아무것도 안 잰다")
                .isTrue();
        assertThat(queue.deleted()).as("아직 붙는 중이다").isEmpty();
        hold.countDown();
        assertThat(runner.awaitIdle(IDLE_BUDGET)).isTrue();
        assertThat(queue.deleted()).containsExactly("evt-1");
    }

    // ── 겹침과 순서 ────────────────────────────────────────────────────────

    /**
     * <b>다른 스트리머는 서로를 안 기다린다.</b> {@code CyclicBarrier(3)}은 셋이 다 도착해야
     * 풀리므로, 하나라도 앞엣것을 기다리면 시한 초과로 빨간불이다.
     */
    @Test
    @Timeout(30)
    void 방송_여럿이_와도_뒤쪽이_앞쪽을_기다리지_않는다() {
        CyclicBarrier barrier = new CyclicBarrier(3);
        FakeQueue queue = new FakeQueue();
        queue.enqueue("evt-1", "live-A-001", "1");
        queue.enqueue("evt-2", "live-B-001", "2");
        queue.enqueue("evt-3", "live-C-001", "3");
        SqsIntakeRunner runner = newRunner(queue, envelope -> {
            awaitBarrier(barrier);
            return ProcessResult.PROCESSED;
        });

        runner.pollOnce();

        assertThat(runner.awaitIdle(Duration.ofSeconds(10))).isTrue();
        assertThat(queue.deleted()).containsExactlyInAnyOrder("evt-1", "evt-2", "evt-3");
    }

    /**
     * <b>같은 스트리머는 순서대로.</b> 첫 작업에 체류 시간을 넣어 <b>겹칠 기회</b>를 만든다 —
     * 즉시 끝나면 순서가 지켜진 것이 아니라 겹칠 틈이 없었을 뿐이다(문항 4).
     */
    @Test
    @Timeout(20)
    void 같은_스트리머의_알림은_순서대로_처리된다() {
        FakeQueue queue = new FakeQueue();
        queue.enqueue("evt-1", "live-A-001", "7");
        queue.enqueue("evt-2", "live-A-001", "7");
        List<String> seen = Collections.synchronizedList(new ArrayList<>());
        SqsIntakeRunner runner = newRunner(queue, envelope -> {
            if (seen.isEmpty()) {
                sleep(50);   // 겹칠 기회
            }
            seen.add(envelope.eventId());
            return ProcessResult.PROCESSED;
        });

        runner.pollOnce();

        assertThat(runner.awaitIdle(IDLE_BUDGET)).isTrue();
        assertThat(seen).containsExactly("evt-1", "evt-2");
    }

    /**
     * 🔴 <b>문항 4의 반대 방향 증명.</b> 줄을 안 쓰고 그냥 병렬로 던지면 위 검사가
     * 정말 깨지는가. 안 깨지면 위 검사는 직렬이든 병렬이든 똑같이 초록이라 아무것도 안 잰다.
     *
     * <p>줄 이름을 알림마다 다르게 주어 직렬화를 무력화한 것이 이 검사다.
     */
    @Test
    @Timeout(20)
    void 줄을_안_쓰면_순서가_실제로_깨진다() {
        try (StreamerSerialExecutor parallel = new StreamerSerialExecutor(10)) {
            List<String> seen = Collections.synchronizedList(new ArrayList<>());
            CountDownLatch done = new CountDownLatch(2);
            parallel.submit("lane-1", () -> {
                sleep(50);
                seen.add("evt-1");
                done.countDown();
            });
            parallel.submit("lane-2", () -> {
                seen.add("evt-2");
                done.countDown();
            });

            await(done);

            assertThat(seen)
                    .as("줄이 갈리면 뒤엣것이 앞지른다 — 위 순서 검사의 체류 시간이 충분하다는 증거")
                    .containsExactly("evt-2", "evt-1");
        }
    }

    /**
     * <b>앞이 실패하면 같은 줄의 뒤는 안 돈다.</b> 뒤가 먼저 반영되면 앞엣것이 「낡음」으로
     * 걸러진 뒤 지워져 <b>재전송으로도 못 고치는 영구 유실</b>이 된다.
     *
     * <p>문항 2: {@code deleted()}가 비었다는 것만 보면 「아무것도 안 돌았다」도 통과한다 —
     * 그래서 {@code seen}에 첫 알림이 <b>있다</b>는 것을 같이 잰다(긍정 단언이 먼저다).
     */
    @Test
    @Timeout(20)
    void 앞_알림이_실패하면_같은_스트리머의_뒤_알림을_안_건드린다() {
        FakeQueue queue = new FakeQueue();
        queue.enqueue("evt-1", "live-A-001", "7");
        queue.enqueue("evt-2", "live-A-001", "7");
        List<String> seen = Collections.synchronizedList(new ArrayList<>());
        SqsIntakeRunner runner = newRunner(queue, envelope -> {
            seen.add(envelope.eventId());
            return envelope.eventId().equals("evt-1")
                    ? ProcessResult.RETRY_LATER : ProcessResult.PROCESSED;
        });

        runner.pollOnce();

        assertThat(runner.awaitIdle(IDLE_BUDGET)).isTrue();
        assertThat(seen).containsExactly("evt-1");
        assertThat(queue.deleted()).as("둘 다 큐에 남아야 다시 온다").isEmpty();
    }

    /**
     * 🔴 <b>폭이 {@code Throwable}이다</b>(계획 검증 T3·I6). {@code RuntimeException}으로
     * 두면 {@code Error}가 실행기의 {@code catch (Throwable)}로 새는데 <b>거기서는
     * {@code dropPending}을 안 부른다</b> — 앞 알림이 실패했는데 뒤 알림이 반영되는,
     * 이 설계가 막으려던 바로 그 모양이 된다.
     */
    @Test
    @Timeout(20)
    void 판정이_Error로_죽어도_같은_줄의_뒤_알림을_안_건드린다() {
        FakeQueue queue = new FakeQueue();
        queue.enqueue("evt-1", "live-A-001", "7");
        queue.enqueue("evt-2", "live-A-001", "7");
        List<String> seen = Collections.synchronizedList(new ArrayList<>());
        SqsIntakeRunner runner = newRunner(queue, envelope -> {
            seen.add(envelope.eventId());
            throw new StackOverflowError("줄에서 난 Error");
        });

        runner.pollOnce();

        assertThat(runner.awaitIdle(IDLE_BUDGET)).isTrue();
        assertThat(seen).containsExactly("evt-1");
        assertThat(queue.deleted()).isEmpty();
    }

    // ── 백프레셔 ───────────────────────────────────────────────────────────

    /** 줄이 상한에 닿으면 나머지는 안 받는다 — 안 지웠으므로 가시성 시한 뒤 다시 온다. */
    @Test
    @Timeout(20)
    void 줄이_가득_차면_그_회차를_멈춘다() {
        FakeQueue queue = new FakeQueue();
        for (int i = 0; i < 10; i++) {
            queue.enqueue("evt-" + i, "live-" + i, String.valueOf(i));
        }
        CountDownLatch hold = new CountDownLatch(1);
        SqsIntakeRunner runner = newRunner(queue, 2, envelope -> {
            await(hold);
            return ProcessResult.PROCESSED;
        });

        boolean reached = runner.pollOnce();

        assertThat(reached).as("큐에는 닿았다").isTrue();
        assertThat(runner.acceptedInLastPoll()).isEqualTo(2);
        assertThat(queue.deleted()).isEmpty();
        hold.countDown();
    }

    /**
     * 🔴 <b>가득 찬 뒤의 알림은 읽어 보지도 않는다</b> — {@code break}가 하는 일이 이것이다.
     *
     * <p><b>이 검사는 결함 주입이 없었으면 안 만들었다.</b> 스킬 표는 {@code break}를
     * {@code continue}로 바꾸면 위 {@code 줄이_가득_차면_그_회차를_멈춘다}가
     * {@code acceptedInLastPoll == 10}으로 빨간불이 될 것이라 적었는데 <b>초록이었다</b> —
     * 구현이 「받아들인 것만」 세므로 거절된 것은 {@code continue}로도 안 세어진다.
     * 즉 그 검사는 {@code break}를 <b>하나도 안 재고 있었다.</b>
     *
     * <p><b>진짜 차이는 「뒤엣것에 손을 대는가」다.</b> 셋째를 못 읽는 봉투로 두면 드러난다 —
     * {@code continue}는 그것을 파싱해서 <b>지워 버린다.</b> 앞 알림이 아직 큐에 남아 있는데
     * 뒤엣것을 먼저 처분하는 모양이고, 이 설계가 막으려는 「뒤가 먼저 반영된다」의 한 갈래다.
     */
    @Test
    @Timeout(20)
    void 줄이_가득_찬_뒤의_알림은_읽어_보지도_않는다() {
        FakeQueue queue = new FakeQueue();
        queue.enqueue("evt-1", "live-A-001", "1");
        queue.enqueue("evt-2", "live-B-001", "2");
        queue.enqueueRaw("evt-bad", "{ 이건 JSON이 아니다");
        CountDownLatch hold = new CountDownLatch(1);
        SqsIntakeRunner runner = newRunner(queue, 1, envelope -> {
            await(hold);
            return ProcessResult.PROCESSED;
        });

        runner.pollOnce();

        assertThat(runner.acceptedInLastPoll())
                .as("첫 알림이 실제로 자리를 잡았는가 — 0이면 아래가 아무것도 안 잰다")
                .isEqualTo(1);
        assertThat(queue.deleted())
                .as("가득 찬 뒤의 알림은 읽지도 지우지도 않는다")
                .isEmpty();
        hold.countDown();
    }

    /**
     * 🔴 <b>계획 검증 M1.</b> 이것이 없으면 가득 찬 동안 롱폴링이 즉시 반환해 백로그를
     * 계속 꺼내고, 하나도 처리하지 않은 채 가시성 시한 동안 숨긴다 — FIFO라 그 방송들의
     * 뒤 알림이 통째로 막히는데 {@code pollSucceeded}는 계속 찍혀 health가 초록이다.
     */
    @Test
    @Timeout(20)
    void 줄이_가득_차_있으면_큐를_아예_두드리지_않는다() {
        FakeQueue queue = new FakeQueue();
        for (int i = 0; i < 10; i++) {
            queue.enqueue("evt-" + i, "live-" + i, String.valueOf(i));
        }
        CountDownLatch hold = new CountDownLatch(1);
        SqsIntakeRunner runner = newRunner(queue, 2, envelope -> {
            await(hold);
            return ProcessResult.PROCESSED;
        });
        runner.pollOnce();
        int callsAfterFirstPoll = queue.receiveCalls();

        boolean reached = runner.pollOnce();

        assertThat(callsAfterFirstPoll).as("첫 회차가 실제로 큐를 두드렸는가").isEqualTo(1);
        assertThat(reached).as("「닿았다」가 아니다 — 안 두드렸다").isFalse();
        assertThat(queue.receiveCalls()).isEqualTo(callsAfterFirstPoll);
        assertThat(runner.acceptedInLastPoll()).isZero();
        hold.countDown();
    }

    /**
     * 「닿았다」로 세면 2분 {@code stalled} 판정이 안 걸려, 줄이 영영 안 비는 상태가
     * health에서 <b>초록</b>으로 보인다.
     */
    @Test
    @Timeout(20)
    void 가득_찬_회차는_큐가_건강하다고_기록하지_않는다() {
        FakeQueue queue = new FakeQueue();
        queue.enqueue("evt-0", "live-0", "0");
        CountDownLatch hold = new CountDownLatch(1);
        IntakeStatus status = new IntakeStatus(true);
        SqsIntakeRunner runner = newRunner(queue, status, 1, envelope -> {
            await(hold);
            return ProcessResult.PROCESSED;
        });
        runner.pollOnce();
        Instant lastOk = status.snapshot().lastPollSucceededAt();

        runner.pollOnce();

        assertThat(lastOk).as("첫 회차가 실제로 성공을 찍었는가").isNotNull();
        assertThat(status.snapshot().lastPollSucceededAt()).isEqualTo(lastOk);
        hold.countDown();
    }

    /**
     * 포화는 장애가 아니라 정상적인 밀림이다. 백오프(최대 60초)를 태우면 줄이 비어도
     * 그만큼 아무것도 안 꺼낸다.
     */
    @Test
    @Timeout(20)
    void 가득_찬_동안은_백오프가_아니라_짧게_쉰다() {
        FakeQueue queue = new FakeQueue();
        queue.enqueue("evt-0", "live-0", "0");
        CountDownLatch hold = new CountDownLatch(1);
        List<Duration> slept = Collections.synchronizedList(new ArrayList<>());
        SqsIntakeRunner runner = newRunner(queue, new IntakeStatus(true), 1, envelope -> {
            await(hold);
            return ProcessResult.PROCESSED;
        }, duration -> {
            slept.add(duration);
            return slept.size() >= 3;   // 세 번 쉬면 루프를 끝낸다
        });

        runner.runLoop();

        assertThat(slept).containsExactly(SqsIntakeRunner.SATURATED_PAUSE,
                SqsIntakeRunner.SATURATED_PAUSE, SqsIntakeRunner.SATURATED_PAUSE);
        hold.countDown();
    }

    // ── 못 읽는 봉투 ───────────────────────────────────────────────────────

    /** 스트리머를 모르니 줄에 못 넣는다. 재시도해도 계속 실패하므로 그 자리에서 지운다. */
    @Test
    @Timeout(20)
    void 봉투를_못_읽으면_줄에_안_넣고_바로_지운다() {
        FakeQueue queue = new FakeQueue();
        queue.enqueueRaw("evt-bad", "{ 이건 JSON이 아니다");
        SqsIntakeRunner runner = newRunner(queue, envelope -> ProcessResult.PROCESSED);

        runner.pollOnce();

        assertThat(queue.deleted()).containsExactly("evt-bad");
        assertThat(runner.inFlight()).isZero();
    }

    // ── 삭제 실패 ──────────────────────────────────────────────────────────

    /**
     * 🔴 <b>계획 검증 T3·I7.</b> 삭제는 이제 폴링 스레드가 아니라 줄에서 일어나므로
     * {@code pollOnce}의 catch가 못 받는다. {@code status}에 안 알리면 <b>큐에 못 닿는
     * 상태가 health에서 사라진다</b> — 삭제만 실패하고 수신은 되는 동안 계속 초록이다.
     */
    @Test
    @Timeout(20)
    void 삭제가_실패하면_큐가_아프다고_남는다() {
        FakeQueue queue = new FakeQueue();
        queue.failDeletes();
        queue.enqueue("evt-1", "live-A-001", "7");
        IntakeStatus status = new IntakeStatus(true);
        SqsIntakeRunner runner = newRunner(queue, status, 50,
                envelope -> ProcessResult.PROCESSED);

        try (LogCaptor captor = new LogCaptor()) {
            runner.pollOnce();
            assertThat(runner.awaitIdle(IDLE_BUDGET)).isTrue();

            assertThat(captor.levelOf("broadcast.intake.delete_failed")).isEqualTo(Level.WARN);
        }
        assertThat(status.snapshot().healthy()).isFalse();
        assertThat(status.snapshot().lastFailureReason()).isEqualTo("SqsException");
    }

    // ── 가시성 시한 ────────────────────────────────────────────────────────

    /**
     * 붙기가 끝나야 알림을 지우므로, 시한이 붙기보다 짧으면 같은 알림이 다시 온다.
     * 동작은 중복 방어가 지키지만 헛일이 늘므로 값을 남겨 나중에 판단할 수 있게 한다.
     *
     * <p>🔴 {@code LogCaptor}는 root에 붙고 클래스 필터가 없다. 레벨은 {@code messages()}가
     * 아니라 {@code levelOf(이벤트이름)}로 잰다.
     */
    @Test
    @Timeout(20)
    void 가시성_시한이_짧으면_부팅에_한_번_경고를_남긴다() {
        FakeQueue queue = new FakeQueue();
        queue.visibilityTimeoutSeconds(5);
        try (LogCaptor captor = new LogCaptor()) {
            newRunner(queue, envelope -> ProcessResult.PROCESSED).reportQueueVisibility();

            assertThat(captor.levelOf("broadcast.intake.visibility_short")).isEqualTo(Level.WARN);
            assertThat(captor.messages())
                    .anyMatch(line -> line.contains("visibilityTimeoutSeconds=5"));
        }
    }

    /** 문항 4의 짝 — 넉넉하면 경고가 아니라 그냥 남긴다. 짝이 없으면 「늘 WARN」도 통과한다. */
    @Test
    @Timeout(20)
    void 가시성_시한이_넉넉하면_경고가_아니라_그냥_남긴다() {
        FakeQueue queue = new FakeQueue();
        queue.visibilityTimeoutSeconds(60);
        try (LogCaptor captor = new LogCaptor()) {
            newRunner(queue, envelope -> ProcessResult.PROCESSED).reportQueueVisibility();

            assertThat(captor.levelOf("broadcast.intake.visibility")).isEqualTo(Level.INFO);
            assertThat(captor.messages())
                    .noneMatch(line -> line.startsWith("broadcast.intake.visibility_short"));
        }
    }

    /** 큐가 시한을 안 알려 줘도 부팅이 죽지 않는다 — 이 값은 관측용이지 동작에 안 쓰인다. */
    @Test
    @Timeout(20)
    void 가시성_시한을_못_읽어도_부팅이_안_죽는다() {
        FakeQueue queue = new FakeQueue();
        queue.failAttributes();
        try (LogCaptor captor = new LogCaptor()) {
            newRunner(queue, envelope -> ProcessResult.PROCESSED).reportQueueVisibility();

            assertThat(captor.levelOf("broadcast.intake.visibility_unknown")).isEqualTo(Level.WARN);
        }
    }

    // ── 종료 ───────────────────────────────────────────────────────────────

    /**
     * 🔴 <b>종료는 폴링을 먼저 세우고 그 뒤에 줄을 비운다.</b> 순서가 뒤집히면 비운 뒤에
     * 들어온 알림이 실행기가 닫힌 채로 거절돼 조용히 사라질 수 있다.
     *
     * <p>여기서 재는 것은 {@code SqsIntakeLoop.stop()}이 <b>돌고 있던 붙이기가 끝나기를
     * 기다린다</b>는 것이다. 안 기다리면 스프링이 곧바로 빈 파괴로 넘어가 세션·DB가 밑에서
     * 닫힌다.
     */
    @Test
    @Timeout(30)
    void 종료가_돌고_있는_붙이기를_기다린다() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        List<String> finished = new CopyOnWriteArrayList<>();
        FakeQueue queue = new FakeQueue();
        queue.enqueue("evt-1", "live-A-001", "7");
        SqsIntakeRunner runner = newRunner(queue, envelope -> {
            entered.countDown();
            sleep(300);
            finished.add(envelope.eventId());
            return ProcessResult.PROCESSED;
        });
        SqsIntakeLoop loop = new SqsIntakeLoop(runner);

        loop.start();
        assertThat(entered.await(5, TimeUnit.SECONDS))
                .as("붙이기가 시작조차 안 했으면 아래는 아무것도 안 잰다").isTrue();
        loop.stop();

        assertThat(finished).as("stop이 돌아왔을 때 붙이기가 끝나 있어야 한다")
                .containsExactly("evt-1");
        assertThat(queue.deleted()).containsExactly("evt-1");
    }

    /**
     * 🔴 <b>줄 비우기 예산은 2초다</b>(계획 검증 M3). README의 편지 경로 산수가
     * join 2 + 세션 닫기 8 + flush 5 = 15초이고 유예는 20초다. 6초를 끼우면 21초라
     * <b>세션 닫기가 잘려 구독이 반납 안 되고 계정 자리가 남는다.</b>
     *
     * <p>값을 글자로 못박는다 — 이 숫자가 조용히 커지는 것이 정확히 그 사고다.
     */
    @Test
    void 종료_예산이_유예를_안_넘긴다() {
        assertThat(SqsIntakeLoop.DRAIN_WAIT).isEqualTo(Duration.ofSeconds(2));
        assertThat(SqsIntakeLoop.JOIN_WAIT.plus(SqsIntakeLoop.DRAIN_WAIT))
                .as("join 2 + 줄 비우기 2 + 세션 닫기 8 + flush 5 = 17초 < 유예 20초")
                .isLessThanOrEqualTo(Duration.ofSeconds(4));
    }

    // ── 조립 도우미 ────────────────────────────────────────────────────────

    private SqsIntakeRunner newRunner(FakeQueue queue,
                                      Function<LifecycleEnvelope, ProcessResult> handler) {
        return newRunner(queue, new IntakeStatus(true), 50, handler);
    }

    private SqsIntakeRunner newRunner(FakeQueue queue, int maxInFlight,
                                      Function<LifecycleEnvelope, ProcessResult> handler) {
        return newRunner(queue, new IntakeStatus(true), maxInFlight, handler);
    }

    private SqsIntakeRunner newRunner(FakeQueue queue, IntakeStatus status, int maxInFlight,
                                      Function<LifecycleEnvelope, ProcessResult> handler) {
        return newRunner(queue, status, maxInFlight, handler, null);
    }

    private SqsIntakeRunner newRunner(FakeQueue queue, IntakeStatus status, int maxInFlight,
                                      Function<LifecycleEnvelope, ProcessResult> handler,
                                      SqsIntakeRunner.Sleeper sleeper) {
        BroadcastEventProcessor processor = mock(BroadcastEventProcessor.class);
        given(processor.process(any())).willAnswer(
                invocation -> handler.apply(invocation.getArgument(0)));
        // 검사가 끝나면 JVM이 가상 스레드를 안 붙드므로 따로 닫지 않는다 —
        // 닫으면 awaitIdle 뒤에도 도는 마지막 작업이 거절될 수 있다.
        return new SqsIntakeRunner(queue, properties(), status, processor, new ObjectMapper(),
                new StreamerSerialExecutor(maxInFlight), sleeper);
    }

    private static IntakeProperties properties() {
        return new IntakeProperties(true, QUEUE_URL, "ap-northeast-2", "",
                Duration.ofSeconds(20), 10);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("빗장이 10초 안에 안 풀렸다");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static void awaitBarrier(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException | BrokenBarrierException | TimeoutException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("셋이 겹치지 않았다 — 직렬로 돌고 있다", e);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 가짜 큐. <b>{@code SqsIntakeRunnerTest}의 것과 갈라 둔 이유</b>: 저쪽은 같은 알림을
     * 회차마다 다시 주는(소비하지 않는) 모양이고, 여기서는 <b>회차 사이에 줄이 차 있는지</b>를
     * 재야 해서 실제로 소비해야 한다. 저쪽을 고치면 저쪽 검사 17건의 전제가 바뀐다.
     *
     * <p>삭제 이름을 {@code receiptHandle}이 아니라 <b>{@code eventId}</b>로 되돌려 준다 —
     * 「지우긴 지웠다」가 아니라 「그 알림을 지웠다」를 재려면 이름이 붙어야 한다.
     */
    static final class FakeQueue implements SqsClient {

        private final Deque<Message> waiting = new ArrayDeque<>();
        private final Map<String, String> eventIdByHandle = new HashMap<>();
        private final List<String> deleted = new CopyOnWriteArrayList<>();
        private final AtomicInteger receiveCalls = new AtomicInteger();
        private final AtomicInteger handles = new AtomicInteger();
        private volatile int visibilityTimeoutSeconds = 30;
        private volatile boolean deletesFail;
        private volatile boolean attributesFail;

        void enqueue(String eventId, String streamId, String streamerId) {
            enqueueRaw(eventId, startedJson(eventId, streamId, streamerId));
        }

        void enqueueRaw(String eventId, String body) {
            String handle = "rh-" + handles.getAndIncrement();
            eventIdByHandle.put(handle, eventId);
            waiting.addLast(Message.builder()
                    .messageId("msg-" + eventId).receiptHandle(handle).body(body).build());
        }

        void visibilityTimeoutSeconds(int seconds) {
            this.visibilityTimeoutSeconds = seconds;
        }

        void failDeletes() {
            this.deletesFail = true;
        }

        void failAttributes() {
            this.attributesFail = true;
        }

        List<String> deleted() {
            return List.copyOf(deleted);
        }

        int receiveCalls() {
            return receiveCalls.get();
        }

        @Override
        public ReceiveMessageResponse receiveMessage(ReceiveMessageRequest request) {
            receiveCalls.incrementAndGet();
            List<Message> batch = new ArrayList<>();
            synchronized (waiting) {
                while (batch.size() < request.maxNumberOfMessages() && !waiting.isEmpty()) {
                    batch.add(waiting.pollFirst());
                }
            }
            return ReceiveMessageResponse.builder().messages(batch).build();
        }

        @Override
        public DeleteMessageResponse deleteMessage(DeleteMessageRequest request) {
            if (deletesFail) {
                throw SqsException.builder().message("delete refused").build();
            }
            deleted.add(eventIdByHandle.get(request.receiptHandle()));
            return DeleteMessageResponse.builder().build();
        }

        @Override
        public GetQueueAttributesResponse getQueueAttributes(GetQueueAttributesRequest request) {
            if (attributesFail) {
                throw SqsException.builder().message("no permission").build();
            }
            return GetQueueAttributesResponse.builder()
                    .attributes(Map.of(QueueAttributeName.VISIBILITY_TIMEOUT,
                            String.valueOf(visibilityTimeoutSeconds)))
                    .build();
        }

        @Override
        public String serviceName() {
            return "sqs";
        }

        @Override
        public void close() {
        }

        private static String startedJson(String eventId, String streamId, String streamerId) {
            return """
                    {"schemaVersion":1,"eventId":"%s","eventType":"broadcast.started",
                     "occurredAt":"2026-08-19T00:00:00Z","streamId":"%s","streamerId":"%s",
                     "sequence":1,"traceId":"t-1","payload":{}}"""
                    .formatted(eventId, streamId, streamerId);
        }
    }
}
