package com.pokeclip.chat.collector.broadcast.intake;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.pokeclip.chat.collector.broadcast.BroadcastEventProcessor;
import com.pokeclip.chat.collector.broadcast.ProcessResult;
import com.pokeclip.chat.collector.broadcast.attach.StreamerSerialExecutor;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageResponse;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;
import software.amazon.awssdk.services.sqs.model.SqsException;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 큐에서 편지를 꺼내 판정기에 넘기고, <b>더 볼 일이 없어진 것만</b> 지운다.
 *
 * <p><b>다중 세션 문항(multi-session-test-reality)</b> — 이 부품에는 세션이 없다.
 * 문항 1(세션 하나로 돌려도 통과하는가)은 잴 대상이 없어 해당하지 않는다.
 * 문항 3(의도한 동시성이 환경에 막히는가)은 <b>모양을 바꿔 걸린다</b>: 이 카드에서
 * 중요한 것은 "편지 여럿이 한 회차에 실제로 온다"이고, 그것이 안 겹치면
 * 회차 중단 검사가 아무것도 안 잰다. 그래서 받은 <b>회차 수</b>를 상대(가짜 큐) 쪽에서
 * 센다 — 우리 쪽에서 "셋을 줬다"고 세지 않는다.
 * 문항 2·4·5는 검사마다 주석으로 답을 남겼다.
 */
class SqsIntakeRunnerTest {

    private static final String QUEUE_URL = "http://localhost:4566/000000000000/broadcast.fifo";

    @Test
    void 처리된_편지는_지운다() {
        FakeQueue queue = FakeQueue.with(startedJson("evt-1", "s1", 1L));
        SqsIntakeRunner runner = newRunner(queue, ProcessResult.PROCESSED);

        runner.pollOnce();
        drain(runner);

        assertThat(queue.deleted()).containsExactly("rh-0");
    }

    /**
     * 낡아서 무시한 편지도 다시 받을 이유가 없다. 안 지우면 FIFO라 같은 방송의 뒤 편지가
     * 전부 막힌다.
     * <p>문항 4: 「무조건 지운다」로 바꿔도 통과한다 — 그 방향은 아래 {@code RETRY_LATER}·
     * 예외 검사 둘이 맡는다. 한쪽만 지우지 마라.
     */
    @Test
    void 낡아서_무시한_편지도_지운다() {
        FakeQueue queue = FakeQueue.with(startedJson("evt-1", "s1", 1L));
        SqsIntakeRunner runner = newRunner(queue, ProcessResult.IGNORED_STALE);

        runner.pollOnce();
        drain(runner);

        assertThat(queue.deleted()).containsExactly("rh-0");
    }

    /** 우리가 못 읽는다고 판정된 편지도 다시 받아야 못 읽는다. */
    @Test
    void 읽을_수_없다고_판정된_편지도_지운다() {
        FakeQueue queue = FakeQueue.with(startedJson("evt-1", "s1", 1L));
        SqsIntakeRunner runner = newRunner(queue, ProcessResult.UNREADABLE);

        runner.pollOnce();
        drain(runner);

        assertThat(queue.deleted()).containsExactly("rh-0");
    }

    /**
     * 편지 셋 중 <b>둘째가 {@code RETRY_LATER}</b>다. 셋째는 건드리지 않아야 한다.
     * 계속 돌면 같은 방송의 뒤 편지가 먼저 처리돼 메모의 {@code lastSequence}를 올리고,
     * 재전송된 앞 편지가 {@code IGNORED_STALE}로 걸러진 뒤 삭제된다 — 재전송으로도
     * 못 고치는 영구 유실이다(clip PR #82 P1).
     *
     * <p>🔴 <b>POK-219에서 멈추는 단위가 「회차」에서 「그 줄」로 바뀌었다.</b> 예전에는
     * 이 회차의 나머지를 통째로 안 봤는데(멀쩡한 남의 방송까지 미뤄졌다), 이제는 그
     * 스트리머의 줄에 쌓인 것만 버린다({@code StreamerSerialExecutor.dropPending}).
     * <b>이 검사가 그대로 서는 이유는 셋이 같은 줄이기 때문이다</b> —
     * {@code startedJson}이 {@code streamerId}를 셋 다 {@code "42"}로 준다.
     * 방송 번호는 다르지만 줄은 스트리머로 가른다.
     *
     * <p><b>문항 3</b>: 러너가 애초에 하나씩만 받아오면 이 검사는 아무것도 안 잰다.
     * 그래서 ① 가짜 큐가 <b>받은 회차 수</b>를 세고 1인지 단언하고, ② 가짜 큐가
     * {@code maxNumberOfMessages}를 실제로 지킨다 — 셋이 한 회차에 진짜로 왔다는 뜻이다.
     * <b>실측(주입 J)</b>: 운영 설정을 {@code max-messages=1}로 낮추면 이 검사가
     * {@code process}를 한 번밖에 못 세 빨간불이 된다(확인함).
     * <p>문항 4: {@code times(1)}만 세면 <b>둘째를 지우고 첫째를 안 지워도</b> 통과한다 —
     * 그래서 지운 편지의 이름까지 단언한다.
     * <p>문항 5: {@code dropPending} 두 줄을 지우면 셋째가 돌아 빨간불(확인함).
     */
    @Test
    void 못_지운_편지가_나오면_같은_줄의_뒤_편지를_안_건드린다() {
        FakeQueue queue = FakeQueue.with(
                startedJson("evt-1", "s1", 1L),
                startedJson("evt-2", "s2", 1L),
                startedJson("evt-3", "s3", 1L));
        BroadcastEventProcessor processor = mock(BroadcastEventProcessor.class);
        given(processor.process(any())).willReturn(
                ProcessResult.PROCESSED, ProcessResult.RETRY_LATER, ProcessResult.PROCESSED);
        SqsIntakeRunner runner = newRunner(queue, processor);

        runner.pollOnce();
        drain(runner);

        assertThat(queue.receiveCount())
                .as("한 회차에 셋이 실제로 오지 않으면 이 검사는 아무것도 안 잰다")
                .isEqualTo(1);
        verify(processor, times(2)).process(any());
        assertThat(queue.deleted())
                .as("못 지운 편지가 나오기 전까지만 지운다")
                .containsExactly("rh-0");
    }

    /**
     * 🔴 <b>처리가 막혀도 이 회차는 성공이다 — 백오프를 걸지 마라.</b>
     *
     * <p>「처리를 못 했는데 왜 성공인가」가 자연스러운 물음이라 이 줄을 뒤집고 싶어진다.
     * <b>실제로 로컬 리뷰 1라운드에서 뒤집었다가 되돌렸다.</b> 되돌린 근거를 남긴다.
     *
     * <p><b>이 반환값이 답하는 물음은 「큐에 닿았나」이지 「처리가 됐나」가 아니다.</b>
     * 못 지운 편지는 <b>가시성 타임아웃 동안 숨겨지므로</b> 곧바로 다시 폴링해도 그 편지는
     * 안 온다 — 대신 <b>다른 방송의 편지가 온다.</b> 큐가 비어 있으면 롱폴링이 20초를
     * 붙든다. 즉 <b>재시도 간격은 큐가 이미 조절하고 있고</b>, 여기 백오프를 얹으면
     * 그 위에 우리 대기가 겹친다.
     *
     * <p><b>얹었을 때 무슨 일이 나는지 실측했다</b>(auth가 계속 아파 모든 시작 편지가
     * {@code RETRY_LATER}가 되는 상황):
     *
     * <pre>
     * 1회차 1초 · 2회차 2초 · 3회차 4초 · … · 7회차 60초 · 8회차 60초
     * 8회차까지 누적 정지 183초
     * </pre>
     *
     * <p>그동안 <b>큐는 멀쩡한데 아무 편지도 안 꺼낸다</b> — auth와 무관한 스트리머가
     * 방송을 시작해도 최대 60초 뒤에나 붙고, 그 1분치 채팅은 되돌릴 수 없다.
     * <b>한 스트리머의 열쇠 문제가 전체 수집을 멈추는 모양이 되므로 얹지 않는다.</b>
     *
     * <p>문항 2: 「늘 true」인 구현도 이 단언을 통과한다 — 그 방향은 위
     * {@code 큐에_못_닿으면_예외가_안_나가고_아프다고_남는다}가 잰다(false를 요구한다).
     * 둘을 같이 봐야 「큐 접근만 백오프를 만든다」가 고정된다.
     */
    @Test
    void 처리가_막혀도_큐에_닿은_회차는_성공으로_센다() {
        IntakeStatus status = new IntakeStatus(true);
        SqsIntakeRunner runner = newRunner(
                FakeQueue.with(startedJson("evt-1", "s1", 1L)),
                processorReturning(ProcessResult.RETRY_LATER), status);

        assertThat(runner.pollOnce())
                .as("큐에는 닿았다 — 여기서 false를 주면 백오프가 무관한 방송까지 멈춘다")
                .isTrue();
        drain(runner);

        IntakeStatus.Snapshot snap = status.snapshot();
        assertThat(snap.healthy()).isTrue();
        assertThat(snap.lastPollSucceededAt()).isNotNull();
    }

    /**
     * 🔴 <b>실패 로그에 예외를 통째로 실으면 감추려던 것이 그대로 나간다.</b>
     *
     * <p>주석은 「예외 타입만 남긴다 — 메시지에 큐 주소·계정 번호가 실릴 수 있다」인데,
     * 마지막 인자로 넘긴 예외는 SLF4J가 <b>throwable로 인식해 메시지와 스택트레이스를
     * 함께 렌더한다.</b> 정제한다고 적어 놓고 정반대로 동작하던 자리다(codex P2, 재현함).
     *
     * <p><b>{@code LogCaptor.messages()}로는 못 잡는다</b> — 그것은 포맷된 메시지만 주고
     * throwable은 별도 필드다. 기존 유출 검사들이 이 자리를 못 본 이유이기도 하다.
     * 그래서 {@code events()}에서 <b>throwable 자체가 붙어 있는지</b>를 본다.
     *
     * <p>문항 2: 「메시지에 주소가 없다」로만 재면 자동으로 참이다 — 주소는 원래 메시지가
     * 아니라 throwable에 있다. 그래서 붙어 있는지를 직접 본다.
     * <p>문항 5: 두 자리 중 하나라도 {@code e}를 되살리면 빨간불(확인함).
     */
    @Test
    void 실패_로그에_예외를_통째로_싣지_않는다() {
        try (LogCaptor captor = new LogCaptor()) {
            // ① 큐에 못 닿는 갈래 — SDK 예외 메시지에 큐 주소와 계정 번호가 들어 있다.
            newRunner(FakeQueue.unreachable(), ProcessResult.PROCESSED).pollOnce();
            // ② 판정이 던지는 갈래 — 예외 메시지가 무엇이든 실리면 안 된다.
            BroadcastEventProcessor throwing = mock(BroadcastEventProcessor.class);
            given(throwing.process(any())).willThrow(new IllegalStateException("secret-detail"));
            SqsIntakeRunner failing = newRunner(FakeQueue.with(startedJson("evt-1", "s1", 1L)), throwing);
            failing.pollOnce();
            // handle_failed는 이제 줄 스레드에서 난다. 안 기다리면 아래 긍정 단언이
            // 0건을 재고 빨간불이 된다 — LogCaptor는 root에 붙어 스레드를 안 가린다.
            drain(failing);

            assertThat(captor.messages())
                    .as("두 갈래가 실제로 로그를 남겼는가 — 안 남으면 아래가 0건을 재고 통과한다")
                    .anyMatch(l -> l.startsWith("broadcast.intake.poll_failed"))
                    .anyMatch(l -> l.startsWith("broadcast.intake.handle_failed"));

            assertThat(captor.events().stream()
                    .filter(e -> e.getFormattedMessage().startsWith("broadcast.intake."))
                    .filter(e -> e.getThrowableProxy() != null)
                    .map(ILoggingEvent::getFormattedMessage))
                    .as("예외가 붙으면 그 메시지와 스택트레이스가 통째로 렌더된다")
                    .isEmpty();
        }
    }

    private static BroadcastEventProcessor processorReturning(ProcessResult result) {
        BroadcastEventProcessor processor = mock(BroadcastEventProcessor.class);
        given(processor.process(any())).willReturn(result);
        return processor;
    }

    /**
     * 🔴 <b>{@code process()}가 던지면 그 편지를 지우지 않는다.</b> 태스크 5는 DB 예외를
     * 일부러 안 삼켰다(방어를 두 겹 두면 어느 쪽이 일하는지 흐려진다) — 그래서 여기서 받는다.
     * DB가 잠깐 죽었을 때 종료 편지를 지우면 메모가 영영 안 남고, 뒤늦게 온 시작 편지가
     * 세션을 연다. 이 카드가 막으려는 실패 그 자체다.
     *
     * <p>🔴 <b>POK-219에서 멈추는 단위가 「회차」에서 「그 줄」로 바뀌었다.</b> 여기도
     * 둘이 같은 줄이라(둘 다 {@code streamerId="42"}) 단언은 그대로 선다.
     * <b>catch의 폭이 {@code Throwable}인 것</b>은 {@code AsyncIntakeTest}가 {@code Error}로 잰다.
     *
     * <p>문항 4: 예외를 「지우지 않는다」로만 잰다면 <b>뒤 편지를 계속 처리해도</b> 통과한다 —
     * 그러면 위 {@code RETRY_LATER}와 달리 순서가 뒤집힌다. 그래서 뒤 편지가 판정기에
     * 안 갔는지도 같이 잰다.
     * <p>문항 5: {@code catch}에서 {@code dropPending}을 빼면 뒤 편지가 돌아 빨간불(확인함).
     */
    @Test
    void 판정_중_예외가_나면_지우지_않고_같은_줄의_뒤_편지를_안_건드린다() {
        FakeQueue queue = FakeQueue.with(
                startedJson("evt-1", "s1", 1L),
                startedJson("evt-2", "s2", 1L));
        BroadcastEventProcessor processor = mock(BroadcastEventProcessor.class);
        given(processor.process(any())).willThrow(new IllegalStateException("DB 연결 끊김"));
        SqsIntakeRunner runner = newRunner(queue, processor);

        try (LogCaptor captor = new LogCaptor()) {
            runner.pollOnce();
            drain(runner);

            assertThat(captor.levelOf("broadcast.intake.handle_failed")).isEqualTo(Level.WARN);
        }

        assertThat(queue.deleted()).isEmpty();
        verify(processor, times(1)).process(any());
    }

    /**
     * 재시도해도 계속 실패하는데 안 지우면 같은 그룹의 뒤 편지가 전부 막힌다.
     * 지웠으므로 <b>중단 사유가 아니다</b> — 뒤 편지는 같은 회차에서 처리한다.
     *
     * <p>문항 2: 삭제만 단언하면 러너가 편지를 통째로 무시해도 통과한다 —
     * 그래서 판정기에 안 갔다는 것과 뒤 편지는 갔다는 것을 같이 잰다.
     * <p>문항 5: {@code catch}에서 삭제를 빼면 빨간불(확인함).
     */
    @Test
    void 읽을_수_없는_본문은_지우고_뒤_편지는_그대로_처리한다() {
        FakeQueue queue = FakeQueue.with("{망가진 json", startedJson("evt-2", "s2", 1L));
        BroadcastEventProcessor processor = mock(BroadcastEventProcessor.class);
        given(processor.process(any())).willReturn(ProcessResult.PROCESSED);
        SqsIntakeRunner runner = newRunner(queue, processor);

        try (LogCaptor captor = new LogCaptor()) {
            runner.pollOnce();
            drain(runner);

            assertThat(captor.levelOf("broadcast.intake.unreadable_dropped")).isEqualTo(Level.WARN);
        }

        // rh-0은 폴링 스레드가(줄에 못 넣는다), rh-1은 줄이 지운다 — 순서는 그래서 고정이다.
        assertThat(queue.deleted()).containsExactly("rh-0", "rh-1");
        verify(processor, times(1)).process(any());
    }

    /**
     * 큐에 못 닿는 것은 우리가 고칠 수 없다. 예외를 밖으로 던지면 루프가 죽어 편지를
     * 영영 못 받는다 — 서버는 UP인 채로.
     * <p>문항 2: {@code healthy()}가 무조건 false를 줘도 통과한다 — 아래 양성 대조가 막는다.
     * <p>문항 5: {@code catch}를 떼면 빨간불(확인함).
     */
    @Test
    void 큐에_못_닿으면_예외가_안_나가고_아프다고_남는다() {
        FakeQueue queue = FakeQueue.unreachable();
        IntakeStatus status = new IntakeStatus(true);
        SqsIntakeRunner runner = newRunner(queue, mock(BroadcastEventProcessor.class), status);

        assertThat(runner.pollOnce()).isFalse();
        assertThat(status.snapshot().healthy()).isFalse();
    }

    /** 양성 대조. 이게 없으면 {@code healthy()}가 늘 false여도 위 검사가 통과한다(문항 2). */
    @Test
    void 큐에_닿으면_건강하다고_남는다() {
        FakeQueue queue = FakeQueue.with();   // 빈 응답도 닿은 것이다
        IntakeStatus status = new IntakeStatus(true);
        SqsIntakeRunner runner = newRunner(queue, mock(BroadcastEventProcessor.class), status);

        assertThat(runner.pollOnce()).isTrue();
        assertThat(status.snapshot().healthy()).isTrue();
        assertThat(status.snapshot().lastPollSucceededAt()).isNotNull();
    }

    /**
     * 한 번 아팠다가 나으면 다시 건강해야 한다. 안 지우면 health가 영영 DOWN이라
     * 회복을 아무도 모른다.
     * <p>문항 5: {@code pollSucceeded}에서 사유 지우기를 빼면 빨간불(확인함).
     */
    @Test
    void 다시_닿으면_아팠던_기록이_지워진다() {
        FakeQueue queue = FakeQueue.reachableOn(false, true);
        IntakeStatus status = new IntakeStatus(true);
        SqsIntakeRunner runner = newRunner(queue, mock(BroadcastEventProcessor.class), status);

        runner.pollOnce();
        runner.pollOnce();

        assertThat(status.snapshot().healthy()).isTrue();
    }

    /**
     * 설정값이 실제로 요청에 실리는지. 안 실리면 롱폴링이 0초가 되어 빈 응답만 쏟아지고
     * (SQS 기본값), 한 회차에 편지 하나만 오면 위 회차 중단 검사가 운영에서 뜻을 잃는다.
     * <p>문항 2: 요청을 안 보면 저절로 참이 되지 않는다 — 상대(가짜 큐)가 받은 것을 본다.
     */
    @Test
    void 설정한_대기_시간과_최대치를_요청에_싣는다() {
        FakeQueue queue = FakeQueue.with();
        SqsIntakeRunner runner = newRunner(queue, ProcessResult.PROCESSED);

        runner.pollOnce();

        ReceiveMessageRequest request = queue.lastRequest();
        assertThat(request.queueUrl()).isEqualTo(QUEUE_URL);
        assertThat(request.maxNumberOfMessages()).isEqualTo(10);
        assertThat(request.waitTimeSeconds()).isEqualTo(20);
    }

    /**
     * 루프는 큐에 못 닿아도 죽지 않고, 실패가 이어지면 점점 더 쉬며, <b>성공하면 간격을
     * 처음으로 되돌린다.</b> 안 되돌리면 한 번 흔들린 뒤로 영영 60초에 한 번씩만 꺼낸다.
     *
     * <p>문항 2: 잰 간격 목록이 비어 있어도 {@code isEmpty} 같은 단언이면 저절로 참이다 —
     * 그래서 <b>값을 정확히</b> 대조한다.
     * <p>문항 5: 성공 시 {@code consecutiveFailures = 0}을 빼면 [1s, 2s, <b>4s</b>]로
     * 빨간불(확인함). {@code pollOnce}의 {@code catch}를 떼면 루프가 첫 실패에 죽어
     * 간격이 하나도 안 쌓여 빨간불(확인함).
     */
    @Test
    @Timeout(10)
    void 루프는_못_닿아도_안_죽고_쉬었다가_성공하면_간격을_되돌린다() {
        FakeQueue queue = FakeQueue.reachableOn(false, false, true, false);
        List<Duration> slept = new ArrayList<>();
        SqsIntakeRunner runner = newRunner(queue, mock(BroadcastEventProcessor.class),
                new IntakeStatus(true), duration -> {
                    slept.add(duration);
                    return slept.size() >= 3;   // 세 번 쉬면 종료 신호가 온 것으로 친다
                });

        runner.runLoop();

        assertThat(slept).containsExactly(
                Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(1));
    }

    /**
     * 종료가 백오프만큼 늦으면 안 된다. {@code Thread.sleep}으로 자면 아무도 못 깨워
     * 최악 60초를 기다린다 — 이 서버의 종료 유예는 20초다.
     *
     * <p>문항 2: 깼다는 것만 보면 {@code awaitStop}이 무조건 true를 줘도 통과한다 —
     * 그래서 <b>걸린 시간</b>도 같이 잰다(60초를 기다렸다면 join이 안 끝난다).
     * <p>문항 5: {@code stopSignal.await}를 {@code Thread.sleep} + {@code return false}로
     * 바꾸면 5초 join이 실패해 빨간불(확인함).
     */
    @Test
    @Timeout(30)
    void 종료_신호가_오면_자던_회차가_즉시_깬다() throws InterruptedException {
        SqsIntakeRunner runner = newRunner(FakeQueue.with(), ProcessResult.PROCESSED);
        AtomicBoolean woke = new AtomicBoolean();
        Thread sleeping = new Thread(() -> woke.set(runner.awaitStop(Duration.ofSeconds(60))));
        sleeping.start();

        runner.stop();
        sleeping.join(5_000);

        assertThat(sleeping.isAlive()).as("60초를 그대로 잤다면 아직 살아 있다").isFalse();
        assertThat(woke).isTrue();
    }

    /**
     * 켜면 큐 클라이언트가 만들어지고 꺼지면 안 만들어진다.
     *
     * <p><b>여기서 실물이 조립된다</b> — {@code SqsClient.builder().build()}가 자격증명
     * 없이도 서는지, HTTP 구현이 하나로 정해지는지가 이 갈래에서만 드러난다.
     * SDK 동기 HTTP 구현이 둘이 되면 {@code Multiple HTTP implementations were found}로
     * 여기서 죽는다.
     * <p>문항 4: 켜짐만 재면 「무조건 만든다」로 바꿔도 통과한다 — 꺼짐 갈래가 그 짝이다.
     */
    @Test
    void 켜면_큐_클라이언트가_생기고_끄면_안_생긴다() {
        intakeContext("true").run(context -> assertThat(context).hasSingleBean(SqsClient.class));
        intakeContext("false").run(context -> assertThat(context).doesNotHaveBean(SqsClient.class));
    }

    /** 꺼져 있어도 상태는 있어야 한다 — health가 「빈이 없음」과 「꺼져 있음」을 갈라야 한다. */
    @Test
    void 꺼져_있어도_상태는_등록되고_꺼졌다고_말한다() {
        intakeContext("false").run(context -> {
            assertThat(context).hasSingleBean(IntakeStatus.class);
            assertThat(context.getBean(IntakeStatus.class).snapshot().enabled()).isFalse();
        });
    }

    /** 꺼져 있으면 폴링이 아예 안 도는 것이 정상이라 health가 초록이어야 한다. */
    @Test
    void 꺼져_있으면_건강하다() {
        assertThat(new IntakeStatus(false).snapshot().healthy()).isTrue();
    }

    private static ApplicationContextRunner intakeContext(String enabled) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
                .withUserConfiguration(IntakeConfiguration.class)
                .withPropertyValues(
                        "pokeclip.broadcast.intake.enabled=" + enabled,
                        "pokeclip.broadcast.intake.queue-url=" + QUEUE_URL,
                        "pokeclip.broadcast.intake.region=ap-northeast-2",
                        "pokeclip.broadcast.intake.endpoint=http://localhost:4566",
                        "pokeclip.broadcast.intake.wait-time=20s",
                        "pokeclip.broadcast.intake.max-messages=10");
    }

    private static SqsIntakeRunner newRunner(FakeQueue queue, ProcessResult always) {
        BroadcastEventProcessor processor = mock(BroadcastEventProcessor.class);
        given(processor.process(any())).willReturn(always);
        return newRunner(queue, processor);
    }

    private static SqsIntakeRunner newRunner(FakeQueue queue, BroadcastEventProcessor processor) {
        return newRunner(queue, processor, new IntakeStatus(true));
    }

    private static SqsIntakeRunner newRunner(FakeQueue queue, BroadcastEventProcessor processor,
                                             IntakeStatus status) {
        return newRunner(queue, processor, status, null);
    }

    /**
     * <b>줄 상한을 넉넉히(100) 준다.</b> 이 파일의 검사들은 백프레셔를 재는 것이 아니라
     * 「무엇을 지우고 무엇을 남기나」를 잰다 — 포화 갈래는 {@code AsyncIntakeTest}가 맡는다.
     * 여기서 상한을 좁게 잡으면 그 검사들이 <b>포화 때문에</b> 빨간불이 되어 원인이 흐려진다.
     */
    private static SqsIntakeRunner newRunner(FakeQueue queue, BroadcastEventProcessor processor,
                                             IntakeStatus status, SqsIntakeRunner.Sleeper sleeper) {
        return new SqsIntakeRunner(queue, properties(), status, processor, new ObjectMapper(),
                new StreamerSerialExecutor(100), sleeper);
    }

    /**
     * 🔴 <b>붙이기가 줄로 옮겨가 {@code pollOnce}가 곧바로 돌아온다</b>(POK-219).
     * 판정·삭제를 단언하기 <b>전에</b> 반드시 이것을 통과해야 한다 — 안 기다리면
     * 「아직 안 일어났다」를 「안 일어난다」로 읽고 통과한다(문항 5(가)).
     */
    private static void drain(SqsIntakeRunner runner) {
        assertThat(runner.awaitIdle(Duration.ofSeconds(5)))
                .as("줄이 5초 안에 안 비었다 — 붙이기가 매달려 있다")
                .isTrue();
    }

    private static IntakeProperties properties() {
        return new IntakeProperties(true, QUEUE_URL, "ap-northeast-2", "",
                Duration.ofSeconds(20), 10);
    }

    private static String startedJson(String eventId, String streamId, long sequence) {
        return """
                {"schemaVersion":1,"eventId":"%s","eventType":"broadcast.started",
                 "occurredAt":"2026-08-19T00:00:00Z","streamId":"%s","streamerId":"42",
                 "sequence":%d,"traceId":"t-1","payload":{}}"""
                .formatted(eventId, streamId, sequence);
    }

    /**
     * 가짜 큐. {@code receiveMessage}·{@code deleteMessage} 둘만 동작한다 — 나머지는
     * SDK 인터페이스의 기본 구현이 {@code UnsupportedOperationException}을 던지므로,
     * 러너가 그 밖의 무언가를 부르면 그대로 터져서 드러난다.
     *
     * <p>receiptHandle은 순번대로 {@code rh-0}·{@code rh-1}…이다. 어느 편지를 지웠는지
     * 이름으로 짚을 수 있어야 「지우긴 지웠다」가 아니라 「그 편지를 지웠다」를 잰다.
     */
    static final class FakeQueue implements SqsClient {

        private final List<Message> messages;
        /** 회차마다 닿을지 말지. 비면 늘 닿는다. 대본이 끝나면 계속 실패한다. */
        private final Deque<Boolean> script = new ArrayDeque<>();
        private final boolean scripted;
        /** <b>줄 스레드가 쓴다.</b> 삭제가 폴링 스레드를 떠났으므로 동기화가 필요하다. */
        private final List<String> deleted = Collections.synchronizedList(new ArrayList<>());
        private final List<ReceiveMessageRequest> requests =
                Collections.synchronizedList(new ArrayList<>());

        private FakeQueue(List<Message> messages, boolean scripted) {
            this.messages = messages;
            this.scripted = scripted;
        }

        static FakeQueue with(String... bodies) {
            return new FakeQueue(IntStream.range(0, bodies.length)
                    .mapToObj(i -> Message.builder()
                            .messageId("msg-" + i)
                            .receiptHandle("rh-" + i)
                            .body(bodies[i])
                            .build())
                    .toList(), false);
        }

        static FakeQueue unreachable() {
            return reachableOn();
        }

        /**
         * 대본이 끝나면 <b>계속 실패한다</b> — 성공으로 끝나면 루프가 쉬지 않고 돌아
         * 검사가 안 끝난다.
         */
        static FakeQueue reachableOn(boolean... reachable) {
            FakeQueue queue = new FakeQueue(List.of(), true);
            for (boolean ok : reachable) {
                queue.script.add(ok);
            }
            return queue;
        }

        List<String> deleted() {
            return List.copyOf(deleted);
        }

        int receiveCount() {
            return requests.size();
        }

        ReceiveMessageRequest lastRequest() {
            return requests.get(requests.size() - 1);
        }

        @Override
        public ReceiveMessageResponse receiveMessage(ReceiveMessageRequest request) {
            requests.add(request);
            if (scripted) {
                Boolean reachable = script.poll();
                if (reachable == null || !reachable) {
                    throw SqsException.builder().message("unreachable").build();
                }
            }
            // 상한을 실제로 지킨다 — 안 지키면 "한 회차에 몇 통 오는가"가 설정과 무관해져
            // 회차 중단 검사가 운영 설정을 아무것도 안 재게 된다(문항 3).
            int limit = Math.min(messages.size(), request.maxNumberOfMessages());
            return ReceiveMessageResponse.builder().messages(messages.subList(0, limit)).build();
        }

        @Override
        public DeleteMessageResponse deleteMessage(DeleteMessageRequest request) {
            deleted.add(request.receiptHandle());
            return DeleteMessageResponse.builder().build();
        }

        @Override
        public String serviceName() {
            return "sqs";
        }

        @Override
        public void close() {
        }
    }
}
