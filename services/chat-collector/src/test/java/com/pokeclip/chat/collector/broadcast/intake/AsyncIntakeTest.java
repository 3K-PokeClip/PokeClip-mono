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
import java.util.concurrent.atomic.AtomicReference;
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

    /**
     * 🔴 <b>앞 알림이 실패하면 뒤 알림은 「아직 제출 전」이어도 안 돈다</b> (감사 G1).
     *
     * <p><b>왜 이 검사가 따로 필요한가</b>: 아래 {@code 앞_알림이_실패하면…}은 폴링 스레드가
     * 두 알림을 <b>판정보다 빨리</b> 제출할 때만 초록이다. 그 전제가 코드 어디에도 없었고,
     * 깨지면 앞 알림은 큐에 남고 뒤 알림이 처리·삭제된다 — 재전송된 앞 알림은
     * {@code IGNORED_STALE}로 걸러진 뒤 지워지므로 <b>되돌릴 방법이 없다.</b>
     *
     * <p><b>창을 시간으로 벌리지 않는다.</b> {@code sleep}으로 벌리면 그 검사는 「그때 그렇게
     * 느렸다」만 재고 회귀로 남지 않는다. 대신 <b>파싱 시점을 잡는다</b> —
     * 러너는 {@code mapper.readValue}를 폴링 스레드에서, 두 제출 사이에 부른다.
     * 둘째 알림의 파싱을 <b>줄이 빌 때까지</b> 막으면 양쪽이 결정적으로 갈린다.
     *
     * <ul>
     *   <li><b>알림마다 따로 제출하는 구현</b>: 첫 알림이 이미 제출돼 있으므로 게이트가
     *       실제로 막고, 첫 알림이 <b>완전히 끝난 뒤</b>(실패 → {@code dropPending} →
     *       줄 해제) 둘째가 제출돼 <b>돈다</b> → 빨간불</li>
     *   <li><b>회차를 줄별로 묶는 구현</b>: 제출 전에 다 파싱하므로 그 시점 {@code inFlight}가
     *       이미 0이라 게이트가 <b>즉시 통과</b>한다(교착 없음) → 한 배치 → 초록</li>
     * </ul>
     *
     * <p>상한을 넘기면 <b>단언으로 터뜨린다</b> — 조용히 초록이 되는 길을 막는다.
     */
    @Test
    @Timeout(30)
    void 앞_알림이_실패하면_아직_제출되지_않은_뒤_알림도_안_돈다() {
        FakeQueue queue = new FakeQueue();
        queue.enqueue("evt-1", "live-A-001", "7");
        queue.enqueue("evt-2", "live-A-001", "7");
        List<String> seen = Collections.synchronizedList(new ArrayList<>());
        AtomicReference<SqsIntakeRunner> holder = new AtomicReference<>();
        SqsIntakeRunner runner = newRunner(queue, new IntakeStatus(true), 50,
                envelope -> {
                    seen.add(envelope.eventId());
                    return ProcessResult.RETRY_LATER;
                },
                null, new LaneDrainGate(holder, "evt-2"));
        holder.set(runner);

        runner.pollOnce();

        assertThat(runner.awaitIdle(IDLE_BUDGET)).isTrue();
        assertThat(seen)
                .as("앞엣것이 실패했다 — 뒤엣것은 제출 시점과 무관하게 안 돌아야 한다")
                .containsExactly("evt-1");
        assertThat(queue.deleted()).as("둘 다 큐에 남아야 다시 온다").isEmpty();
    }

    /**
     * 둘째 알림의 <b>파싱</b>을 줄이 빌 때까지 막는 매퍼. 러너가 {@code readValue}를
     * 폴링 스레드에서 부르는 것을 이용한다 — 시간이 아니라 <b>상태</b>로 막으므로
     * 고친 구현에서는 즉시 통과한다.
     */
    private static final class LaneDrainGate extends ObjectMapper {

        private final transient AtomicReference<SqsIntakeRunner> runner;
        private final String gatedEventId;

        LaneDrainGate(AtomicReference<SqsIntakeRunner> runner, String gatedEventId) {
            this.runner = runner;
            this.gatedEventId = gatedEventId;
        }

        @Override
        public <T> T readValue(String content, Class<T> valueType) {
            if (content.contains(gatedEventId)) {
                awaitLanesIdle();
            }
            return super.readValue(content, valueType);
        }

        private void awaitLanesIdle() {
            SqsIntakeRunner target = runner.get();
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (target.inFlight() > 0) {
                if (System.nanoTime() > deadline) {
                    // 조용히 통과시키면 이 검사가 아무것도 안 잰 채 초록이 된다.
                    throw new AssertionError("줄이 10초 안에 안 비었다 — 게이트가 뜻을 잃었다");
                }
                Thread.onSpinWait();
            }
        }
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

    /**
     * 🔴 <b>한 회차의 같은 줄 알림은 「작업 하나」다</b> (감사 G1의 구조를 직접 잰다).
     *
     * <p><b>왜 이것이 따로 필요한가</b>: 위 게이트 검사는 <b>파싱이 제출보다 먼저</b>임을
     * 재지만, 그것만으로는 「줄별로 묶였다」가 안 잡힌다 — 다 파싱해 놓고 알림마다 따로
     * 제출해도 그 검사는 초록이다(내가 그 되돌림을 넣어 <b>실제로 초록인 것을 봤다</b>).
     *
     * <p>실행기의 셈이 그 구조를 그대로 드러낸다. {@code inFlight}는 <b>제출된 작업 수</b>이지
     * 알림 수가 아니다 — 한 줄에 알림 셋이 와도 묶였으면 <b>1</b>, 안 묶였으면 <b>3</b>이다.
     */
    @Test
    @Timeout(20)
    void 한_회차의_같은_줄_알림_셋이_작업_하나로_들어간다() {
        FakeQueue queue = new FakeQueue();
        queue.enqueue("evt-1", "live-A-001", "7");
        queue.enqueue("evt-2", "live-A-001", "7");
        queue.enqueue("evt-3", "live-A-001", "7");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch hold = new CountDownLatch(1);
        SqsIntakeRunner runner = newRunner(queue, envelope -> {
            entered.countDown();
            await(hold);
            return ProcessResult.PROCESSED;
        });

        runner.pollOnce();

        assertThat(awaitLatch(entered))
                .as("붙이기가 시작조차 안 했으면 아래가 아무것도 안 잰다").isTrue();
        assertThat(runner.inFlight())
                .as("셋이 각각 제출됐으면 3이다 — 묶였으면 1")
                .isEqualTo(1);
        assertThat(runner.acceptedInLastPoll())
                .as("그래도 「받은 알림 수」는 셋이다 — 백프레셔가 세는 단위는 알림이다")
                .isEqualTo(3);
        hold.countDown();
    }

    /**
     * 🔴 <b>같은 회원을 가리키는 다른 문자열은 같은 줄이다 — 러너 층에서 잰다</b>(감사 G3).
     *
     * <p>{@code LaneKey}가 {@code "7"}·{@code "007"}·{@code "+7"}을 회원 7로 뭉치도록
     * 고친 것은 라운드 1(I5)이다. <b>그런데 러너가 그것을 실제로 쓰는지는 아무도 안 쟀다</b> —
     * 감사자가 러너의 줄 이름을 원문 {@code streamerId}로 되돌렸더니 검사 여섯 클래스가
     * <b>전부 초록</b>이었다. {@code LaneKeyTest}는 부품만 재고, 순서 검사는 두 알림의
     * {@code streamerId}가 애초에 같은 글자라 <b>정규화를 안 해도 통과한다.</b>
     *
     * <p>여기서는 두 알림의 원문을 <b>일부러 다르게</b> 준다({@code "7"}·{@code "007"}).
     * 정규화가 살아 있으면 같은 줄이라 앞엣것의 실패가 뒤엣것을 멈추고, 죽으면 다른 줄이라
     * 뒤엣것이 그대로 돈다.
     *
     * <p><b>지금 막는 것보다 앞으로 막는 것이 크다</b>: 태스크 7이 「재부착도 같은 정규화를
     * 쓴다」에 기대는데, 두 발행자가 서로 다른 시스템이다(1번의 SQS 봉투 대 clip 명부의 칸).
     * 값이 갈릴 여지가 바로 그 자리다.
     */
    @Test
    @Timeout(20)
    void 같은_회원을_가리키는_다른_문자열은_같은_줄이다() {
        FakeQueue queue = new FakeQueue();
        queue.enqueue("evt-1", "live-A-001", "7");
        queue.enqueue("evt-2", "live-A-002", "007");
        List<String> seen = Collections.synchronizedList(new ArrayList<>());
        SqsIntakeRunner runner = newRunner(queue, envelope -> {
            seen.add(envelope.eventId());
            return envelope.eventId().equals("evt-1")
                    ? ProcessResult.RETRY_LATER : ProcessResult.PROCESSED;
        });

        runner.pollOnce();

        assertThat(runner.awaitIdle(IDLE_BUDGET)).isTrue();
        assertThat(seen)
                .as("\"7\"과 \"007\"이 다른 줄이면 뒤엣것이 그대로 돈다")
                .containsExactly("evt-1");
        assertThat(queue.deleted()).isEmpty();
    }

    /**
     * 🔴 <b>이 커밋의 둘째 목표를 실패 경로에서 잰다</b>(감사 G6).
     *
     * <p>「회차 중단 → 줄 중단」의 이득은 <b>「멀쩡한 남의 방송까지 미뤄지지 않는다」</b>인데,
     * 그것을 재는 검사가 없었다 — 실패 검사는 둘 다 같은 줄이고, 겹침 검사는 전부 성공
     * 경로다. 그래서 {@code dropPending}을 「전 줄을 비운다」로 바꿔도 아무 검사가 안 깨졌다.
     *
     * <p>여기서는 한 회차에 <b>실패하는 스트리머와 멀쩡한 스트리머</b>를 같이 넣는다.
     */
    @Test
    @Timeout(20)
    void 한_스트리머가_실패해도_다른_스트리머는_그_회차에_처리된다() {
        FakeQueue queue = new FakeQueue();
        queue.enqueue("evt-A1", "live-A-001", "7");
        queue.enqueue("evt-A2", "live-A-002", "7");
        queue.enqueue("evt-B1", "live-B-001", "8");
        List<String> seen = Collections.synchronizedList(new ArrayList<>());
        SqsIntakeRunner runner = newRunner(queue, envelope -> {
            seen.add(envelope.eventId());
            return envelope.eventId().startsWith("evt-A")
                    ? ProcessResult.RETRY_LATER : ProcessResult.PROCESSED;
        });

        runner.pollOnce();

        assertThat(runner.awaitIdle(IDLE_BUDGET)).isTrue();
        assertThat(seen).contains("evt-B1");
        assertThat(seen).doesNotContain("evt-A2");
        assertThat(queue.deleted())
                .as("남의 방송은 이 회차에 끝났고, 실패한 줄의 것은 큐에 남는다")
                .containsExactly("evt-B1");
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
     * {@code continue}는 그것을 <b>지워 버린다.</b> 앞 알림이 아직 큐에 남아 있는데
     * 뒤엣것을 먼저 처분하는 모양이고, 이 설계가 막으려는 「뒤가 먼저 반영된다」의 한 갈래다.
     *
     * <p>🔴 <b>이름을 「읽어 보지도 않는다」에서 바꿨다</b>(감사 G5와 같은 결). G1을 고치며
     * 1단계가 회차의 봉투를 <b>다 파싱하게</b> 됐다 — 줄별로 묶으려면 그래야 한다.
     * 파싱은 부작용이 없고, 지우고 넘기는 <b>처분</b>은 여전히 받은 순서대로 2단계에서만
     * 일어나므로 이 검사가 지키는 것은 그대로다. 이름이 재는 것과 어긋나지 않게 고쳤다.
     */
    @Test
    @Timeout(20)
    void 줄이_가득_찬_뒤의_알림은_지우지도_처리하지도_않는다() {
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

    /**
     * 🔴 <b>포화가 이어지는 동안 같은 줄을 반복해 찍지 않는다</b>(감사 라운드 3 H4).
     * {@code SATURATED_PAUSE}가 200ms라 매 회차 찍으면 <b>초당 다섯 줄</b>이고,
     * <b>대량 재부착이 정확히 이 카드가 노리는 시나리오다</b> — 복구가 돌 때마다
     * 로그가 밀려 정작 그 옆의 {@code chat.reattach.*}가 안 보인다.
     *
     * <p><b>「얼마나 오래 찼나」를 이 줄이 안 져도 된다.</b> 포화 회차는 큐를 두드리지
     * 않으므로 마지막 폴링 성공 시각이 안 움직이고, 2분을 넘기면 health가
     * {@code letterIntake=stalled}로 DOWN을 준다({@code 가득_찬_회차는_큐가_건강하다고_기록하지_않는다}
     * 와 {@code CollectorHealthTest}가 그 사슬을 잰다). 깊이는 {@code inFlight=}가 말한다.
     *
     * <p>🔴 <b>「한 번만」과 「영영 한 번만」은 다르다.</b> 들어설 때 찍고 <b>빠져나올 때
     * 깃발을 안 되돌리면</b>, 두 번째 포화는 아무 줄도 안 남긴다 — 첫 번째만 보이는 로그는
     * 안 보이는 로그보다 나쁘다(「그때 한 번뿐이었다」로 읽힌다). 그래서 이 검사는
     * <b>포화 → 해소 → 다시 포화</b>를 한 번에 돈다.
     *
     * <p>문항 2 — 「아예 안 찍는」 구현도 「반복이 없다」는 통과하고, 「매번 찍는」 구현도
     * 「두 번 이상 있다」는 통과한다. 그래서 <b>정확히 둘</b>을 재고 값까지 본다.
     * 문항 5 — 잠든 회차 수({@code slept})를 같이 재서 <b>실제로 다섯 바퀴를 돌았다</b>를
     * 확인한다. 안 재면 루프가 두 바퀴만 돌고 끝나도 「반복 없음」이 참이다.
     */
    @Test
    @Timeout(20)
    void 포화가_이어져도_한_줄만_찍고_다시_포화되면_또_찍는다() {
        FakeQueue queue = new FakeQueue();
        queue.enqueue("evt-0", "live-0", "0");
        CountDownLatch 첫째 = new CountDownLatch(1);
        CountDownLatch 둘째 = new CountDownLatch(1);
        List<Duration> slept = Collections.synchronizedList(new ArrayList<>());
        SqsIntakeRunner[] box = new SqsIntakeRunner[1];
        SqsIntakeRunner runner = newRunner(queue, new IntakeStatus(true), 1, envelope -> {
            await(envelope.eventId().equals("evt-0") ? 첫째 : 둘째);
            return ProcessResult.PROCESSED;
        }, duration -> {
            slept.add(duration);
            if (slept.size() == 2) {
                // 포화를 푼다 — 줄이 실제로 빌 때까지 기다린 뒤 다음 편지를 놓는다.
                첫째.countDown();
                assertThat(box[0].awaitIdle(Duration.ofSeconds(5))).isTrue();
                queue.enqueue("evt-1", "live-1", "1");
            }
            return slept.size() >= 3;
        });
        box[0] = runner;

        try (LogCaptor captor = new LogCaptor()) {
            runner.runLoop();

            // 바퀴 1 제출 · 2·3 포화(sleep 1·2) · 4 해소 후 제출 · 5 다시 포화(sleep 3)
            assertThat(slept).containsExactly(SqsIntakeRunner.SATURATED_PAUSE,
                    SqsIntakeRunner.SATURATED_PAUSE, SqsIntakeRunner.SATURATED_PAUSE);
            assertThat(captor.messages())
                    .filteredOn(line -> line.startsWith("broadcast.intake.saturated"))
                    .as("이어지는 동안 한 줄 · 다시 포화되면 또 한 줄")
                    .containsExactly("broadcast.intake.saturated inFlight=1",
                            "broadcast.intake.saturated inFlight=1");
        }
        둘째.countDown();
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
     * 🔴 <b>삭제 실패는 「남는다」 — 다음 폴링 성공이 지우지 않는다</b>(계획 검증 T3·I7 · 감사 G2).
     *
     * <p>삭제는 폴링 스레드가 아니라 줄에서 일어나므로 {@code pollOnce}의 catch가 못 받는다.
     * 그래서 따로 받아 health에 남기는데, <b>폴링 실패와 같은 칸에 담으면
     * {@code pollSucceeded}가 그것을 지운다.</b> 삭제만 실패하는 동안에도 수신은 계속
     * 성공하므로, 그러면 롱폴링 주기(20초)마다 한 번 깜빡일 뿐 health는 대체로 초록이다 —
     * 계획 검증 I7이 겨눈 문장이 정확히 그것이었다.
     *
     * <p>🔴 <b>둘째 회차가 이 검사의 핵심이다.</b> 그것이 없으면 「한 회차 안에서만 아프다」와
     * 「계속 아프다」가 구분되지 않는다 — 감사자가 <b>코드를 한 글자도 안 고치고</b>
     * 이 한 줄만 더해 결함을 드러냈다.
     */
    @Test
    @Timeout(20)
    void 삭제가_실패하면_다음_회차가_성공해도_계속_아프다고_남는다() {
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
        assertThat(status.snapshot().healthy())
                .as("첫 회차 직후에는 아프다 — 여기가 초록이면 아래가 아무것도 안 잰다")
                .isFalse();
        assertThat(status.snapshot().lastDeleteFailureReason()).isEqualTo("SqsException");

        // 큐가 빈 다음 회차. 알림은 없지만 receiveMessage는 성공한다 — 운영에서는
        // 롱폴링이 20초마다 이것을 한다.
        runner.pollOnce();

        assertThat(status.snapshot().healthy())
                .as("폴링 성공이 삭제 실패를 지우면 안 된다")
                .isFalse();
        assertThat(status.snapshot().lastDeleteFailureReason()).isEqualTo("SqsException");
    }

    /** 회복은 삭제 성공이 지운다. 짝이 없으면 「영영 아프다」도 통과한다(문항 2). */
    @Test
    @Timeout(20)
    void 삭제가_다시_되면_아팠던_기록이_지워진다() {
        FakeQueue queue = new FakeQueue();
        queue.failDeletes();
        queue.enqueue("evt-1", "live-A-001", "7");
        IntakeStatus status = new IntakeStatus(true);
        SqsIntakeRunner runner = newRunner(queue, status, 50,
                envelope -> ProcessResult.PROCESSED);
        runner.pollOnce();
        assertThat(runner.awaitIdle(IDLE_BUDGET)).isTrue();
        assertThat(status.snapshot().healthy()).isFalse();

        queue.allowDeletes();
        queue.enqueue("evt-2", "live-A-001", "7");
        runner.pollOnce();
        assertThat(runner.awaitIdle(IDLE_BUDGET)).isTrue();

        assertThat(status.snapshot().healthy()).isTrue();
        assertThat(status.snapshot().lastDeleteFailureReason()).isNull();
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
     * 🔴 <b>줄 비우기 예산은 2초다</b>(계획 검증 M3). 6초를 끼우면 합이 21초라
     * <b>세션 닫기가 잘려 구독이 반납 안 되고 계정 자리가 남는다.</b>
     *
     * <p><b>이 검사는 이 값 하나만 못박는다.</b> 「종료 예산이 유예를 안 넘는다」는 더 큰
     * 주장은 <b>{@code ShutdownBudgetTest}로 옮겼다</b> — 여기서 재던 {@code JOIN + DRAIN}은
     * 네 항 중 둘뿐이라, 세션 닫기·flush가 아무리 커져도 초록이었다(감사 G5).
     * 이름이 주장하는 것과 재는 것을 가르지 않으려고 범위를 좁혀 이름도 바꿨다.
     */
    @Test
    void 줄_비우기_예산이_2초다() {
        assertThat(SqsIntakeLoop.DRAIN_WAIT).isEqualTo(Duration.ofSeconds(2));
    }

    /**
     * 🔴 <b>줄이 예산 안에 안 비면 경고를 남기고 넘어간다</b>(감사 축 B의 B12 — 미시험이었다).
     *
     * <p><b>매달리면 안 되는 이유</b>: 종료 유예가 20초인데 붙이기 하나의 최악은
     * auth 5초 + 치지직 수립 15초라 <b>기다려서 끝낼 수 있는 값이 아니다.</b> 그래서 예산을
     * 넘기면 포기하고 다음 단계(세션 닫기)로 간다 — 그것을 못 하면 세션 닫기가 통째로 잘린다.
     *
     * <p><b>못 끝낸 붙이기의 알림은 안 지워진다</b>(삭제가 줄 안에 있다). 가시성 시한이
     * 지나면 다시 오므로 유실이 아니라 지연이다 — 그 사실을 {@code deleted()}로 같이 잰다.
     */
    @Test
    @Timeout(30)
    void 줄이_예산_안에_안_비면_경고를_남기고_넘어간다() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch hold = new CountDownLatch(1);
        FakeQueue queue = new FakeQueue();
        queue.enqueue("evt-1", "live-A-001", "7");
        SqsIntakeRunner runner = newRunner(queue, envelope -> {
            entered.countDown();
            await(hold);
            return ProcessResult.PROCESSED;
        });
        SqsIntakeLoop loop = new SqsIntakeLoop(runner);

        loop.start();
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        try (LogCaptor captor = new LogCaptor()) {
            loop.stop();   // 붙이기가 hold에 붙들려 DRAIN_WAIT를 넘긴다

            assertThat(captor.levelOf("broadcast.intake.drain_timeout")).isEqualTo(Level.WARN);
        }
        assertThat(queue.deleted())
                .as("못 끝낸 붙이기의 알림은 안 지워져 다시 온다 — 유실이 아니라 지연이다")
                .isEmpty();
        hold.countDown();
    }

    /**
     * 🔴 <b>join 예산을 넘긴 폴링 스레드를 죽이지 않는다</b>(감사 축 B의 B11 — 미시험이었다).
     *
     * <p>롱폴링에 들어간 {@code receiveMessage}는 인터럽트로도 안 끊기고, 이 서버는
     * 그것을 <b>일부러 안 죽인다</b> — 스레드는 데몬이라 JVM 종료를 안 붙들고,
     * 강제로 끊으면 이미 받아 둔 알림의 운명이 흐려진다. 대신 <b>경고를 남긴다</b>:
     * 조용히 넘어가면 「종료가 끝났다」와 「아직 편지를 처리 중이다」가 구분되지 않는다.
     *
     * <p><b>그 창에서 알림을 안 잃는다는 것을 같이 잰다</b> — 롱폴링이 돌아온 뒤에도
     * {@code running}이 false라 그 회차는 아무것도 안 꺼내고 루프가 끝난다.
     */
    @Test
    @Timeout(30)
    void 롱폴링에_잠긴_회차는_join을_넘겨도_안_죽이고_경고만_남긴다() throws Exception {
        CountDownLatch receiving = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FakeQueue queue = new FakeQueue();
        queue.blockReceive(receiving, release);
        queue.enqueue("evt-1", "live-A-001", "7");
        SqsIntakeRunner runner = newRunner(queue, envelope -> ProcessResult.PROCESSED);
        SqsIntakeLoop loop = new SqsIntakeLoop(runner);

        loop.start();
        assertThat(receiving.await(5, TimeUnit.SECONDS))
                .as("롱폴링에 들어가지 않았으면 아래가 다른 것을 잰다").isTrue();
        try (LogCaptor captor = new LogCaptor()) {
            loop.stop();   // join 2초를 넘긴다

            assertThat(captor.levelOf("broadcast.intake.loop_still_running")).isEqualTo(Level.WARN);
        }

        release.countDown();
        assertThat(queue.deleted())
                .as("멈추라는 신호 뒤에는 그 회차가 아무것도 처분하지 않는다")
                .isEmpty();
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
        return newRunner(queue, status, maxInFlight, handler, sleeper, new ObjectMapper());
    }

    private SqsIntakeRunner newRunner(FakeQueue queue, IntakeStatus status, int maxInFlight,
                                      Function<LifecycleEnvelope, ProcessResult> handler,
                                      SqsIntakeRunner.Sleeper sleeper, ObjectMapper mapper) {
        BroadcastEventProcessor processor = mock(BroadcastEventProcessor.class);
        given(processor.process(any())).willAnswer(
                invocation -> handler.apply(invocation.getArgument(0)));
        // 검사가 끝나면 JVM이 가상 스레드를 안 붙드므로 따로 닫지 않는다 —
        // 닫으면 awaitIdle 뒤에도 도는 마지막 작업이 거절될 수 있다.
        return new SqsIntakeRunner(queue, properties(), status, processor, mapper,
                new StreamerSerialExecutor(maxInFlight), sleeper);
    }

    private static IntakeProperties properties() {
        return new IntakeProperties(true, QUEUE_URL, "ap-northeast-2", "",
                Duration.ofSeconds(20), 10);
    }

    private static boolean awaitLatch(CountDownLatch latch) {
        try {
            return latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
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
        private volatile CountDownLatch receiveEntered;
        private volatile CountDownLatch receiveRelease;
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

        void allowDeletes() {
            this.deletesFail = false;
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

        void blockReceive(CountDownLatch entered, CountDownLatch release) {
            this.receiveEntered = entered;
            this.receiveRelease = release;
        }

        @Override
        public ReceiveMessageResponse receiveMessage(ReceiveMessageRequest request) {
            receiveCalls.incrementAndGet();
            if (receiveRelease != null) {
                // 롱폴링을 흉내 낸다 — 인터럽트로 안 끊기는 것까지 같다.
                receiveEntered.countDown();
                await(receiveRelease);
            }
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
