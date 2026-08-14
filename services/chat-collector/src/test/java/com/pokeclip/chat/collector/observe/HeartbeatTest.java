package com.pokeclip.chat.collector.observe;

import com.pokeclip.chat.collector.engineio.EngineIoFrame;
import com.pokeclip.chat.collector.engineio.EngineIoSocket;
import com.pokeclip.chat.collector.engineio.Handshake;
import com.pokeclip.chat.collector.engineio.PingFailure;
import com.pokeclip.chat.collector.engineio.PingSender;
import com.pokeclip.chat.collector.fake.FakeChzzkBehavior;
import com.pokeclip.chat.collector.fake.FakeChzzkTest;
import com.pokeclip.chat.collector.support.IntegrationTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-08-01 사고의 회귀 방지. 관측: 74초간 ping 0회 → 서버가 조용히 끊음 →
 * 메시지 15건 유실, 오류 로그 0줄.
 *
 * <p>가짜 서버가 pingInterval을 줄여 주므로 실측 비율을 유지한 채 몇 초에 끝난다.
 */
@FakeChzzkTest
class HeartbeatTest extends IntegrationTestSupport {

    /** 수립 예산. 이 파일은 접속 자체를 보므로 넉넉히 준다. */
    private static final java.time.Duration BUDGET = java.time.Duration.ofSeconds(5);

    /** 중단 신호가 없는 호출. 중단은 EstablishCutCleanupTest가 본다. */
    private static final java.util.function.BooleanSupplier NO_ABORT = () -> false;

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

    /** 홍수를 밀어 넣고 간격·건수를 재는 창. 아래 {@code minPings}가 여기서 나온다. */
    private static final Duration OBSERVE_WINDOW = Duration.ofSeconds(3);

    /**
     * <b>부하 도달 양성 대조의 하한.</b> 홍수가 수신 스레드에 실제로 닿았는가를 본다.
     *
     * <p>이게 없으면 T1·T2에 남는 단언이 ping 건수 하나뿐인데, 그 줄은 전용
     * 스케줄러만으로도 창 안에 8건을 채워 <b>부하와 무관하게 참</b>이 된다 —
     * 홍수 루프를 통째로 지워도 둘 다 초록이었다(2026-08-06 실측). 그러면 T1·T2는
     * "경합에서 ping이 나갔다"가 아니라 "한가할 때 ping이 나갔다"를 잰 것이 되고,
     * 겨냥한 회귀를 하나도 못 잡는다. T12는 {@code contains("2")}가 같은 일을 한다.
     *
     * <p><b>T1의 실측은 1건이다.</b> 이론값 {@code 창 / SLOW_HANDLER = 3000/1600 ≈ 1.9}
     * 중 한 자리를 핸드셰이크 직후의 connected SYSTEM 이벤트가 먼저 먹는다 —
     * 그것도 EVENT 프레임이라 같은 지연을 태우기 때문이다. 그래서 첫 CHAT은 창의
     * 1600ms 지점에 들어온다(5회, 편차 0).
     *
     * <p>실측이 1이면 하한도 1뿐이라 <b>숫자 여유가 없다.</b> 대신 시간으로 여유를
     * 준다 — 아래 {@code awaitAtLeast}가 창이 끝난 뒤 2초를 더 기다린다. 첫 CHAT의
     * 마감이 3000ms에서 5000ms로 늘어 필요치 1600ms의 <b>3.1배</b>가 된다.
     * 홍수가 아예 안 오면 시한을 다 쓰고 0이라 판별은 그대로 선다.
     */
    private static final long MIN_CHATS_T1 = 1;

    /**
     * T2의 부하 도달 하한. 지연이 0이라 3,000건이 그대로 도착한다 —
     * 실측 <b>3,000건</b>(5회, 편차 0. 홍수와 정확히 같다).
     *
     * <p>하한은 <b>1,000</b>이다. 실측의 1/3이라 여유가 3배다. 창 3초 안에 3,000건을
     * 다 못 훑을 만큼 부하가 걸려도 1/3은 남는다는 뜻으로 잡은 값이지 기대값이 아니다.
     * 홍수가 안 오면 0이라 판별에는 여유가 넘친다.
     */
    private static final long MIN_CHATS_T2 = 1_000;

    /**
     * 창 안에 나가야 할 ping의 하한. <b>운영이 준 송신 주기에서 파생한다</b> —
     * 여기서 주기를 다시 계산하면({@code pingInterval × 0.8}) 운영
     * {@code Handshake.sendPeriod()}의 배수를 복제한 것이 되어, 운영 배수가
     * 바뀌는 날 이 임계만 조용히 어긋난다.
     *
     * <p>창 3초 ÷ 주기 400ms = 7건이 기대값이고, 그 절반인 <b>3건</b>이 하한이다.
     * 실측(25회씩)은 정상 <b>8건</b>(편차 0) · 사고 <b>1건</b>(편차 0)이라
     * 양쪽으로 여유가 +5 / −2다.
     *
     * <p><b>{@code Math.max(1, …)}이 하한을 깐다. 정수 나눗셈이 0을 만들기 때문이다</b> —
     * 배수 복제를 없애고 파생으로 바꾼 뒤에도 그대로다. {@code pingInterval = 1877}부터
     * 0이고 <b>실제 치지직 값 25000ms에서도 0</b>이다(주기 20000ms → 3000/20000/2 = 0).
     *
     * <p>하한이 0이면 T1의 {@code > 0}이 ping 1건에도 통과해 거의 자동 참이 된다.
     * 짝인 T12의 {@code <= 0}이 빨간불이 되므로 짝 전체가 뚫리지는 않지만,
     * T1 한 줄만 놓고 보면 아무것도 안 재는 줄이 된다.
     */
    private static long minPings(Handshake handshake) {
        return Math.max(1, OBSERVE_WINDOW.toMillis() / handshake.sendPeriod().toMillis() / 2);
    }

    private EngineIoSocket socket;
    private Heartbeat heartbeat;

    // ─────────────────────────────────────────────────────────────────────
    // 왜 T1·T2가 "최대 간격"이 아니라 "ping 건수"를 재는가 (2026-08-06 실측)
    //
    // 간격 단언(maxPingGapObserved <= 임계)이 있었는데 지웠다. 부하에서
    // 거짓 빨간불을 낸다.
    //
    //   압력 없음        29초 시행    실패 0
    //   스피너 18개      36~51초      22회 중 실패 0
    //   스피너 40개      60~86초      T1 4회 · T2 3회 실패 (최악 2.27초 / 임계 0.8초)
    //
    // 같은 압력에서 건수는 한 번도 안 깨졌다. scheduleAtFixedRate가 밀린
    // 주기를 따라잡아 창 안의 총량이 보존되기 때문이다. 간격은 최악값이라
    // 스케줄링 지연 한 번에 깨지고, 건수는 지속적으로 막혀야 깨진다.
    //
    // 그리고 8/1 사고가 지속적 막힘이었다 — 74초간 ping 0회. 건수가 그
    // 모양을 직접 잰다.
    //
    // 잃은 것: "몇 초 밀렸나"를 못 잰다. 그건 실서버 10분 실측이 훨씬 강하게
    // 잰다 — 압축 비율이 아니라 진짜 25초/60초에서 요약 23줄 내내 20.0초,
    // 지터 0이었다.
    //
    // 잃은 것 하나 더: T2가 잡던 모양 하나가 T1 단독 책임으로 옮겨갔다.
    // 게이트 없이 이벤트마다 ping을 쏘는 회귀는 홍수 중에 건수가 폭증해
    // (실측 3001건) T2의 건수 단언을 그냥 통과한다 — 지워진 간격 단언은
    // 2887ms로 잡았다. T1이 같은 변형을 1건으로 잡으므로 전체에는 구멍이
    // 없지만, T2 한 줄만 보고 그 모양까지 지킨다고 읽으면 안 된다.
    //
    // 배수·주기·SLOW_HANDLER를 만지지 마라. 아래 전제가 같이 흔들린다.
    // ─────────────────────────────────────────────────────────────────────

    /**
     * T1·T12가 성립하기 위한 전제. 이게 깨지면 두 테스트 다 무의미하므로
     * 각 테스트 안에서 부른다 — 별도 테스트로 두면 순서에 따라 안 돌 수 있다.
     *
     * <p><b>이 전제가 건수 단언의 판별력을 떠받친다.</b> 사고 구조에서는 이벤트마다
     * ping이 한 번 나가고 이벤트 처리가 직렬이므로, 창 안의 건수가
     * {@code 창 / SLOW_HANDLER}로 묶인다(emit을 아무리 빨리 해도 마찬가지다).
     * 그 값이 임계({@code 창 / 송신주기 / 2}) 이하가 되려면
     * <b>{@code SLOW_HANDLER ≥ 송신주기 × 2 = ping 임계}</b>여야 한다 —
     * 정확히 아래가 단언하는 것이다.
     *
     * <p><b>실측(2026-08-06).</b> 전제를 깨면(지연을 임계의 0.8배인 640ms로) 사고
     * 구조의 건수가 8 → <b>4</b>로만 줄어 임계 3을 못 넘긴다. 그런데 T12가 조용히
     * 통과하지는 않는다 — 건수 단언보다 <b>앞</b>에 있는 간격 양성 대조가 먼저
     * 터진다(관측 0.730S / 임계 0.8S). AssertJ는 fail-fast라 건수 단언은 실행조차
     * 안 된다.
     *
     * <p>그래서 이 가드가 하는 일은 "T12를 빨간불로 만드는 것"이 아니라
     * <b>그 빨간불의 원인이 SLOW_HANDLER라는 것을 지목해 주는 것</b>이다.
     * 가드가 없으면 T12는 시끄럽게 터지되 아무도 원인을 못 알아본다.
     */
    private static void 지연이_임계보다_큰지_먼저_확인한다(Handshake handshake) {
        assertThat(SLOW_HANDLER)
                .as("지연이 임계 이하면 사고 재현이 약해진다 — T12는 간격 양성 대조에서 먼저 터진다")
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
    void 수신_핸들러가_느려도_ping이_계속_나간다() throws Exception {
        behavior.pingIntervalMillis = PING_INTERVAL_MS;
        behavior.pingTimeoutMillis = PING_TIMEOUT_MS;

        AtomicLong chatsHandled = new AtomicLong();
        Handshake handshake = openWithSlowHandler(SLOW_HANDLER, chatsHandled);
        지연이_임계보다_큰지_먼저_확인한다(handshake);
        heartbeat = Heartbeat.start(socket, handshake, onSendFailed(cause -> { }));

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
        //
        // 창이 끝난 시각의 건수를 여기서 고정한다. 아래 대기가 이 값을 늘리면
        // 늘어난 시간만큼 ping이 더 나가 건수 단언이 헐거워진다.
        long pings = pingsReceived();

        // 부하 도달 양성 대조가 먼저다. 홍수가 수신 스레드에 안 닿았으면 아래 건수
        // 단언은 "한가한 상태에서 ping이 나갔다"를 재고 그냥 통과한다.
        assertThat(awaitAtLeast(chatsHandled, MIN_CHATS_T1))
                .as("홍수가 수신 스레드에 도달하지 않았다 — 아래 건수 단언은 경합을 잰 것이 아니다")
                .isGreaterThanOrEqualTo(MIN_CHATS_T1);

        long minPings = minPings(handshake);
        assertThat(pings)
                .as("창 안에 ping이 %d건 이하면 지속적으로 막힌 것이다 — 8/1이 그 모양이었다", minPings)
                .isGreaterThan(minPings);
    }

    /** T2 — 보조. 느린 핸들러가 아니라 건수로 밀어 본다. */
    @Test
    void 채팅이_쏟아져도_ping이_계속_나간다() throws Exception {
        behavior.pingIntervalMillis = PING_INTERVAL_MS;
        behavior.pingTimeoutMillis = PING_TIMEOUT_MS;

        AtomicLong chatsHandled = new AtomicLong();
        Handshake handshake = openWithSlowHandler(Duration.ZERO, chatsHandled);
        heartbeat = Heartbeat.start(socket, handshake, onSendFailed(cause -> { }));

        for (int i = 0; i < 3_000; i++) {
            behavior.emitChat("{\"content\":\"x\",\"messageTime\":1}");
        }
        Thread.sleep(OBSERVE_WINDOW.toMillis());

        long pings = pingsReceived();

        // T1과 같은 이유의 양성 대조. 3,000건을 밀어 넣었다고 적혀 있어도 그것이
        // 수신 스레드까지 왔다는 근거는 여기밖에 없다.
        assertThat(awaitAtLeast(chatsHandled, MIN_CHATS_T2))
                .as("홍수가 수신 스레드에 도달하지 않았다 — 아래 건수 단언은 홍수를 잰 것이 아니다")
                .isGreaterThanOrEqualTo(MIN_CHATS_T2);

        long minPings = minPings(handshake);
        assertThat(pings)
                .as("3,000건이 쏟아지는 동안 ping이 %d건 이하면 홍수가 ping을 막은 것이다", minPings)
                .isGreaterThan(minPings);
    }

    /** T11 — 지표 자체 검증. pong이 끊기면 공백이 실제로 커지는가. */
    @Test
    void 서버가_pong을_멈추면_pong_공백이_커진다() throws Exception {
        behavior.pingIntervalMillis = PING_INTERVAL_MS;
        behavior.pingTimeoutMillis = PING_TIMEOUT_MS;
        behavior.disconnectWhenPingMissing = false;

        Handshake handshake = openWithSlowHandler(Duration.ZERO);
        heartbeat = Heartbeat.start(socket, handshake, onSendFailed(cause -> { }));
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
        Handshake handshake = openWith((frame, shaken) -> {
            // T1의 openWithSlowHandler와 똑같이 EVENT에서만 잔다.
            // 자기검사는 변수 하나(ping을 어디서 보내나)만 달라야 성립한다 —
            // 프레임 종류까지 다르면 "다른 부하를 준 것 아니냐"가 남는다.
            if (frame.type() != EngineIoFrame.Type.EVENT) {
                return;
            }
            sleepQuietly(SLOW_HANDLER);                // T1과 똑같은 지연
            Duration period = shaken.sendPeriod();
            if (System.nanoTime() - lastPingNanos.get() >= period.toNanos()) {
                socket.sendPing();
                lastPingNanos.set(System.nanoTime());
            }
        });
        지연이_임계보다_큰지_먼저_확인한다(handshake);

        for (int i = 0; i < 20; i++) {
            behavior.emitChat("{\"content\":\"x\",\"messageTime\":1}");
        }
        Thread.sleep(OBSERVE_WINDOW.toMillis());

        // 사고 구조가 실제로 재현됐다는 양성 대조가 먼저다.
        //
        // ping이 한 번도 안 나갔으면 이건 "ping 0회"를 잰 것이고, 그 상태는
        // 건수 단언을 여유 있게 통과시켜 버린다(0 <= 3). 그러면 T12는
        // "수신 스레드에 얹으면 깨진다"가 아니라 "안 보내면 깨진다"를 잰 것이 되고,
        // 그건 회귀를 못 잡는다.
        //
        // T12는 T1·T2의 값어치를 보증하는 유일한 근거인데, 이 줄이 없으면
        // 그 보증서가 자기 위조를 못 잡는다.
        assertThat(behavior.receivedFrames())
                .as("사고 구조가 재현되지 않았다 — ping이 한 번도 안 나갔다면 이건 "
                        + "'ping 0회'를 잰 것이고 회귀는 못 잡는다")
                .contains("2");

        // 사고 구조에서 간격이 실제로 벌어진다는 관측. T1·T2가 더 이상 간격을
        // 안 재므로 이 줄은 짝이 아니라 양성 대조다 — 2026-08-01에 무슨 일이
        // 있었는지를 숫자로 남긴다.
        //
        // 부등호가 반대라 부하에 안전하다. 부하는 공백을 키우므로 통과 쪽으로
        // 민다. T1·T2의 간격 단언을 지운 이유가 여기엔 해당하지 않는다.
        assertThat(behavior.maxPingGapObserved())
                .as("사고 구조인데 간격이 안 벌어졌다면 재현이 안 된 것이다")
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
        long minPings = minPings(handshake);
        assertThat(pingsReceived())
                .as("사고 구조에서도 건수가 %d건을 넘으면 T1의 건수 단언은 회귀를 못 잡는다", minPings)
                .isLessThanOrEqualTo(minPings);
    }

    /** 요약·집계를 ping 스레드에 얹으면 8/1이 재현된다. 지금 상태를 못박는다. */
    @Test
    void ping은_전용_스레드에서만_나간다() throws Exception {
        behavior.pingIntervalMillis = PING_INTERVAL_MS;
        behavior.pingTimeoutMillis = PING_TIMEOUT_MS;

        Handshake handshake = openWithSlowHandler(Duration.ZERO);
        heartbeat = Heartbeat.start(socket, handshake, onSendFailed(cause -> { }));
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
        heartbeat = Heartbeat.start(socket, handshake, onSendFailed(cause -> callbacks.incrementAndGet()));
        Thread.sleep(1_500);                   // 송신 주기 400ms — 서너 번 돈다

        assertThat(heartbeat.sendFailureCount())
                .as("첫 실패에 스케줄러가 죽으면 1에서 멈춘다")
                .isGreaterThan(1);
        // 세는 것과 알리는 것은 다른 자다. 실패는 매 주기 세지만 알림은 구간당 한 번이다 —
        // 아래 송신이_계속_실패해도_구간당_한_번만_알린다가 그 이유를 든다.
        assertThat(callbacks.get()).isOne();
        assertThat(heartbeat.callbackFailureCount())
                .as("콜백이 멀쩡한데 실패로 세면 그 카운터는 아무것도 못 가른다")
                .isZero();
    }

    /**
     * 실패 콜백은 catch 블록 안에서 불린다. <b>그것이 던지면 예외가 밖으로 나가
     * 스케줄러가 멈춘다.</b> 지금은 전부 빈 람다라 안 터지지만, 태스크 9가 붙일
     * 진짜 콜백(health를 DOWN으로 돌리고 로그를 남기는)이 던지는 순간 실체가 된다.
     *
     * <p><b>송신자가 성공과 실패를 오간다.</b> 알림이 실패 구간당 한 번이라,
     * 콜백을 두 번 이상 터뜨리려면 사이에 성공한 송신이 끼어 재무장해야 한다.
     * 죽은 소켓으로 계속 실패시키면 콜백이 한 번만 불려 <b>"매번 터져도 계속
     * 도는가"를 못 잰다.</b>
     */
    @Test
    void 실패_콜백이_던져도_스케줄러가_멈추지_않는다() throws Exception {
        AtomicInteger ticks = new AtomicInteger();
        Handshake handshake = new Handshake("s", Duration.ofMillis(100), Duration.ofMillis(240));
        PingSender flapping = () -> {
            if (ticks.incrementAndGet() % 2 == 1) {
                throw new PingFailure(PingFailure.Cause.CONNECTION_DEAD, new IOException("소켓이 죽었다"));
            }
        };
        long sendFailures;
        long callbackFailures;

        try (Heartbeat beat = Heartbeat.start(flapping, handshake, onSendFailed(cause -> {
            throw new IllegalStateException("콜백이 터졌다");
        }))) {
            awaitUntil(() -> beat.callbackFailureCount() > 1, Duration.ofSeconds(2));
            sendFailures = beat.sendFailureCount();
            callbackFailures = beat.callbackFailureCount();
        }

        assertThat(sendFailures)
                .as("콜백이 던진 예외가 밖으로 나가면 스케줄러가 죽어 1에서 멈춘다")
                .isGreaterThan(1);

        // 삼킨 자리에 카운터가 없으면 콜백이 매번 터져도 아무 데도 안 남는다.
        // health를 DOWN으로 돌리는 일이 콜백 안에 있으면, 수집이 죽었는데
        // health는 UP이고 요약에도 표시가 없는 상태가 된다.
        assertThat(callbackFailures)
                .as("삼켰으면 세야 한다 — 안 세면 조용한 실패를 우리가 만드는 것이다")
                .isGreaterThan(1);
    }

    /** 우리 잘못은 그대로 전해져야 한다. 연결 죽음으로 묶으면 재연결이 버그를 덮는다. */
    @Test
    void 송신이_우리_잘못으로_실패하면_원인을_그대로_전한다() throws Exception {
        AtomicReference<PingFailure.Cause> reported = new AtomicReference<>();
        Handshake handshake = new Handshake("s", Duration.ofMillis(100), Duration.ofMillis(240));
        PingSender broken = () -> {
            throw new PingFailure(PingFailure.Cause.MISUSE, new IllegalStateException("겹쳐 보냈다"));
        };

        try (Heartbeat beat = Heartbeat.start(broken, handshake, onSendFailed(reported::set))) {
            awaitUntil(() -> reported.get() != null, Duration.ofSeconds(2));
        }

        assertThat(reported.get())
                .as("우리 버그를 연결 죽음으로 묶으면 재연결이 성공하는 한 버그가 안 보인다")
                .isEqualTo(PingFailure.Cause.MISUSE);
    }

    /**
     * {@code PingSender}가 {@code PingFailure} 밖의 것을 던져도 스케줄러는 계속
     * 돌아야 한다. 예외가 밖으로 나가면 {@code scheduleAtFixedRate}가 <b>조용히</b>
     * 멈춰 ping이 영영 안 나가고, 그것이 2026-08-01의 결말이다.
     *
     * <p><b>이 테스트가 없으면 그 갈래를 통째로 지워도 전 테스트가 초록이다</b>
     * (2026-08-08 실측, 107개 실패 0). 다른 테스트의 송신자는 전부
     * {@code PingFailure}만 던져서 그 갈래에 아무도 안 들어간다.
     */
    @Test
    void 송신이_PingFailure_밖의_것을_던져도_스케줄러가_멈추지_않는다() throws Exception {
        AtomicLong reports = new AtomicLong();
        AtomicReference<PingFailure.Cause> lastCause = new AtomicReference<>();
        Handshake handshake = new Handshake("s", Duration.ofMillis(100), Duration.ofMillis(240));
        PingSender wild = () -> {
            throw new IllegalArgumentException("PingFailure가 아닌 것");
        };

        try (Heartbeat beat = Heartbeat.start(wild, handshake, onSendFailed(cause -> {
            lastCause.set(cause);
            reports.incrementAndGet();
        }))) {
            awaitUntil(() -> reports.get() > 1, Duration.ofSeconds(2));

            assertThat(beat.sendFailureCount())
                    .as("첫 실패에 스케줄러가 죽으면 1에서 멈추고 그 뒤 ping이 영영 안 나간다")
                    .isGreaterThan(1);
        }

        assertThat(lastCause.get())
                .as("못 가른 실패는 연결 죽음으로 본다 — 채팅 유실이 유일한 치명 실패라 "
                        + "붙잡고 멈추는 쪽이 더 나쁘다")
                .isEqualTo(PingFailure.Cause.CONNECTION_DEAD);
    }

    /**
     * <b>송신 실패도 구간당 한 번만 알린다.</b> 좀비 알림과 같은 모양이어야 한다.
     *
     * <p>이유도 같다 — 매 주기 알리면 재연결 요청이 실패가 이어지는 내내 쌓이고,
     * 첫 요청이 만든 루프에 밀린 나머지가 <b>대기 사유</b>로 앉는다. 그 루프가
     * 재접속에 성공하면 대기 사유가 재생돼 <b>막 세운 멀쩡한 세션을 헐어낸다.</b>
     * 좀비 쪽은 가드가 있어 신호가 하나뿐이라 이 길이 막혀 있었다.
     */
    @Test
    void 송신이_계속_실패해도_구간당_한_번만_알린다() throws Exception {
        AtomicInteger reports = new AtomicInteger();
        Handshake handshake = new Handshake("s", Duration.ofMillis(100), Duration.ofMillis(240));
        PingSender dead = () -> {
            throw new PingFailure(PingFailure.Cause.CONNECTION_DEAD, new IOException("소켓이 죽었다"));
        };
        long failures;

        try (Heartbeat beat = Heartbeat.start(dead, handshake,
                onSendFailed(cause -> reports.incrementAndGet()))) {
            // 송신 주기 80ms짜리 다섯 번. 매 주기 알리는 구현이면 여기서 건수가 불어난다.
            awaitUntil(() -> beat.sendFailureCount() >= 5, Duration.ofSeconds(2));
            failures = beat.sendFailureCount();
        }

        assertThat(failures)
                .as("실패가 쌓이지 않았다 — 아래 단언은 아무것도 안 잰 것이다")
                .isGreaterThanOrEqualTo(5);
        assertThat(reports.get())
                .as("실패 구간 하나에 알림은 한 번이다 — 매 주기 알리면 재연결 요청이 "
                        + "실패가 이어지는 내내 쌓인다")
                .isOne();
    }

    /**
     * <b>송신이 돌아왔다가 다시 죽으면 다시 알린다.</b> 가드를 하트비트 생애 1회로
     * 잠그면 두 번째 실패 구간이 통째로 안 알려진다 — 재연결이 그 절단을 영영 못 본다.
     *
     * <p>바로 위와 부등호가 반대다. 저쪽은 "이어지는 실패에 한 번만",
     * 이쪽은 "끊겼다 다시 실패하면 또". <b>한쪽만 고치지 마라</b> —
     * 좀비 알림의 짝({@code pong이_한_번_늦었다가…} · {@code 회복된_뒤_다시_끊기면…})과
     * 같은 구조다.
     */
    @Test
    void 송신이_회복된_뒤_다시_실패하면_다시_알린다() throws Exception {
        AtomicInteger reports = new AtomicInteger();
        AtomicInteger sends = new AtomicInteger();
        AtomicBoolean failing = new AtomicBoolean(true);
        Handshake handshake = new Handshake("s", Duration.ofMillis(100), Duration.ofMillis(240));
        PingSender flapping = () -> {
            sends.incrementAndGet();
            if (failing.get()) {
                throw new PingFailure(PingFailure.Cause.CONNECTION_DEAD, new IOException("소켓이 죽었다"));
            }
        };

        try (Heartbeat beat = Heartbeat.start(flapping, handshake,
                onSendFailed(cause -> reports.incrementAndGet()))) {
            awaitUntil(() -> reports.get() >= 1, Duration.ofSeconds(2));
            failing.set(false);                 // 송신이 돌아왔다
            // 성공한 송신이 실제로 있었다는 대조. 없으면 재무장할 기회가 없었던 것이라
            // 아래 두 번째 알림이 무엇 덕분인지 알 수 없다.
            int seen = sends.get();
            awaitUntil(() -> sends.get() >= seen + 2, Duration.ofSeconds(2));
            assertThat(sends.get())
                    .as("성공한 송신이 없었다면 재무장을 검사한 것이 아니다")
                    .isGreaterThanOrEqualTo(seen + 2);
            failing.set(true);                  // 두 번째 실패 구간
            awaitUntil(() -> reports.get() >= 2, Duration.ofSeconds(2));
        }

        assertThat(reports.get())
                .as("두 번째 실패 구간을 안 알리면 가드가 사고를 가린다")
                .isGreaterThanOrEqualTo(2);
    }

    /**
     * 좀비 연결 — 소켓은 살아 있고 ping도 나가는데 pong만 안 온다.
     * onClose가 안 오므로 이 판정이 없으면 영영 못 알아챈다.
     */
    @Test
    void pong이_임계를_넘도록_안_오면_알린다() throws Exception {
        AtomicReference<Duration> reported = new AtomicReference<>();
        AtomicInteger reports = new AtomicInteger();
        Handshake handshake = new Handshake("s", Duration.ofMillis(100), Duration.ofMillis(240));

        try (Heartbeat beat = Heartbeat.start(() -> { }, handshake, new HeartbeatListener() {
            @Override public void onSendFailed(PingFailure.Cause cause) { }
            @Override public void onPongTimeout(Duration gap) {
                reported.set(gap);
                reports.incrementAndGet();
            }
        })) {
            // pongThreshold = 80ms + 120ms = 200ms. 넉넉히 기다린다.
            awaitUntil(() -> reported.get() != null, Duration.ofSeconds(2));
            // 알린 뒤로도 pong은 여전히 안 온다. 송신 주기(80ms) 여섯 번을 더 태운다 —
            // 매 주기 알리는 구현이면 여기서 건수가 불어난다.
            Thread.sleep(500);
        }

        assertThat(reported.get())
                .as("pong이 끊긴 것을 안 알리면 좀비 연결이 자리를 문 채 영영 남는다")
                .isNotNull()
                .isGreaterThanOrEqualTo(handshake.pongThreshold());
        assertThat(reports.get())
                .as("좀비 구간 하나에 알림은 한 번이다 — 매 주기 알리면 재연결 요청이 "
                        + "임계를 넘는 내내 쌓인다")
                .isOne();
    }

    /** 양성 대조. pong이 제때 오면 조용해야 한다 — 안 그러면 멀쩡한 세션을 계속 끊는다. */
    @Test
    void pong이_제때_오면_알리지_않는다() throws Exception {
        AtomicInteger reports = new AtomicInteger();
        Handshake handshake = new Handshake("s", Duration.ofMillis(100), Duration.ofMillis(240));

        try (Heartbeat beat = Heartbeat.start(() -> { }, handshake, new HeartbeatListener() {
            @Override public void onSendFailed(PingFailure.Cause cause) { }
            @Override public void onPongTimeout(Duration gap) { reports.incrementAndGet(); }
        })) {
            long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
            while (System.nanoTime() < deadline) {
                beat.recordPong();
                Thread.sleep(20);
            }
        }

        assertThat(reports.get())
                .as("정상 연결을 좀비로 오인하면 멀쩡한 세션을 계속 끊는다")
                .isZero();
    }

    /**
     * <b>회복된 지연은 좀비가 아니다.</b> 이 검사가 없으면 {@code maxPongGap()}(역대 최대)로
     * 판정해도 위 두 테스트가 다 통과한다 — 좀비 테스트는 pong을 아예 안 주고,
     * 양성 대조는 최대값이 임계 아래에 머물기 때문이다. <b>둘 다 자를 구분하지 못한다.</b>
     *
     * <p><b>"회복 뒤 조용한가"를 재려면 먼저 한 번은 알려야 한다.</b> 임계를 넘긴
     * 구간을 만들면 그 구간에서 알림이 한 번 나오는 것이 정상이다 — 그것까지 0으로
     * 두면 어떤 자를 써도 못 지나간다. 그래서 <b>회복 이후에 더 늘어나는가</b>를 본다:
     * {@code sincePong()}이면 1에서 멈추고, {@code maxPongGap()}이면 회복된 뒤에도
     * 매 주기 참이라 계속 불어난다.
     */
    @Test
    void pong이_한_번_늦었다가_회복되면_더_알리지_않는다() throws Exception {
        AtomicInteger reports = new AtomicInteger();
        Handshake handshake = new Handshake("s", Duration.ofMillis(100), Duration.ofMillis(240));
        Duration maxGap;

        try (Heartbeat beat = Heartbeat.start(() -> { }, handshake, new HeartbeatListener() {
            @Override public void onSendFailed(PingFailure.Cause cause) { }
            @Override public void onPongTimeout(Duration gap) { reports.incrementAndGet(); }
        })) {
            // 임계(200ms)를 넘겨 알림이 실제로 한 번 나올 때까지 기다린다 —
            // 여기서 역대 최대가 올라간다. 고정 수면으로 대신하면 스케줄러가 밀린
            // 실행에서 알림이 안 나온 채 지나가 아래 판별이 성립하지 않는다.
            awaitUntil(() -> reports.get() >= 1, Duration.ofSeconds(2));
            beat.recordPong();
            // 그 뒤로는 제때 준다. 회복됐으므로 더 알리면 안 된다.
            long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
            while (System.nanoTime() < deadline) {
                beat.recordPong();
                Thread.sleep(20);
            }
            // try 블록 안에서 꺼내 둔다. beat는 여기 스코프에만 있다.
            maxGap = beat.maxPongGap();
        }

        assertThat(maxGap)
                .as("역대 최대는 실제로 임계를 넘겨 놨다 — 자를 구분하는 상황이 만들어졌다")
                .isGreaterThanOrEqualTo(handshake.pongThreshold());
        assertThat(reports.get())
                .as("maxPongGap(역대 최대)으로 판정하면 회복된 뒤에도 매 주기 알린다. "
                        + "회복된 지연이 살아 있는 세션을 끊는다")
                .isOne();
    }

    /**
     * <b>회복된 뒤 다시 끊기면 다시 알린다.</b> 알림 가드를 하트비트 생애 1회로
     * 잠그면 두 번째 좀비 구간이 통째로 안 알려진다 — 재연결이 아직 안 붙은 채
     * 같은 하트비트가 살아 있는 동안 수집이 죽어도 아무도 모른다.
     *
     * <p>바로 위 테스트와 부등호가 반대다. 저쪽은 "회복했으면 그만 알려라",
     * 이쪽은 "다시 끊겼으면 또 알려라"다. <b>한쪽만 고치지 마라</b> — 가드를
     * 영구히 잠그면 위가 통과하고 여기가 깨지며, 가드를 아예 없애면 반대가 된다.
     */
    @Test
    void 회복된_뒤_다시_끊기면_다시_알린다() throws Exception {
        AtomicInteger reports = new AtomicInteger();
        Handshake handshake = new Handshake("s", Duration.ofMillis(100), Duration.ofMillis(240));

        try (Heartbeat beat = Heartbeat.start(() -> { }, handshake, new HeartbeatListener() {
            @Override public void onSendFailed(PingFailure.Cause cause) { }
            @Override public void onPongTimeout(Duration gap) { reports.incrementAndGet(); }
        })) {
            awaitUntil(() -> reports.get() >= 1, Duration.ofSeconds(2));
            beat.recordPong();                  // 좀비 구간 하나가 끝났다
            // 그 뒤로 pong을 다시 안 준다. 두 번째 구간이다.
            awaitUntil(() -> reports.get() >= 2, Duration.ofSeconds(2));
        }

        assertThat(reports.get())
                .as("두 번째 좀비 구간을 안 알리면 알림 가드가 사고를 가린다")
                .isGreaterThanOrEqualTo(2);
    }

    /**
     * <b>좀비 알림 콜백도 보호 안에서 불려야 한다.</b> 그것이 던지면 예외가 스케줄
     * 작업 밖으로 나가 {@code scheduleAtFixedRate}가 조용히 멈추고, 그 순간부터
     * ping이 영영 안 나간다 — 2026-08-01 그 자체다.
     *
     * <p>짝은 바로 위의 {@code 실패_콜백이_던져도_스케줄러가_멈추지_않는다}(송신 쪽)다.
     * <b>좀비 쪽에는 그 짝이 없었고, 보호를 벗겨도 전체가 초록이었다</b>(CP2 실측).
     * 태스크 9가 이 콜백에 재연결 요청을 얹고 그것은 {@code RejectedExecutionException}을
     * 낼 수 있다 — 보호가 사라지면 그 한 번에 하트비트가 통째로 멈춘다.
     *
     * <p>알림은 좀비 구간당 한 번이라 두 번째 알림을 받으려면 사이에 pong이 들어와
     * 재무장해야 한다. 그래서 <b>다음 구간의 알림이 오는 것</b>이 곧 "콜백이 던진
     * 뒤에도 스케줄러가 살아 있다"의 증거다.
     */
    @Test
    void 좀비_콜백이_던져도_스케줄러가_멈추지_않는다() throws Exception {
        AtomicInteger reports = new AtomicInteger();
        Handshake handshake = new Handshake("s", Duration.ofMillis(100), Duration.ofMillis(240));
        long callbackFailures;

        try (Heartbeat beat = Heartbeat.start(() -> { }, handshake, new HeartbeatListener() {
            @Override public void onSendFailed(PingFailure.Cause cause) { }
            @Override public void onPongTimeout(Duration gap) {
                reports.incrementAndGet();
                throw new IllegalStateException("좀비 콜백이 터졌다");
            }
        })) {
            awaitUntil(() -> reports.get() >= 1, Duration.ofSeconds(2));
            beat.recordPong();                  // 첫 구간을 닫고 재무장한다
            // <b>단언할 그 값을 기다린다.</b> reports는 콜백이 던지기 "전"에 오르고
            // callbackFailures는 notifyQuietly가 "잡은 뒤"에 오른다 — reports로
            // 기다리면 두 번째 예외가 아직 안 세어진 채로 읽는다. 그 창은 좁아서
            // 빠른 기계에서는 안 보이고 느린 기계에서만 빨간불이 된다(실제로 그랬다).
            awaitUntil(() -> beat.callbackFailureCount() >= 2, Duration.ofSeconds(2));
            // beat는 이 스코프에만 있다. 닫힌 뒤에 읽으면 값이 아니라 참조가 없다.
            callbackFailures = beat.callbackFailureCount();
        }

        assertThat(reports.get())
                .as("콜백이 던진 예외가 밖으로 나가면 스케줄러가 죽어 1에서 멈춘다")
                .isGreaterThanOrEqualTo(2);
        assertThat(callbackFailures)
                .as("삼켰으면 세야 한다 — 안 세면 조용한 실패를 우리가 만드는 것이다")
                .isGreaterThanOrEqualTo(2);
    }

    /**
     * <b>송신이 죽은 주기에는 pong을 판정하지 않는다.</b> ping이 안 나가면 pong이
     * 안 오는 것은 당연해서, 같은 절단 하나가 두 사유로 두 번 알려진다. 재연결은
     * 사유에 따라 재시도 여부가 갈리므로(MISUSE는 재연결 대상이 아니다) 사유가
     * 겹치는 순간 무엇을 보고 결정해야 하는지가 사라진다.
     *
     * <p>이 갈래를 지워도 나머지 테스트는 전부 초록이다(2026-08-08 실측) —
     * 송신이 실패하는 다른 테스트들은 좀비 알림을 안 보기 때문이다.
     */
    @Test
    void 송신이_실패하는_동안에는_좀비로도_알리지_않는다() throws Exception {
        AtomicInteger sendFailed = new AtomicInteger();
        AtomicInteger pongTimeouts = new AtomicInteger();
        Handshake handshake = new Handshake("s", Duration.ofMillis(100), Duration.ofMillis(240));
        PingSender dead = () -> {
            throw new PingFailure(PingFailure.Cause.CONNECTION_DEAD, new IOException("소켓이 죽었다"));
        };

        long failures;
        try (Heartbeat beat = Heartbeat.start(dead, handshake, new HeartbeatListener() {
            @Override public void onSendFailed(PingFailure.Cause cause) { sendFailed.incrementAndGet(); }
            @Override public void onPongTimeout(Duration gap) { pongTimeouts.incrementAndGet(); }
        })) {
            // 송신 주기 80ms짜리 다섯 번이면 pong 임계 200ms를 훌쩍 넘긴다.
            // 이 대기가 곧 양성 대조다 — 여기까지 왔다면 판정이 돌 조건은 갖춰졌다.
            //
            // 알림이 아니라 <b>카운터</b>로 센다. 알림은 실패 구간당 한 번이라 1에서
            // 멈추고, 그러면 "주기를 몇 번 돌았나"를 못 잰다.
            awaitUntil(() -> beat.sendFailureCount() >= 5, Duration.ofSeconds(2));
            failures = beat.sendFailureCount();
        }

        assertThat(failures)
                .as("송신 실패가 임계를 넘도록 쌓이지 않았다 — 아래 단언은 아무것도 안 잰 것이다")
                .isGreaterThanOrEqualTo(5);
        assertThat(sendFailed.get())
                .as("실패 구간 하나에 알림은 한 번이다")
                .isOne();
        assertThat(pongTimeouts.get())
                .as("같은 절단을 송신 실패와 좀비로 두 번 알리면 재연결이 사유를 못 고른다")
                .isZero();
    }

    /**
     * <b>우리가 닫은 것을 연결이 죽었다고 알리지 않는다.</b>
     *
     * <p>{@code close()}의 {@code shutdownNow()}는 <b>돌고 있는 주기 작업을
     * 인터럽트한다.</b> 그러면 송신이 {@code InterruptedException}으로 깨지고,
     * 그것은 {@code IllegalStateException}이 아니라서 {@code CONNECTION_DEAD}로
     * 분류된다 — <b>우리 정리가 스스로 절단 신호를 만든다.</b>
     *
     * <p>드문 경합이 아니다. 송신이 {@code get(5s)}에 오래 매달리는 때가 곧
     * <b>연결이 이미 병든 때</b>이고, 우리가 하트비트를 닫는 것도 정확히 그때다.
     * 태스크 9가 이 신호를 재연결 요청으로 잇는 순간 <b>모든 정리가 재연결을
     * 낳고</b>, 그 요청은 이미 도는 루프에 밀려 {@code pendingReason}에 앉았다가
     * <b>새로 세운 멀쩡한 세션을 헐어낸다.</b>
     *
     * <p>가짜 송신자가 인터럽트를 {@code PingFailure(CONNECTION_DEAD)}로 바꾸는
     * 것은 {@code EngineIoSocket.sendPing()}이 하는 일 그대로다 — 거기서도
     * {@code get()}의 인터럽트가 {@code classify()}를 지나 같은 값이 된다.
     */
    @Test
    void 우리가_닫는_중에는_연결_죽음을_알리지_않는다() throws Exception {
        List<PingFailure.Cause> reported = new CopyOnWriteArrayList<>();
        CountDownLatch sending = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        AtomicReference<Thread> pingThread = new AtomicReference<>();
        Handshake handshake = new Handshake("s", Duration.ofMillis(100), Duration.ofMillis(240));

        PingSender blocking = () -> {
            pingThread.set(Thread.currentThread());
            sending.countDown();
            try {
                Thread.sleep(5_000);           // 실물의 get(5s)와 같은 자리다
            } catch (InterruptedException e) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
                throw new PingFailure(PingFailure.Cause.CONNECTION_DEAD, e);
            }
        };

        Heartbeat beat = Heartbeat.start(blocking, handshake, onSendFailed(reported::add));
        assertThat(sending.await(2, TimeUnit.SECONDS))
                .as("송신 중이 아니면 close()의 인터럽트가 아무 데도 안 닿아 이 검사는 아무것도 안 본다")
                .isTrue();

        beat.close();

        // 양성 대조. 인터럽트가 실제로 송신을 깨뜨렸어야 "그런데도 안 알렸다"가 말이 된다.
        assertThat(interrupted.await(2, TimeUnit.SECONDS))
                .as("인터럽트가 송신에 안 닿았다면 알릴 거리가 애초에 없었던 것이다")
                .isTrue();

        // <b>부정 단언에는 기다릴 값이 없다. 그러면 알릴 수 있는 쪽을 끝낸다.</b>
        // 알림은 오직 이 스케줄러 스레드에서만 나오므로, 그것이 죽은 뒤의
        // "안 왔다"는 창이 아니라 사실이다. 고정 수면으로 대신하면 창이 좁아질 뿐
        // 없어지지 않고, 느린 기계에서만 빨간불이 되는 단언이 된다.
        awaitUntil(() -> !pingThread.get().isAlive(), Duration.ofSeconds(2));
        assertThat(pingThread.get().isAlive())
                .as("알림이 나올 수 있는 스레드가 아직 살아 있으면 아래는 '아직 안 왔다'를 잰 것이다")
                .isFalse();

        assertThat(reported)
                .as("우리가 닫았을 뿐인데 연결이 죽었다고 알리면, 태스크 9에서 "
                        + "깨끗한 정리마다 재연결이 걸린다")
                .isEmpty();
    }

    /**
     * 송신 실패만 보는 리스너. <b>pong 판정은 일부러 버린다</b> — 위 테스트들은
     * 가짜 서버가 pong을 끊는 구간을 일부러 만들거나(T11) 소켓을 닫아 두므로
     * 좀비 알림이 같이 나오는데, 그것들이 재는 것은 ping 쪽이다.
     */
    private static HeartbeatListener onSendFailed(Consumer<PingFailure.Cause> handler) {
        return new HeartbeatListener() {
            @Override public void onSendFailed(PingFailure.Cause cause) { handler.accept(cause); }
            @Override public void onPongTimeout(Duration gap) { }
        };
    }

    private Handshake openWithSlowHandler(Duration delay) throws Exception {
        return openWithSlowHandler(delay, new AtomicLong());
    }

    private Handshake openWithSlowHandler(Duration delay, AtomicLong chatsHandled) throws Exception {
        return openWith((frame, handshake) -> {
            if (frame.type() != EngineIoFrame.Type.EVENT) {
                return;
            }
            // CHAT만 센다. 핸드셰이크 직후의 connected SYSTEM 이벤트도 EVENT
            // 프레임이라, 그것까지 세면 홍수를 통째로 지워도 카운터가 1이 되어
            // 양성 대조가 자동으로 참이 된다 — 실측으로 그렇게 됐다(2026-08-06).
            // 자는 것은 EVENT 전부다. T12와 같은 부하여야 비교가 성립한다.
            if (frame.payload().startsWith("[\"CHAT\"")) {
                chatsHandled.incrementAndGet();
            }
            if (!delay.isZero()) {
                sleepQuietly(delay);            // 느린 핸들러
            }
        });
    }

    /**
     * EVENT 프레임을 받았을 때 할 일을 테스트가 직접 정한다.
     * T12는 여기에 "ping 전송"을 얹어 2026-08-01 아키텍처를 재현한다.
     *
     * <p><b>준비되기 전의 프레임은 콜백에 넘기지 않는다.</b> JDK는 open()이
     * join()에서 아직 안 돌아온 사이에 이미 프레임을 배달한다
     * (WebSocketImpl.newInstance → signalOpen → processText). 그때 콜백이
     * {@code socket} 필드를 역참조하면 그 필드는 아직 null이고, NPE가 onText
     * 밖으로 나가면 <b>JDK가 소켓을 abort한다</b>(1006). 그 다음 emitChat이
     * 죽은 세션에 쓰면서 가짜 서버가 터진다. 평소엔 콜백의 1600ms 수면이 가려
     * 주지만, CPU가 포화되면 테스트 스레드가 그 사이에 대입을 못 끝내 드러난다.
     *
     * <p>핸드셰이크도 같은 결함이라 필드가 아니라 <b>인자로 준다</b> —
     * T12가 쓰던 참조는 openWith가 돌아온 뒤에야 채워졌다.
     *
     * <p>여기서 기다리면 안 된다. 그 배달이 {@code join()}이 돌아오기 전에
     * 같은 스레드에서 일어나므로, 콜백이 준비를 기다리면 교착이다.
     */
    private Handshake openWith(BiConsumer<EngineIoFrame, Handshake> onEvent) throws Exception {
        CountDownLatch open = new CountDownLatch(1);
        AtomicReference<Handshake> ref = new AtomicReference<>();
        AtomicBoolean ready = new AtomicBoolean();

        socket = EngineIoSocket.open(uri(), frame -> {
            if (frame.type() == EngineIoFrame.Type.OPEN) {
                ref.set(Handshake.parse(frame.payload()));
                open.countDown();
                return;
            }
            if (!ready.get()) {
                return;
            }
            if (frame.type() == EngineIoFrame.Type.PONG && heartbeat != null) {
                heartbeat.recordPong();
                return;
            }
            onEvent.accept(frame, ref.get());
        }, () -> { }, BUDGET, NO_ABORT);
        ready.set(true);

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

    /**
     * 카운터가 하한에 닿을 때까지 시한을 두고 기다린 뒤 <b>실제 값</b>을 돌려준다.
     *
     * <p><b>단언을 무르게 하는 것이 아니라 스케줄링 지연만 흡수한다.</b> 홍수가
     * 아예 안 오면 시한을 다 쓰고 0을 돌려주므로 판정은 그대로 선다 — 값을 만들어
     * 주지 않고 기다리기만 한다. 시한을 2초로 잡은 근거는 {@code MIN_CHATS_T1}에 있다.
     *
     * <p>부르는 쪽이 ping 건수를 <b>이 대기 전에</b> 고정해야 한다. 안 그러면
     * 늘어난 시간만큼 ping이 더 나가 건수 단언이 헐거워진다.
     */
    private static long awaitAtLeast(AtomicLong counter, long floor) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (counter.get() < floor && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        return counter.get();
    }

    /**
     * 단언할 그것이 나타날 때까지만 기다린다. <b>고정 수면 대신 쓴다</b> —
     * 수면은 창을 좁힐 뿐 없애지 못하고, 안 나타나면 시한을 다 쓰고 그대로
     * 빨간불이 되므로 판별은 무뎌지지 않는다.
     */
    private static void awaitUntil(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
    }

    private static void sleepQuietly(Duration d) {
        try { Thread.sleep(d.toMillis()); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private URI uri() {
        return URI.create("ws://localhost:" + port + "/socket.io/?auth=T&EIO=3&transport=websocket");
    }
}
