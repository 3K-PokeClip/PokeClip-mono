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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

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
        heartbeat = Heartbeat.start(socket, handshake, () -> { });

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
        }, () -> { });
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

    private static void sleepQuietly(Duration d) {
        try { Thread.sleep(d.toMillis()); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private URI uri() {
        return URI.create("ws://localhost:" + port + "/socket.io/?auth=T&EIO=3&transport=websocket");
    }
}
