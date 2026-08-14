package com.pokeclip.chat.collector;

import com.pokeclip.chat.collector.engineio.PingFailure;
import com.pokeclip.chat.collector.fake.FakeChzzkBehavior;
import com.pokeclip.chat.collector.fake.FakeChzzkTest;
import com.pokeclip.chat.collector.observe.CollectionMetrics;
import com.pokeclip.chat.collector.support.IntegrationTestSupport;
import com.pokeclip.chat.collector.support.TestPersistence;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** 끊기면 스스로 다시 붙는가. 끊긴 동안 채팅은 되돌릴 수 없으므로 이것이 유일한 방어다. */
@FakeChzzkTest
class ReconnectTest extends IntegrationTestSupport {

    private static final Duration AWAIT = Duration.ofSeconds(10);

    /** 아직 아무 세션도 안 선 상태. 부팅 첫 수립이 실패한 자리가 여기다. */
    private static final long NO_SESSION = 0L;

    @LocalServerPort int port;
    @Autowired FakeChzzkBehavior behavior;
    @Autowired RestClient.Builder restClientBuilder;

    private CollectorRunner runner;
    private CollectionStatus status;

    @AfterEach
    void tearDown() {
        if (runner != null) runner.stop();
        behavior.reset();
    }

    @Test
    void 서버가_끊으면_세션_발급부터_다시_타서_붙는다() throws Exception {
        start();
        assertThat(status.state()).isEqualTo(CollectionStatus.State.COLLECTING);
        assertThat(behavior.authCallCount()).isEqualTo(1);

        behavior.closeSession();

        awaitUntil(() -> status.state() == CollectionStatus.State.COLLECTING
                && behavior.authCallCount() == 2);
        assertThat(behavior.authCallCount())
                .as("세션 URL은 30초 만료·재사용 불가다. ①부터 다시 안 타면 조용히 실패한다")
                .isEqualTo(2);
        assertThat(status.state())
                .as("다시 붙었으면 health가 UP으로 돌아와야 한다")
                .isEqualTo(CollectionStatus.State.COLLECTING);

        // <b>「붙었다」와 「받는다」는 다른 사실이다.</b> 위 둘은 새 세션의 프레임이
        // 지표에 안 닿는 회귀(예: 싱크가 앞 스코프에 묶임)에서 그대로 초록이고,
        // 그 상태가 이 서비스의 유일한 치명 실패 — health는 UP인데 수집은 죽음 — 이다.
        long receivedBefore = runner.metrics().totalReceived();
        behavior.emitChat("{\"content\":\"x\",\"messageTime\":1}");

        awaitUntil(() -> runner.metrics().totalReceived() > receivedBefore);
        assertThat(runner.metrics().totalReceived())
                .as("다시 붙었는데 채팅이 지표에 안 닿으면 health만 UP이고 수집은 죽어 있다")
                .isGreaterThan(receivedBefore);
    }

    @Test
    void 좀비_연결도_임계를_넘으면_다시_붙는다() throws Exception {
        // 서버가 pong을 멈추고 소켓은 유지한다 — onClose가 안 온다.
        behavior.answerPong = false;
        behavior.disconnectWhenPingMissing = false;
        start();
        assertThat(status.state())
                .as("붙지도 않았다면 좀비가 될 연결이 없다")
                .isEqualTo(CollectionStatus.State.COLLECTING);

        awaitUntil(() -> behavior.authCallCount() >= 2);
        assertThat(behavior.authCallCount())
                .as("소켓이 살아 있으면 onClose가 안 온다. pong 판정이 없으면 영영 못 알아챈다")
                .isGreaterThanOrEqualTo(2);
    }

    /**
     * 한산한 것은 끊긴 것이 아니다. 방송을 꺼도 세션은 살아 있고(361초 실측),
     * POK-85 실측에서 수신 공백이 55.9초까지 갔는데 연결은 멀쩡했다.
     */
    @Test
    void 채팅이_안_와도_재연결하지_않는다() throws Exception {
        start();
        assertThat(status.state()).isEqualTo(CollectionStatus.State.COLLECTING);

        // 채팅을 한 건도 안 보낸다. pong은 정상이므로 좀비도 아니다.
        //
        // <b>pong 임계 위로 잔다.</b> 임계는 송신 주기 800ms + pingTimeout 2400ms의
        // 절반 = 2000ms다. 1초만 자면 어떤 임계에도 안 닿아 <b>판정이 발화할 기회
        // 자체가 없고</b>, 그때는 좀비 판정을 수신 공백으로 재도록 바꿔 놔도 초록이다
        // (3층 CLAUDE.md가 경고한 그 혼동이다). 3초면 "임계는 지났는데 안 끊었다"가 된다.
        Thread.sleep(3_000);

        assertThat(behavior.authCallCount())
                .as("수신 공백을 절단으로 오인하면 한산한 방송마다 멀쩡한 세션을 끊는다")
                .isEqualTo(1);
        assertThat(status.state()).isEqualTo(CollectionStatus.State.COLLECTING);
    }

    @Test
    void 토큰이_거부되면_재시도하지_않고_멈춘다() throws Exception {
        behavior.authStatus = 401;
        start();

        awaitUntil(() -> status.state() == CollectionStatus.State.STOPPED);
        assertThat(status.reason()).isEqualTo(StopReason.SESSION_AUTH_REJECTED);

        Thread.sleep(500);      // 백오프 첫 간격(50ms)의 열 배
        assertThat(behavior.authCallCount())
                .as("만료 토큰으로 영원히 재시도하면 자리만 태우고 아무도 원인을 모른다")
                .isEqualTo(1);
    }

    /**
     * <b>구독이 거부되면 재시도가 세션 발급부터 영원히 돈다.</b>
     *
     * <p>발급은 200이라 ①이 매번 성공하고 ④에서만 거부되므로, 사유를 재시도
     * 가능으로 두면 백오프 상한(여기서는 1초)마다 발급 API를 두들긴다 —
     * 못 쓰는 토큰·빠진 Scope로 자리만 태우고 아무도 원인을 모른다.
     *
     * <p><b>발급 횟수가 이 검사의 본체다.</b> 상태만 보면 "루프가 상한 간격에서
     * 아직 자고 있다"와 "멈췄다"가 같아 보인다.
     */
    @Test
    void 구독이_거부되면_재시도하지_않고_멈춘다() throws Exception {
        behavior.subscribeStatus = 401;
        start();

        awaitUntil(() -> status.state() == CollectionStatus.State.STOPPED);
        assertThat(status.state())
                .as("거부된 구독을 붙들고 재시도하는 동안 health는 DOWN을 오르내릴 뿐 안 멈춘다")
                .isEqualTo(CollectionStatus.State.STOPPED);
        // 사유는 STOPPED와 한 스냅숏으로 바뀐다 — 따로 기다릴 값이 아니다.
        assertThat(status.reason())
                .as("구독 거부를 일시 실패로 분류하면 영구 정지에 아예 못 닿는다")
                .isEqualTo(StopReason.SUBSCRIBE_REJECTED);

        Thread.sleep(500);      // 백오프 첫 간격(50ms)의 열 배
        assertThat(behavior.authCallCount())
                .as("Scope가 빠진 토큰으로 영원히 재시도하면 자리만 태우고 원인은 안 보인다")
                .isEqualTo(1);
    }

    /**
     * <b>동의 철회는 재시도로 안 풀린다.</b> 다시 붙어도 구독이 또 취소되므로,
     * 재연결을 돌면 연결 상한 3개를 태우면서 영원히 "연결은 살아 있는데 채팅만
     * 안 오는" 상태를 반복한다 — 3층 CLAUDE.md가 {@code revoked}를 무시하면 된다고
     * 경고한 바로 그 모양이다.
     *
     * <p><b>{@code ReconnectPolicy}의 {@code REVOKED -> false} 분기가 도달 가능함을
     * 보이는 유일한 검사다.</b> 신호를 정지 정책으로 안 보내면 그 분기는 죽은 코드이고,
     * 러너는 {@code COLLECTING}(health UP)으로 남는다. 거부 목록에서 {@code REVOKED}를
     * 빼도 아래 발급 횟수가 늘어 빨간불이 된다.
     */
    @Test
    void 동의가_철회되면_재시도하지_않고_멈춘다() throws Exception {
        start();
        assertThat(status.state())
                .as("붙지도 않았다면 철회 통지를 받을 길이 없다")
                .isEqualTo(CollectionStatus.State.COLLECTING);

        behavior.emitSystem("{\"type\":\"revoked\",\"data\":{\"eventType\":\"CHAT\"}}");

        awaitUntil(() -> status.state() == CollectionStatus.State.STOPPED);
        assertThat(status.state())
                .as("철회 뒤에도 health가 UP이면 채팅이 끊긴 것을 아무도 모른다")
                .isEqualTo(CollectionStatus.State.STOPPED);
        assertThat(status.reason())
                .as("사유가 REVOKED가 아니면 왜 영영 멈췄는지가 어디에도 안 남는다")
                .isEqualTo(StopReason.REVOKED);

        Thread.sleep(500);      // 백오프 첫 간격(50ms)의 열 배
        assertThat(behavior.authCallCount())
                .as("다시 붙어도 구독이 또 취소된다. 재시도하면 상한 3개만 태운다")
                .isEqualTo(1);
    }

    /**
     * <b>수립 중에 온 철회가 같은 수립의 일시 오류에 덮이면 안 된다.</b>
     *
     * <p>루프가 이미 돌고 있는 동안 세션이 {@code revoked}를 내면 그 신호는 곧장
     * 처리되지 못하고 <b>대기 사유로 앉는다</b>. 그런데 같은 수립이 시한 만료나 5xx로
     * 던지면 루프의 사유는 그 일시 오류로 덮이고, 대기 사유는 {@code finally}에서만
     * 읽힌다 — <b>시도가 계속 실패하면 그 {@code finally}에 영영 안 닿는다.</b>
     * 결과는 동의가 철회됐는데도 재시도가 이어지는 것이고, 나중에 번호가 큰 세션이
     * 그 자리를 덮어써 철회 사실 자체가 사라진다. 다시 붙어도 서버가 구독을 또
     * 취소하므로 그 재시도는 연결 상한 3개만 태운다.
     *
     * <p><b>둘째 시도에서만 철회를 낸다.</b> 첫 시도(부팅)에서 내면 그 신호가 스스로
     * 루프를 띄워 곧바로 멈추고, 그러면 이 검사가 겨냥한 자리 — <b>이미 도는 루프</b> —
     * 를 한 번도 안 지난다. 발급 횟수로 시도를 가른다.
     *
     * <p>{@code sendSubscribed=false}라 시도마다 ⑤에서 시한이 만료된다. 이 사유는
     * 재시도 가능이라, 대기 사유를 안 보면 루프가 영원히 돈다.
     */
    @Test
    void 수립_중에_온_철회가_같은_시도의_일시_오류에_덮이지_않는다() throws Exception {
        behavior.sendSubscribed = false;        // 시도마다 ⑤에서 시한이 만료된다

        status = new CollectionStatus();
        runner = new CollectorRunner(new ChzzkProperties(
                true, "test-token", "http://localhost:" + port,
                Duration.ofMillis(500), Duration.ofMillis(50), Duration.ofSeconds(1)),
                status, restClientBuilder,
                        TestPersistence.unusedBuffer(), TestPersistence.disabledPersister());

        java.util.concurrent.atomic.AtomicBoolean revokedSeen =
                new java.util.concurrent.atomic.AtomicBoolean();
        behavior.onSubscribeBeforeResponse = () -> {
            if (behavior.authCallCount() < 2) {
                return;                          // 첫 시도는 그냥 실패시킨다
            }
            behavior.emitSystem("{\"type\":\"revoked\",\"data\":{\"eventType\":\"CHAT\"}}");
            // <b>러너가 그 프레임을 처리한 것까지 보고 돌아간다.</b> 안 보고 응답하면
            // 철회가 수립 실패보다 늦게 도착하는 실행이 섞여 무엇을 봤는지 흐려진다.
            revokedSeen.set(awaitQuietly(() -> status.reason() == StopReason.REVOKED));
        };

        runner.run(null);

        awaitUntil(() -> status.state() == CollectionStatus.State.STOPPED);
        // 양성 대조. 철회가 수립 중에 실제로 들어가지 않았다면 아래 단언들은
        // 겨냥한 자리를 한 번도 안 지난 채 참이 될 수 있다.
        assertThat(revokedSeen)
                .as("철회가 수립 중인 세션에 안 닿았다면 이 검사는 덮일 신호를 만든 적이 없다")
                .isTrue();
        assertThat(status.state())
                .as("일시 오류가 철회를 덮으면 재시도가 이어지고 상태는 영영 안 멈춘다")
                .isEqualTo(CollectionStatus.State.STOPPED);
        assertThat(status.reason())
                .as("사유가 일시 오류로 남으면 왜 영영 멈췄는지가 어디에도 안 남는다")
                .isEqualTo(StopReason.REVOKED);
        assertThat(behavior.authCallCount())
                .as("철회 뒤에도 시도가 늘면 다시 붙어도 또 취소될 연결로 상한 3개를 태운다")
                .isEqualTo(2);
    }

    /**
     * <b>판정은 절단마다가 아니라 수집이 영영 끝날 때 나간다.</b>
     *
     * <p>재연결이 생기는 순간 절단에서 판정을 내는 것은 틀린 것이 된다 — 판정은
     * "수집이 끝났다"는 뜻인데 안 끝났다. 세션마다 찍으면 운영자는 30초마다 뜨는
     * "최종" 판정을 보게 되고, 그중 어느 것도 최종이 아니다.
     *
     * <p>세션 번호 둘을 다 훑는다. 하나만 보면 <b>세션마다 찍도록 되돌려도</b>
     * 그 줄이 다른 번호로 나가 이 검사를 비껴간다.
     */
    @Test
    void 판정은_절단마다가_아니라_수집이_끝날_때_한_줄_나간다() throws Exception {
        try (LogCaptor captor = new LogCaptor()) {
            start();
            long first = runner.lastSessionNo();

            behavior.closeSession();
            awaitUntil(() -> status.state() == CollectionStatus.State.COLLECTING
                    && behavior.authCallCount() == 2);
            long second = runner.lastSessionNo();
            assertThat(second)
                    .as("다시 붙지 않았다면 '절단마다 안 찍는다'가 검사할 것이 없다")
                    .isGreaterThan(first);

            assertThat(verdicts(captor, first) + verdicts(captor, second))
                    .as("절단은 끝이 아니다. 거기서 판정을 내면 최종이 아닌 최종 판정이 쌓인다")
                    .isZero();

            runner.stop();

            // 양성 대조. 아예 안 나가면 위 0줄 단언은 아무것도 안 본 것이다.
            assertThat(verdicts(captor, first) + verdicts(captor, second))
                    .as("수집이 끝났는데 판정이 없으면 무엇을 놓쳤는지 어디에도 안 남는다")
                    .isEqualTo(1);
        }
    }

    /**
     * <b>이미 치워진 세션의 늦은 신호가 새로 붙은 세션을 헐면 안 된다.</b>
     *
     * <p>감지원이 셋이라 한 번의 절단에 신호가 둘 이상 발화한다 — pong 임계 초과와
     * 전송 절단이 같은 죽음을 서로 다른 경로로 본다. 늦게 도착한 쪽에 세대 표식이
     * 없으면 <b>루프가 재접속에 성공한 뒤 그 신호가 살아 있는 세션을 뜯는다</b>:
     * 구독은 반납되고 health는 DOWN으로 돌아가는데 밖에서는 "방금 붙었는데 또 끊겼다"로만
     * 보인다. 신호를 지어내는 것이 아니라 <b>실제 발화 지점(하트비트 리스너)에</b>
     * 앞 세션 번호로 넣는다.
     */
    @Test
    void 이미_치워진_세션의_늦은_신호는_새_세션을_헐지_않는다() throws Exception {
        start();
        long first = runner.lastSessionNo();

        behavior.closeSession();
        awaitUntil(() -> status.state() == CollectionStatus.State.COLLECTING
                && behavior.authCallCount() == 2);
        assertThat(runner.lastSessionNo())
                .as("새 세션이 안 섰다면 헐릴 것이 없어 이 검사는 아무것도 안 본다")
                .isGreaterThan(first);
        int releasedBefore = behavior.unsubscribeCallCount();

        // 앞 세션의 ping 스케줄러가 뒤늦게 좀비를 알린다.
        runner.heartbeatListener(first).onPongTimeout(Duration.ofSeconds(9));

        Thread.sleep(500);      // 백오프 첫 간격(50ms)의 열 배
        assertThat(status.state())
                .as("낡은 사유가 새 세션을 헐면 health가 DOWN으로 돌아가고 채팅이 그만큼 끊긴다")
                .isEqualTo(CollectionStatus.State.COLLECTING);
        assertThat(behavior.authCallCount())
                .as("살아 있는 세션을 뜯고 다시 붙으면 그 사이 채팅이 통째로 사라진다")
                .isEqualTo(2);
        assertThat(behavior.unsubscribeCallCount())
                .as("낡은 신호가 새 세션의 구독을 반납해 버리면 연결은 살아 있는데 채팅만 안 온다")
                .isEqualTo(releasedBefore);
    }

    /**
     * 대기를 안 깨우면 종료가 백오프 간격만큼 매달린다. 컨테이너 유예를 넘기면
     * SIGKILL이 오고, 그때는 구독 반납이 안 나가 좀비가 남는다 — 이 카드가 없애려던
     * 바로 그것이다.
     *
     * <p><b>간격을 일부러 크게 준다.</b> 임계를 {@code stop()}의
     * {@code awaitTermination}(2초)보다 짧게 둬야 "안 깨웠다"가 실제로 빨간불이 된다 —
     * 간격이 그 2초보다 짧으면 깨우든 말든 종료가 2초 안에 끝나 검사가 헛돈다.
     * 첫 4초·상한 8초면 안 깨울 때 2초(awaitTermination 만료)가 걸리고,
     * 깨우면 ms 안에 끝난다. 임계 1초는 그 사이다.
     */
    @Test
    void 재시도를_기다리는_중에_종료하면_기다림을_깨고_끝낸다() throws Exception {
        start(Duration.ofSeconds(4), Duration.ofSeconds(8));
        behavior.authStatus = 500;      // 재시도가 계속 실패해 대기로 들어간다
        behavior.closeSession();
        awaitUntil(() -> status.state() == CollectionStatus.State.RECONNECTING
                && behavior.authCallCount() >= 2);
        assertThat(behavior.authCallCount())
                .as("재시도가 한 번도 안 돌았다면 대기 중이 아니라 아직 시작 전이다")
                .isGreaterThanOrEqualTo(2);

        long startedAt = System.nanoTime();
        runner.stop();
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        assertThat(elapsed)
                .as("대기를 안 깨우면 종료가 백오프 간격만큼 매달린다")
                .isLessThan(Duration.ofSeconds(1));

        // 멈춘 뒤에도 도는 루프는 종료가 끝난 다음에도 세션 발급을 두드린다.
        int afterStop = behavior.authCallCount();
        Thread.sleep(500);
        assertThat(behavior.authCallCount())
                .as("종료 뒤에도 재시도가 살아 있으면 컨테이너가 죽을 때까지 자리를 태운다")
                .isEqualTo(afterStop);
    }

    /**
     * <b>종료가 「끊겨서 죽은 세션」을 정상 종료로 기록하면 안 된다.</b>
     *
     * <p>우리가 멈추는 중이면 절단 신호는 입구에서 버려진다 — 뒷정리는 {@code stop()}이
     * 하기 때문이다. 그래서 그 절단은 {@code status}에 한 글자도 안 남고, 종료가 사유를
     * {@code status}에서만 읽으면 <b>끊겨 죽은 세션이 세션 종료 줄에 {@code SHUTDOWN}으로
     * 기록된다.</b> 로그만 보는 사람은 그 방송이 우아하게 끝났다고 읽는다.
     *
     * <p><b>창을 반납 왕복으로 벌린다.</b> 종료는 뒷정리 스레드가 반납에 갇혀 있으면
     * 그것이 끝날 때까지 기다린다(거기서 인터럽트하면 세션 키가 이미 소모돼 아무도
     * 다시 못 보낸다). 그 몇 초가 <b>신호는 내려갔고 뒷정리는 아직 안 한</b> 구간이라,
     * 절단을 거기에 떨어뜨린다. 앞 세션을 그 왕복에 가두고 <b>다음 세션을 직접 세워</b>
     * 종료가 치울 것을 만든다.
     *
     * <p><b>순서가 어긋나면 조용히 통과하지 않는다.</b> 절단이 신호보다 먼저 도착했다면
     * 그 신호가 살아서 {@code status}를 RECONNECTING(TRANSPORT_CLOSED)으로 내리므로,
     * 아래 {@code reason()} 단언이 그 자리에서 터진다.
     */
    @Test
    void 종료가_끊겨_죽은_세션을_정상_종료로_기록하지_않는다() throws Exception {
        // 뒷정리 스레드를 반납 왕복에 가둔다. 도착을 센 뒤에 붙들므로 건수가 1이 된
        // 시점이 곧 갇힌 시점이다. 3초는 종료의 기본 대기(2초)보다 길다.
        behavior.unsubscribeDelay = Duration.ofSeconds(3);
        try (LogCaptor captor = new LogCaptor()) {
            // 재시도 간격을 크게 준다. 갇힌 스레드가 풀린 뒤 다음 시도가 끼어들면
            // 무엇을 읽었는지 흐려진다.
            start(Duration.ofSeconds(30), Duration.ofSeconds(60));
            assertThat(status.state()).isEqualTo(CollectionStatus.State.COLLECTING);

            behavior.closeSession();
            awaitUntil(() -> behavior.unsubscribeCallCount() == 1);
            assertThat(behavior.unsubscribeCallCount())
                    .as("반납이 아직 안 나갔다면 뒷정리 스레드가 안 갇혔고 창이 안 열린다")
                    .isEqualTo(1);

            assertThat(runner.start())
                    .as("다음 세션이 안 섰다면 종료가 치울 세션이 없어 이 검사는 아무것도 안 본다")
                    .isTrue();
            long session = runner.lastSessionNo();

            Thread stopper = new Thread(runner::stop, "test-stop");
            stopper.start();
            // 신호를 내리는 것이 stop()의 첫 문장이다. 자는 동안 그것이 지나간다 —
            // 안 지나갔으면 아래 reason() 단언이 터진다.
            Thread.sleep(300);

            behavior.closeSession();
            awaitUntil(() -> closedLines(captor) == 2);
            assertThat(closedLines(captor))
                    .as("절단이 도착 안 했으면 종료가 사유를 고를 일이 없다")
                    .isEqualTo(2);
            // 종료 자신의 반납까지 3초를 더 자면 검사만 느려진다. 갇힌 왕복은
            // 이미 시간을 읽어 갔으므로 여기서 지워도 안 짧아진다.
            behavior.unsubscribeDelay = Duration.ZERO;

            stopper.join(Duration.ofSeconds(20).toMillis());
            assertThat(stopper.isAlive())
                    .as("종료가 안 끝났으면 아래 줄은 아직 안 나온 것이다")
                    .isFalse();

            assertThat(status.reason())
                    .as("절단이 신호보다 먼저 도착했다면 겨냥한 순서를 한 번도 안 만든 것이다")
                    .isNull();
            assertThat(endedLine(captor, session))
                    .as("끊겨 죽은 세션이 정상 종료로 남으면 로그만 보는 사람은 잘 끝났다고 읽는다")
                    .contains("reason=" + StopReason.TRANSPORT_CLOSED);
        }
    }

    private static long closedLines(LogCaptor captor) {
        return captor.messages().stream()
                .filter(m -> m.startsWith("chat.session.closed"))
                .count();
    }

    private static String endedLine(LogCaptor captor, long session) {
        return captor.messages().stream()
                .filter(m -> m.startsWith("chat.session.ended session=" + session + " "))
                .findFirst()
                .orElseThrow(() -> new AssertionError("session=" + session + " 종료 줄이 안 나갔다"));
    }

    /**
     * <b>한 번의 죽음에 신호가 둘 와도 루프는 하나만 돈다.</b>
     *
     * <p>감지원이 셋이라(전송 절단 · pong 임계 · ping 송신 실패) 같은 죽음이 서로
     * 다른 경로로 두 번 보고된다. 둘 다 루프를 띄우면 <b>첫 루프가 재접속에 성공한 뒤
     * 두 번째가 깨어나, 살아 있는 세션을 두고 health를 DOWN으로 되돌리고 시도를
     * 두 배로 태운다.</b> 세션 번호로 거르는 것만으로는 못 막는다 — 둘 다 같은 세션의
     * 신호라 표식이 일치한다.
     *
     * <p><b>둘째를 언제 넣는지가 이 검사의 전부다.</b> 첫 루프가 대기에 들어간 것을
     * 보고서야 넣는다({@code attempt}는 루프만 올린다). 안 보고 넣으면 둘째가 먼저
     * 자리를 잡는 실행이 섞여 무엇을 재는지 알 수 없게 된다.
     *
     * <p>붙은 세션이 없는 상태에서 낸다 — 부팅 첫 수립이 실패한 자리와 같고,
     * 그래야 첫 루프가 뒷정리 없이 곧장 대기로 들어가 창이 <b>대기 시간만큼</b>
     * 넓어진다. 살아 있는 세션에서 내면 그 창이 마이크로초라 재현이 우연에 맡겨진다.
     */
    @Test
    void 한_죽음에_신호가_둘_와도_루프는_하나만_돈다() throws Exception {
        status = new CollectionStatus();
        runner = new CollectorRunner(new ChzzkProperties(
                true, "test-token", "http://localhost:" + port,
                Duration.ofSeconds(5), Duration.ofMillis(300), Duration.ofSeconds(1)),
                status, restClientBuilder,
                        TestPersistence.unusedBuffer(), TestPersistence.disabledPersister());

        runner.heartbeatListener(NO_SESSION).onPongTimeout(Duration.ofSeconds(9));   // 신호 ①
        awaitUntil(() -> status.attempt() == 1);
        assertThat(status.attempt())
                .as("첫 루프가 대기에 들어가지 않았다면 둘째 신호는 겹친 것이 아니다")
                .isEqualTo(1);

        runner.heartbeatListener(NO_SESSION)                                          // 신호 ②
                .onSendFailed(PingFailure.Cause.CONNECTION_DEAD);

        // 양성 대조. 첫 루프가 붙지도 못했다면 "두 번째가 그것을 헐지 않는다"를
        // 검사할 것이 없다. 상태가 아니라 발급 도착으로 본다 — 루프가 둘이면
        // 상태가 오르내려서 상태로 기다리면 이 자리에서 시한을 다 쓴다.
        awaitUntil(() -> behavior.authCallCount() >= 1);
        assertThat(behavior.authCallCount())
                .as("첫 루프가 세션 발급조차 안 불렀다면 붙은 적이 없다")
                .isGreaterThanOrEqualTo(1);

        // 둘째 루프가 있었다면 첫 루프가 끝나자마자 같은 스레드에서 이어 돈다.
        // 그 첫 동작이 상태를 내리는 것이라 곧바로 드러난다.
        Thread.sleep(500);
        assertThat(status.state())
                .as("루프가 둘이면 뒤엣것이 살아 있는 세션을 두고 health를 DOWN으로 되돌린다")
                .isEqualTo(CollectionStatus.State.COLLECTING);
        assertThat(behavior.authCallCount())
                .as("루프가 둘이면 시도도 둘이라 연결 상한 3개를 두 배 속도로 태운다")
                .isEqualTo(1);
    }

    /**
     * <b>얼마나 놓쳤는지가 한 줄에 없으면 어디에도 없다.</b> 판정 줄은 프로세스
     * 누계라 "끊겼다 붙었다"의 흔적이 재연결 횟수와 놓친 시간으로만 남는다.
     *
     * <p>시각 둘을 같이 본다 — 누적 시간만으로는 "언제 놓쳤나"를 못 찾아
     * 나중에 영상과 대조할 수 없다(PRD 완료 조건).
     */
    @Test
    void 판정_줄이_재연결_횟수와_누적_절단_시간을_싣는다() throws Exception {
        try (LogCaptor captor = new LogCaptor()) {
            start();
            long first = runner.lastSessionNo();

            behavior.closeSession();
            // <b>단언하는 값 자체를 기다린다.</b> 상태(COLLECTING)는 절단 기록보다
            // 앞서 찍히므로, 상태로 기다리면 아직 안 걷힌 값을 stop()이 앞지른다.
            awaitUntil(() -> runner.metrics().verdict().reconnects() == 1);
            long second = runner.lastSessionNo();
            assertThat(behavior.authCallCount())
                    .as("세션 발급을 다시 안 탔다면 재연결이 아니라 그냥 안 끊긴 것이다")
                    .isEqualTo(2);
            assertThat(second)
                    .as("새 세션이 안 섰다면 이 줄이 셀 절단이 없다")
                    .isGreaterThan(first);

            runner.stop();

            String verdict = captor.messages().stream()
                    .filter(m -> m.startsWith("chat.session.verdict session=" + second + " "))
                    .reduce((a, b) -> b).orElseThrow();
            java.util.Map<String, String> fields = fields(verdict);
            assertThat(fields)
                    .as("재연결 횟수가 없으면 얼마나 끊겼다 붙었는지가 어디에도 안 남는다")
                    .containsEntry("reconnects", "1");
            // 키가 통째로 사라지면 get이 null이라 뒤의 비교가 저절로 참이 된다.
            // 그 갈래는 FinalVerdictTest의 항목 목록이 잡지만, 여기서도 안 새게 막는다.
            assertThat(fields.get("outage"))
                    .as("놓친 시간이 0이면 절단 구간을 아무도 안 잰 것이다")
                    .isNotNull()
                    .isNotEqualTo("0ms");
            // none이면 키만 있고 값이 없는 것과 같다 — 파싱에서 터진다.
            Instant outageFrom = Instant.parse(fields.get("lastOutageFrom"));
            Instant outageTo = Instant.parse(fields.get("lastOutageTo"));
            assertThat(outageFrom)
                    .as("끊긴 시각이 복구 시각보다 뒤면 둘 중 하나는 그 절단의 것이 아니다")
                    .isBefore(outageTo);

            // 세션별 진단은 판정 줄이 아니라 여기 실린다. 검사가 0개면 PRD 결정이
            // 구현에 안 들어가도 아무도 모른다. <b>번호로 고른다</b> — LogCaptor는
            // JVM 루트 로거라 남의 러너가 늦게 찍은 줄까지 담는다.
            assertThat(captor.messages().stream()
                    .filter(m -> m.startsWith("chat.session.ended session=" + first + " ")
                            || m.startsWith("chat.session.ended session=" + second + " "))
                    .toList())
                    .as("세션이 둘이면 종료 줄도 둘. 각 줄이 그 세션의 하트비트 값을 든다")
                    .hasSize(2)
                    .allMatch(m -> m.contains("maxPingGap=") && m.contains("maxPongGap="));
        }
    }

    /**
     * <b>다시 못 붙은 채로 끝나면 그 절단이 판정 줄에 실려야 한다.</b>
     *
     * <p>절단 구간은 재접속에 <b>성공</b>했을 때만 닫혔다. 그래서 재시도가 401을
     * 만나 영구 정지하면 판정 줄이 {@code outage=0ms lastOutageFrom=none}이라고
     * 말한다 — 원래 절단 이후로 계속 못 받고 있는데도. 「얼마나 놓쳤는지 한 줄로
     * 보이게 한다」가 정확히 그 자리에서 무력해진다.
     *
     * <p><b>{@code reconnects}는 안 올라야 한다.</b> 구간을 닫는 것과 "다시 붙었다"는
     * 다른 사실이다. 같이 올리면 붙은 적이 없는 프로세스가 재연결 1회로 보고되고,
     * 그러면 재연결이 실제로 도는지를 이 숫자로 못 읽는다.
     */
    @Test
    void 영구_정지로_끝나도_열린_절단_구간이_판정에_실린다() throws Exception {
        try (LogCaptor captor = new LogCaptor()) {
            start(Duration.ofMillis(300), Duration.ofSeconds(1));
            assertThat(status.state())
                    .as("붙지도 않았다면 닫을 절단 구간이 애초에 없다")
                    .isEqualTo(CollectionStatus.State.COLLECTING);

            behavior.authStatus = 401;      // 재시도가 영구 사유를 만난다
            behavior.closeSession();

            // <b>단언하는 값 자체를 기다린다.</b> 상태(STOPPED)는 판정 줄보다 앞서
            // 찍히므로, 상태로 기다리면 아직 안 나간 줄을 읽는다.
            awaitUntil(() -> verdicts(captor, runner.lastSessionNo()) == 1);
            assertThat(status.reason())
                    .as("영구 정지를 안 밟았다면 이 검사는 겨냥한 자리를 한 번도 안 지났다")
                    .isEqualTo(StopReason.SESSION_AUTH_REJECTED);
            assertThat(behavior.authCallCount())
                    .as("재시도가 안 돌았다면 절단이 열린 채로 끝나는 상황이 아니다")
                    .isEqualTo(2);

            CollectionMetrics.Verdict v = runner.metrics().verdict();
            assertThat(v.totalOutage())
                    .as("끊긴 뒤로 계속 못 받고 있는데 0이면 얼마나 놓쳤는지가 어디에도 안 남는다")
                    .isGreaterThanOrEqualTo(Duration.ofMillis(300));
            assertThat(v.lastOutageFrom())
                    .as("언제부터 못 받았는지가 없으면 영상과 대조할 수 없다")
                    .isNotNull();
            assertThat(v.lastOutageTo())
                    .as("다시 받기 시작한 적이 없다. 시각이 서면 '이때 돌아왔다'로 읽힌다")
                    .isNull();
            assertThat(v.reconnects())
                    .as("다시 붙은 적이 없는데 세면 재연결이 도는지를 이 숫자로 못 읽는다")
                    .isZero();

            java.util.Map<String, String> fields = fields(verdictLine(captor));
            assertThat(fields.get("outage"))
                    .as("지표에는 있는데 줄에 안 실리면 운영자에게는 여전히 없는 값이다")
                    .isNotNull()
                    .isNotEqualTo("0ms");
            assertThat(fields.get("lastOutageFrom"))
                    .isNotNull()
                    .isNotEqualTo("none");
            assertThat(fields)
                    .containsEntry("lastOutageTo", "none")
                    .containsEntry("reconnects", "0");
        }
    }

    /**
     * <b>끊긴 채로 프로세스를 종료해도 같은 모양이다.</b> 판정이 나가는 자리가
     * 둘(영구 정지 · 프로세스 종료)이라, 한쪽만 닫으면 다른 쪽 판정은 여전히
     * {@code outage=0ms}라고 말한다.
     *
     * <p>재시도 간격을 크게 준다 — 이 검사 안에서 다시 붙으면 절단 구간이 그때
     * 닫혀 종료 경로가 아무것도 안 검사한다.
     */
    @Test
    void 끊긴_채로_종료해도_열린_절단_구간이_판정에_실린다() throws Exception {
        try (LogCaptor captor = new LogCaptor()) {
            start(Duration.ofSeconds(30), Duration.ofSeconds(60));
            assertThat(status.state())
                    .as("붙지도 않았다면 닫을 절단 구간이 애초에 없다")
                    .isEqualTo(CollectionStatus.State.COLLECTING);
            long session = runner.lastSessionNo();

            behavior.closeSession();
            awaitUntil(() -> status.state() == CollectionStatus.State.RECONNECTING);
            assertThat(status.state())
                    .as("절단을 아무도 못 봤다면 열린 구간 자체가 없다")
                    .isEqualTo(CollectionStatus.State.RECONNECTING);
            Thread.sleep(300);      // 끊겨 있는 시간을 아래 임계 위로 확보한다

            runner.stop();

            CollectionMetrics.Verdict v = runner.metrics().verdict();
            assertThat(v.totalOutage())
                    .as("끊긴 채로 끝났는데 0이면 얼마나 놓쳤는지가 어디에도 안 남는다")
                    .isGreaterThanOrEqualTo(Duration.ofMillis(300));
            assertThat(v.reconnects())
                    .as("다시 붙은 적이 없는데 세면 재연결이 도는지를 이 숫자로 못 읽는다")
                    .isZero();

            java.util.Map<String, String> fields = fields(captor.messages().stream()
                    .filter(m -> m.startsWith("chat.session.verdict session=" + session + " "))
                    .findFirst().orElseThrow());
            assertThat(fields.get("outage"))
                    .as("지표에는 있는데 줄에 안 실리면 운영자에게는 여전히 없는 값이다")
                    .isNotNull()
                    .isNotEqualTo("0ms");
            assertThat(fields.get("lastOutageFrom"))
                    .isNotNull()
                    .isNotEqualTo("none");
            assertThat(fields)
                    .containsEntry("lastOutageTo", "none")
                    .containsEntry("reconnects", "0");
        }
    }

    /**
     * <b>재연결 직후의 첫 채팅이 절단 구간을 닫는 코드보다 먼저 도착하는 순서.</b>
     *
     * <p>이 순서가 실제로 이긴다. 프레임 싱크는 세션이 서자마자 살아 있는데, 재연결
     * 스레드는 하트비트·요약 스레드를 만들고 상태를 옮긴 다음에야 절단 구간을 닫는다.
     * 그 사이에 온 첫 채팅이 <b>앞 세션의 마지막 수신과 짝지어지면 끊겨 있던 시간이
     * 통째로 수신 공백으로 찍힌다</b> — 그러면 "한산했을 뿐"과 "끊겨 있었다"를 가르는
     * 항이 무너지고, 최댓값 누계라 한 번 새면 그 프로세스에서 영영 안 내려온다.
     *
     * <p><b>순서를 우연에 맡기지 않는다.</b> 구독 REST가 200을 붙들고 있는 동안
     * 채팅을 쏘고, 그것이 지표에 닿은 것까지 확인하고 응답한다. 수립 스레드는 그
     * 응답을 기다리며 막혀 있으므로 수립 마무리는 반드시 그 뒤다.
     */
    @Test
    void 재연결_직후_채팅이_먼저_와도_절단이_수신_공백에_안_섞인다() throws Exception {
        // 백오프를 크게 준다. 끊긴 시간이 짧으면 그것이 공백으로 새도 다른 공백과
        // 자릿수로 안 갈려 무엇을 봤는지 흐려진다.
        start(Duration.ofSeconds(1), Duration.ofSeconds(2));

        // 앞 세션에서 한 건 받아 둔다. 짝지을 상대가 없으면 새어도 아무 일이 없어
        // 이 검사가 헛돈다.
        behavior.emitChat("{\"content\":\"x\",\"messageTime\":1}");
        awaitUntil(() -> runner.metrics().totalReceived() == 1);
        assertThat(runner.metrics().totalReceived())
                .as("앞 세션이 한 건도 못 받았다면 새 세션의 첫 채팅이 짝지을 상대가 없다")
                .isEqualTo(1);

        java.util.concurrent.atomic.AtomicBoolean chatArrivedFirst =
                new java.util.concurrent.atomic.AtomicBoolean();
        behavior.onSubscribeBeforeResponse = () -> {
            behavior.emitChat("{\"content\":\"y\",\"messageTime\":2}");
            chatArrivedFirst.set(awaitQuietly(() -> runner.metrics().totalReceived() == 2));
        };

        behavior.closeSession();
        // <b>단언하는 값 자체를 기다린다.</b> 상태(COLLECTING)는 절단 기록보다 앞서
        // 찍히므로, 상태로 기다리면 아직 안 닫힌 구간을 보고 단언한다.
        awaitUntil(() -> runner.metrics().verdict().reconnects() == 1);

        assertThat(chatArrivedFirst)
                .as("새 세션의 채팅이 수립 마무리보다 먼저 지표에 안 닿았다면 "
                        + "이 검사는 겨냥한 순서를 한 번도 안 만든 것이다")
                .isTrue();
        CollectionMetrics.Verdict v = runner.metrics().verdict();
        assertThat(v.totalReceived())
                .as("두 건이 다 안 세어졌다면 아래 공백 0은 '샜다'가 아니라 '잰 것이 없다'다")
                .isEqualTo(2);
        // 양성 대조. 아래 단언은 부정형이라 "무엇을 막았나"를 스스로 말하지 못한다.
        // 샜다면 공백에 실렸을 값이 이만큼이라는 것을 같은 실행에서 못박는다.
        assertThat(v.totalOutage())
                .as("끊긴 시간이 이 정도가 아니면 그것이 공백에 실려도 표가 안 나 "
                        + "아래 단언이 아무것도 못 가른다")
                .isGreaterThan(Duration.ofSeconds(1));
        assertThat(v.maxReceiveGap())
                .as("세션마다 한 건씩이라 짝지을 공백이 애초에 없다. 0이 아니면 "
                        + "앞 세션의 마지막 수신과 짝지어진 것이고 그 값은 위 절단 시간이다")
                .isEqualTo(Duration.ZERO);
    }

    /** {@code key=value} 한 줄을 쪼갠다. {@code system={...}}만 공백을 품지 않는다. */
    private static java.util.Map<String, String> fields(String line) {
        java.util.Map<String, String> map = new java.util.LinkedHashMap<>();
        for (String token : line.split(" ")) {
            int eq = token.indexOf('=');
            if (eq > 0) {
                map.put(token.substring(0, eq), token.substring(eq + 1));
            }
        }
        return map;
    }

    /**
     * <b>이 러너가 받은 번호로 좁힌다.</b> {@code LogCaptor}는 JVM 전역 루트 로거라
     * 남의 러너가 늦게 찍은 판정 줄까지 담는다.
     */
    private String verdictLine(LogCaptor captor) {
        long session = runner.lastSessionNo();
        return captor.messages().stream()
                .filter(m -> m.startsWith("chat.session.verdict session=" + session + " "))
                .findFirst()
                .orElseThrow(() -> new AssertionError("session=" + session + " 판정 라인이 안 나갔다"));
    }

    private long verdicts(LogCaptor captor, long session) {
        return captor.messages().stream()
                .filter(m -> m.startsWith("chat.session.verdict session=" + session + " "))
                .count();
    }

    /**
     * <b>{@code runner.start()}가 아니라 {@code run()}으로 띄운다.</b> 이 태스크가
     * {@code start()}를 밖으로 던지게 바꾸므로, 직접 부르면 401 테스트에서
     * {@code SessionEstablishException}이 테스트 메서드를 뚫고 나가 단언에 못 닿는다.
     *
     * <p>{@code run()}은 그 예외를 {@code requestReconnect}로 넘긴다 — 운영 경로와 같고,
     * 영구 정지 시 판정 줄이 나가는 것까지 함께 검사된다.
     */
    private void start() {
        start(Duration.ofMillis(50), Duration.ofSeconds(1));
    }

    private void start(Duration firstDelay, Duration maxDelay) {
        status = new CollectionStatus();
        runner = new CollectorRunner(new ChzzkProperties(
                true, "test-token", "http://localhost:" + port,
                Duration.ofSeconds(5), firstDelay, maxDelay),
                status, restClientBuilder,
                        TestPersistence.unusedBuffer(), TestPersistence.disabledPersister());
        runner.run(null);
    }

    private static void awaitUntil(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + AWAIT.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
    }

    /**
     * 가짜 서버 스레드에서 기다린다. <b>결과를 값으로 돌려준다</b> — 거기서 던지면
     * 예외가 REST 응답으로 둔갑해 수립 실패가 되고, 그러면 테스트는 "재현이 안 됐다"가
     * 아니라 엉뚱한 자리에서 깨져 원인이 흐려진다.
     */
    private static boolean awaitQuietly(java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + AWAIT.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return condition.getAsBoolean();
    }
}
