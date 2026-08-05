package com.pokeclip.chat.collector.observe;

import com.pokeclip.chat.collector.engineio.EngineIoFrame;
import com.pokeclip.chat.collector.engineio.EngineIoSocket;
import com.pokeclip.chat.collector.engineio.Handshake;
import com.pokeclip.chat.collector.fake.FakeChzzkBehavior;
import com.pokeclip.chat.collector.fake.FakeChzzkTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-08-01 사고의 회귀 방지. 관측: 74초간 ping 0회 → 서버가 조용히 끊음 →
 * 메시지 15건 유실, 오류 로그 0줄.
 *
 * <p>가짜 서버가 pingInterval을 줄여 주므로 실측 비율을 유지한 채 몇 초에 끝난다.
 */
@FakeChzzkTest
class HeartbeatTest {

    @LocalServerPort int port;
    @Autowired FakeChzzkBehavior behavior;

    /**
     * 실측 비율(60000/25000 = 2.4)을 유지한 압축값.
     * 파생: 송신 400ms · ping 임계 800ms · pong 임계 1000ms · 생존 시한 1700ms.
     *
     * <p><b>300/720이 아니라 500/1200인 이유가 있다.</b> T1의 여유는 구조상 항상
     * 송신 주기와 같다(관측 ≈ 주기, 임계 = 주기 × 2). 300이면 여유가 240ms인데,
     * 부하 걸린 CI에서 ping이 한 주기만 밀리면 임계에 닿아 <b>고쳐도 빨간불</b>이
     * 된다 — 이 문서가 배격하는 바로 그 단언이다. 절대 여유를 늘리는 방법은
     * 주기를 키우는 것뿐이라 500으로 올렸다(여유 400ms). 대가는 테스트 몇 초다.
     */
    private static final long PING_INTERVAL_MS = 500;
    private static final long PING_TIMEOUT_MS = 1200;   // 실측 비율 2.4 유지

    /**
     * 수신 핸들러 한 건이 잡아먹는 시간. <b>T1과 T12가 같은 값을 쓴다</b> —
     * 두 테스트는 ping을 어디서 보내느냐만 달라야 하고, 지연이 다르면 비교가
     * 성립하지 않는다.
     *
     * <p><b>임계의 2배로 잡는 것이 핵심이다.</b> 사고 아키텍처에서 관측되는
     * 간격은 이 지연 그 자체인데(이벤트마다 ping이 한 번 나가므로),
     * 임계보다 작으면 사고가 나도 초록불이다 — 계획 1차에서 400ms &lt; 480ms로
     * 정확히 그렇게 됐다. 2배면 스케줄링 지터가 끼어도 판정이 안 뒤집힌다.
     *
     * <p>임계 = pingInterval × 1.6 이므로 여기는 pingInterval × 3.2 다.
     * 아래 {@code 지연이_임계보다_큰지_먼저_확인한다}가 이 관계를 못박는다.
     */
    private static final Duration SLOW_HANDLER = Duration.ofMillis(PING_INTERVAL_MS * 32 / 10);

    /** 홍수를 밀어 넣고 간격·건수를 재는 창. 아래 {@code MIN_PINGS}가 여기서 나온다. */
    private static final Duration OBSERVE_WINDOW = Duration.ofSeconds(3);

    /**
     * 창 안에 나가야 할 ping의 하한. <b>여기도 상수를 박지 않고 파생한다</b> —
     * 송신 주기가 바뀌면 같이 움직여야 한다.
     *
     * <p>창 3초 ÷ 주기 400ms = 7건이 기대값이고, 그 절반인 <b>3건</b>이 하한이다.
     * 실측(25회씩)은 정상 <b>8건</b>(편차 0) · 사고 <b>1건</b>(편차 0)이라
     * 양쪽으로 여유가 +5 / −2다.
     */
    private static final long MIN_PINGS =
            OBSERVE_WINDOW.toMillis() / (PING_INTERVAL_MS * 8 / 10) / 2;

    private EngineIoSocket socket;
    private Heartbeat heartbeat;

    // ─────────────────────────────────────────────────────────────────────
    // T1이 흔들리면: 배수를 만지지 말고 간격 단언(isLessThanOrEqualTo)만 지운다.
    //
    // 원래 처방은 "T1을 통째로 지운다"였다. 2026-08-05에 실측하고 바꿨다.
    //
    // 재측정(깨끗한 트리, 25회): 실패 0. 관측 403~413ms / 임계 800ms —
    // 여유 387ms에 지터가 ±5ms다. 전체 테스트와 함께 돌려도 4~5ms만 밀린다.
    // 흔들림 1회 관측은 오염된 표본에서 나왔고 재현되지 않았다.
    // 논리 CPU 18개를 전부 굶겨도 10/10 통과했다(최대 758ms).
    //
    // 그래서 지우지 않는다. 다만 그 포화에서 여유가 42ms까지 줄었으므로
    // 간격 단언은 언젠가 CI에서 흔들릴 수 있다. 그때 이 테스트를 통째로
    // 버리지 않아도 되게 건수 단언을 함께 뒀다 — 같은 포화에서 간격이
    // 1.5배 벌어지는 동안 건수는 10회 전부 8건으로 꿈쩍도 안 했다.
    //
    // 배수·주기를 만지는 것은 여전히 금지다. 그 순간 T12의 2배 여유도 같이
    // 흔들리고, 무엇이 무엇을 지키는지가 흐려진다.
    // ─────────────────────────────────────────────────────────────────────

    /**
     * T1·T12가 성립하기 위한 전제. 이게 깨지면 두 테스트 다 무의미하므로
     * 각 테스트 안에서 부른다 — 별도 테스트로 두면 순서에 따라 안 돌 수 있다.
     */
    private static void 지연이_임계보다_큰지_먼저_확인한다(Handshake handshake) {
        assertThat(SLOW_HANDLER)
                .as("지연이 임계 이하면 사고 상태에서도 간격이 임계를 안 넘어 T12가 못 잡는다")
                .isGreaterThan(handshake.pingThreshold());
    }

    @AfterEach
    void tearDown() {
        if (heartbeat != null) heartbeat.close();
        if (socket != null) socket.close();
        behavior.reset();
    }

    /**
     * T1 — 본 재현. 사고 원인은 건수가 아니라 스레드 공유였다.
     * 수신 핸들러를 느리게 만들어 ping 스레드가 막히는지 본다.
     */
    @Test
    void 수신_핸들러가_느려도_ping_간격이_임계를_넘지_않는다() throws Exception {
        behavior.pingIntervalMillis = PING_INTERVAL_MS;
        behavior.pingTimeoutMillis = PING_TIMEOUT_MS;

        Handshake handshake = openWithSlowHandler(SLOW_HANDLER);
        지연이_임계보다_큰지_먼저_확인한다(handshake);
        heartbeat = Heartbeat.start(socket, handshake, () -> { });

        // 채팅을 계속 밀어 넣어 수신 스레드를 붙잡아 둔다.
        // 홍수가 관측 창(3초)보다 오래 가도록 건수를 잡는다 — 창 안에서 재는
        // 간격이 "홍수 중"의 값이어야 한다.
        for (int i = 0; i < 20; i++) {
            behavior.emitChat("{\"content\":\"x\",\"messageTime\":1}");
        }
        Thread.sleep(OBSERVE_WINDOW.toMillis());

        // 자를 가짜 서버 쪽에 둔다. Heartbeat.maxPingGap()은 클라이언트의
        // 자기 신고라, 그것으로 재면 "지표가 틀린 경우"를 못 잡는다.
        // 서버가 2를 실제로 받은 시각이 지상 진실이고, T12가 같은 자를 쓴다.
        assertThat(behavior.maxPingGapObserved())
                .as("수신이 밀리면 ping이 막힌다 — 이게 8/1 사고다")
                .isLessThanOrEqualTo(handshake.pingThreshold());

        // 간격과 건수는 서로 다른 회귀를 잡는다. 둘 다 있어야 한다.
        //
        // 간격은 "한 주기를 통째로 걸렀다"를 잡지만 부하에 민감하다 — 실측에서
        // 정상 부하 여유가 387ms인데 CPU 18코어를 전부 굶기면 42ms까지 줄었다.
        // 건수는 반대다. 같은 포화에서 간격이 1.5배 벌어지는 동안에도 10회 전부
        // 8건이었다. scheduleAtFixedRate가 밀린 주기를 따라잡아 창 안의 총량이
        // 보존되기 때문이다.
        //
        // 그리고 8/1 사고는 "한 번 걸렀다"가 아니라 74초간 0회였다 — 지속적
        // 막힘이고, 건수가 그 모양을 직접 잰다.
        assertThat(pingsReceived())
                .as("창 안에 ping이 %d건 이하면 지속적으로 막힌 것이다 — 8/1이 그 모양이었다", MIN_PINGS)
                .isGreaterThan(MIN_PINGS);
    }

    /** T2 — 보조. 건수로도 밀어 본다. */
    @Test
    void 채팅이_쏟아져도_ping_간격이_임계를_넘지_않는다() throws Exception {
        behavior.pingIntervalMillis = PING_INTERVAL_MS;
        behavior.pingTimeoutMillis = PING_TIMEOUT_MS;

        Handshake handshake = openWithSlowHandler(Duration.ZERO);
        heartbeat = Heartbeat.start(socket, handshake, () -> { });

        for (int i = 0; i < 3_000; i++) {
            behavior.emitChat("{\"content\":\"x\",\"messageTime\":1}");
        }
        Thread.sleep(OBSERVE_WINDOW.toMillis());

        assertThat(behavior.maxPingGapObserved()).isLessThanOrEqualTo(handshake.pingThreshold());
    }

    /** T11 — 지표 자체 검증. pong이 끊기면 공백이 실제로 커지는가. */
    @Test
    void 서버가_pong을_멈추면_pong_공백이_커진다() throws Exception {
        behavior.pingIntervalMillis = PING_INTERVAL_MS;
        behavior.pingTimeoutMillis = PING_TIMEOUT_MS;
        behavior.disconnectWhenPingMissing = false;

        Handshake handshake = openWithSlowHandler(Duration.ZERO);
        heartbeat = Heartbeat.start(socket, handshake, () -> { });
        Thread.sleep(1_000);
        assertThat(heartbeat.maxPongGap()).isLessThanOrEqualTo(handshake.pongThreshold());

        behavior.answerPong = false;
        Thread.sleep(2_000);

        assertThat(heartbeat.maxPongGap())
                .as("pong이 끊겼는데 공백이 안 커지면 이 지표는 아무것도 못 잡는다")
                .isGreaterThan(handshake.pongThreshold());
    }

    /**
     * <b>T12 — 자기검사. 이 테스트가 이 파일 전체의 값어치를 정한다.</b>
     *
     * <p>2026-08-01 사고는 "ping을 안 보냈다"가 아니라 <b>"보내려는 ping이 수신
     * 처리 뒤에 줄 서서 못 나갔다"</b>이다. 회귀도 그 형태로 온다 — 다음 사람이
     * 요약 집계를 ping 스레드에 얹거나, 전송을 수신 콜백으로 옮기는 식이다.
     *
     * <p>그래서 자기검사는 <b>사고 구조를 실제로 재현</b>해야 한다. ping 전송을
     * 수신 스레드에 얹고, T1과 <b>똑같은 단언</b>이 빨간불이 되는지 본다.
     * 빨간불이 안 되면 T1은 그 회귀를 못 잡는다는 뜻이고, T1·T2는 장식이다.
     *
     * <p>"하트비트를 아예 안 돌린다"로는 이걸 못 덮는다. 그건 T1이 간격을
     * 재고 있다는 것만 확인한다.
     */
    @Test
    void ping을_수신_스레드에_얹으면_T1과_같은_단언이_실제로_깨진다() throws Exception {
        behavior.pingIntervalMillis = PING_INTERVAL_MS;
        behavior.pingTimeoutMillis = PING_TIMEOUT_MS;
        behavior.disconnectWhenPingMissing = false;    // 끊기 전에 간격을 재야 한다

        // 2026-08-01의 아키텍처를 그대로 재현한다 — 전용 스케줄러 없이,
        // 수신 콜백이 프레임을 처리한 "뒤에" 시간을 보고 ping을 보낸다.
        AtomicLong lastPingNanos = new AtomicLong(System.nanoTime());
        AtomicReference<Handshake> shaken = new AtomicReference<>();
        Handshake handshake = openWith(frame -> {
            // T1의 openWithSlowHandler와 똑같이 EVENT에서만 잔다.
            // 자기검사는 변수 하나(ping을 어디서 보내나)만 달라야 성립한다 —
            // 프레임 종류까지 다르면 "다른 부하를 준 것 아니냐"가 남는다.
            if (frame.type() != EngineIoFrame.Type.EVENT) {
                return;
            }
            sleepQuietly(SLOW_HANDLER);                // T1과 똑같은 지연
            Duration period = shaken.get().sendPeriod();
            if (System.nanoTime() - lastPingNanos.get() >= period.toNanos()) {
                socket.sendPing();
                lastPingNanos.set(System.nanoTime());
            }
        });
        shaken.set(handshake);
        지연이_임계보다_큰지_먼저_확인한다(handshake);

        for (int i = 0; i < 20; i++) {
            behavior.emitChat("{\"content\":\"x\",\"messageTime\":1}");
        }
        Thread.sleep(OBSERVE_WINDOW.toMillis());

        // 사고 구조가 실제로 재현됐다는 양성 대조가 먼저다.
        //
        // T1과 부등호가 반대라 같은 성질이 정반대로 작동한다. maxPingGapObserved가
        // "흘러가는 중인 공백"까지 세므로 ping이 0회면 값이 3초까지 자라는데,
        // T1은 <= 임계라 빨간불이 되어 보호되고 T12는 > 임계라 초록불이 된다.
        // 실제로 재 보면 틀린 형태(0회, 3016ms)가 옳은 재현(1회, 1607ms)보다
        // 두 배 여유 있게 통과한다 — 여유값으로는 사람이 눈치챌 수 없다.
        //
        // T12는 T1·T2의 값어치를 보증하는 유일한 근거인데, 이 줄이 없으면
        // 그 보증서가 자기 위조를 못 잡는다.
        assertThat(behavior.receivedFrames())
                .as("사고 구조가 재현되지 않았다 — ping이 한 번도 안 나갔다면 이건 "
                        + "'ping 0회'를 잰 것이고 회귀는 못 잡는다")
                .contains("2");

        assertThat(behavior.maxPingGapObserved())
                .as("수신 스레드에 얹었는데도 통과한다면 T1은 8/1 회귀를 못 잡는다")
                .isGreaterThan(handshake.pingThreshold());

        // T1의 건수 단언도 자기검사가 있어야 한다. 없으면 그 줄은 "정상에서
        // 초록"만 보인 것이고, 사고에서 빨간불이 되는지는 아무도 확인 안 한 셈이다.
        //
        // 부등호가 T1과 정반대라 임계가 양쪽에서 조인다 — 임계가 0이면 여기가
        // 깨지고(사고 관측 1건), 8 이상이면 T1이 깨진다(정상 관측 8건).
        //
        // 다만 짝이 조이는 것은 한 값이 아니라 구간이다. 임계를 갈아 끼워 실측:
        // 0 → T12만 빨강 · 1·3·7 → 둘 다 초록 · 8 → T1만 빨강.
        // 즉 정상 8건 / 사고 1건이 임계를 [1,7]로 조이고, 그 안에서는 어느 값이든
        // 둘 다 통과한다. 여기 원래 "임계를 아무 값으로나 바꿔 두 단언을 동시에
        // 통과시킬 수 없다"고 적혀 있었는데 그것은 사실이 아니다.
        assertThat(pingsReceived())
                .as("사고 구조에서도 건수가 %d건을 넘으면 T1의 건수 단언은 회귀를 못 잡는다", MIN_PINGS)
                .isLessThanOrEqualTo(MIN_PINGS);
    }

    /** 요약·집계를 ping 스레드에 얹으면 8/1이 재현된다. 지금 상태를 못박는다. */
    @Test
    void ping은_전용_스레드에서만_나간다() throws Exception {
        behavior.pingIntervalMillis = PING_INTERVAL_MS;
        behavior.pingTimeoutMillis = PING_TIMEOUT_MS;

        Handshake handshake = openWithSlowHandler(Duration.ZERO);
        heartbeat = Heartbeat.start(socket, handshake, () -> { });
        Thread.sleep(1_000);

        assertThat(heartbeat.senderThreadNames()).containsExactly("chzzk-ping");
    }

    /**
     * ping 송신이 실패해도 스케줄러는 계속 돌아야 한다. 예외가 밖으로 나가면
     * scheduleAtFixedRate가 <b>조용히</b> 멈춰 ping이 영영 안 나가고, 그것이
     * 정확히 8/1이다 — 오류 로그도 카운터도 없이 서버가 끊는다.
     */
    @Test
    void ping_송신이_실패해도_스케줄러가_멈추지_않는다() throws Exception {
        behavior.pingIntervalMillis = PING_INTERVAL_MS;
        behavior.pingTimeoutMillis = PING_TIMEOUT_MS;

        Handshake handshake = openWithSlowHandler(Duration.ZERO);
        socket.close();                        // 이후 모든 송신이 실패한다

        AtomicLong callbacks = new AtomicLong();
        heartbeat = Heartbeat.start(socket, handshake, callbacks::incrementAndGet);
        Thread.sleep(1_500);                   // 송신 주기 400ms — 서너 번 돈다

        assertThat(heartbeat.sendFailureCount())
                .as("첫 실패에 스케줄러가 죽으면 1에서 멈춘다")
                .isGreaterThan(1);
        assertThat(callbacks.get()).isEqualTo(heartbeat.sendFailureCount());
        assertThat(heartbeat.callbackFailureCount())
                .as("콜백이 멀쩡한데 실패로 세면 그 카운터는 아무것도 못 가른다")
                .isZero();
    }

    /**
     * 실패 콜백은 catch 블록 안에서 불린다. <b>그것이 던지면 예외가 밖으로 나가
     * 스케줄러가 멈춘다.</b> 지금은 전부 빈 람다라 안 터지지만, 태스크 9가 붙일
     * 진짜 콜백(health를 DOWN으로 돌리고 로그를 남기는)이 던지는 순간 실체가 된다.
     */
    @Test
    void 실패_콜백이_던져도_스케줄러가_멈추지_않는다() throws Exception {
        behavior.pingIntervalMillis = PING_INTERVAL_MS;
        behavior.pingTimeoutMillis = PING_TIMEOUT_MS;

        Handshake handshake = openWithSlowHandler(Duration.ZERO);
        socket.close();

        heartbeat = Heartbeat.start(socket, handshake, () -> {
            throw new IllegalStateException("콜백이 터졌다");
        });
        Thread.sleep(1_500);

        assertThat(heartbeat.sendFailureCount())
                .as("콜백이 던진 예외가 밖으로 나가면 스케줄러가 죽어 1에서 멈춘다")
                .isGreaterThan(1);

        // 삼킨 자리에 카운터가 없으면 콜백이 매번 터져도 아무 데도 안 남는다.
        // health를 DOWN으로 돌리는 일이 콜백 안에 있으면, 수집이 죽었는데
        // health는 UP이고 요약에도 표시가 없는 상태가 된다.
        assertThat(heartbeat.callbackFailureCount())
                .as("삼켰으면 세야 한다 — 안 세면 조용한 실패를 우리가 만드는 것이다")
                .isGreaterThan(1);
    }

    private Handshake openWithSlowHandler(Duration delay) throws Exception {
        return openWith(frame -> {
            if (frame.type() == EngineIoFrame.Type.EVENT && !delay.isZero()) {
                sleepQuietly(delay);            // 느린 핸들러
            }
        });
    }

    /**
     * EVENT 프레임을 받았을 때 할 일을 테스트가 직접 정한다.
     * T12는 여기에 "ping 전송"을 얹어 2026-08-01 아키텍처를 재현한다.
     */
    private Handshake openWith(java.util.function.Consumer<EngineIoFrame> onEvent) throws Exception {
        CountDownLatch open = new CountDownLatch(1);
        AtomicReference<Handshake> ref = new AtomicReference<>();

        socket = EngineIoSocket.open(uri(), frame -> {
            if (frame.type() == EngineIoFrame.Type.OPEN) {
                ref.set(Handshake.parse(frame.payload()));
                open.countDown();
                return;
            }
            if (frame.type() == EngineIoFrame.Type.PONG && heartbeat != null) {
                heartbeat.recordPong();
                return;
            }
            onEvent.accept(frame);
        }, () -> { });

        assertThat(open.await(5, TimeUnit.SECONDS)).isTrue();
        return ref.get();
    }

    /**
     * 가짜 서버가 실제로 받은 ping 건수. <b>T1과 T12가 같은 자를 쓴다.</b>
     *
     * <p>{@code Heartbeat}의 자기 신고가 아니라 서버가 받은 프레임을 센다 —
     * 지표 자체가 틀린 경우를 잡으려면 자가 저쪽에 있어야 한다. 이 테스트의
     * 클라이언트가 WS로 내보내는 것은 ping뿐이라(루트 CONNECT는 함정 4로 금지)
     * 다른 프레임이 섞여 헛통과할 입구가 없다.
     */
    private long pingsReceived() {
        return behavior.receivedFrames().stream().filter("2"::equals).count();
    }

    private static void sleepQuietly(Duration d) {
        try { Thread.sleep(d.toMillis()); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private URI uri() {
        return URI.create("ws://localhost:" + port + "/socket.io/?auth=T&EIO=3&transport=websocket");
    }
}
