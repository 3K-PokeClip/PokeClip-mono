package com.pokeclip.chat.collector;

import com.pokeclip.chat.collector.engineio.PingFailure;
import com.pokeclip.chat.collector.fake.FakeChzzkBehavior;
import com.pokeclip.chat.collector.fake.FakeChzzkTest;
import com.pokeclip.chat.collector.observe.CollectionMetrics;
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
class ReconnectTest {

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
                status, restClientBuilder);

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
                status, restClientBuilder);
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
