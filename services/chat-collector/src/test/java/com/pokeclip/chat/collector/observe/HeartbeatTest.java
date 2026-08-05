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

    private EngineIoSocket socket;
    private Heartbeat heartbeat;

    // ─────────────────────────────────────────────────────────────────────
    // T1이 CI에서 한 번이라도 흔들리면: 배수를 만지지 말고 T1을 지운다.
    //
    // T1의 여유는 구조상 항상 송신 주기와 같다(관측 ≈ 주기, 임계 = 주기 × 2).
    // 주기를 키우는 것 말고 늘릴 방법이 없고, CI의 GC·컨테이너 스케줄링은
    // 어떤 값이든 넘을 수 있다. 간헐 실패하는 테스트는 사람이 곧 무시하게
    // 되고, 그러면 "고쳐도 빨간불"과 결과가 같아진다.
    //
    // T1을 지워도 잃는 것이 없다:
    //   · 회귀 검출 → T12가 한다. 임계의 2.02배, 편차 8ms로 안정적이다
    //   · "정상 상태에서도 임계를 지킨다" → 태스크 11의 실서버 10분 실측이
    //     훨씬 강하게 한다. 압축 비율이 아니라 진짜 25초/60초다
    //
    // 배수를 다시 만지는 것은 금지다. 그 순간 T12의 2배 여유도 같이 흔들리고,
    // 무엇이 무엇을 지키는지가 흐려진다.
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
        Thread.sleep(3_000);

        // 자를 가짜 서버 쪽에 둔다. Heartbeat.maxPingGap()은 클라이언트의
        // 자기 신고라, 그것으로 재면 "지표가 틀린 경우"를 못 잡는다.
        // 서버가 2를 실제로 받은 시각이 지상 진실이고, T12가 같은 자를 쓴다.
        assertThat(behavior.maxPingGapObserved())
                .as("수신이 밀리면 ping이 막힌다 — 이게 8/1 사고다")
                .isLessThanOrEqualTo(handshake.pingThreshold());
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
        Thread.sleep(3_000);

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
        Thread.sleep(3_000);

        assertThat(behavior.maxPingGapObserved())
                .as("수신 스레드에 얹었는데도 통과한다면 T1은 8/1 회귀를 못 잡는다")
                .isGreaterThan(handshake.pingThreshold());
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

    private static void sleepQuietly(Duration d) {
        try { Thread.sleep(d.toMillis()); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private URI uri() {
        return URI.create("ws://localhost:" + port + "/socket.io/?auth=T&EIO=3&transport=websocket");
    }
}
