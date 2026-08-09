package com.pokeclip.chat.collector.chzzk;

import com.pokeclip.chat.collector.StopReason;
import com.pokeclip.chat.collector.engineio.EngineIoFrame;
import com.pokeclip.chat.collector.engineio.EngineIoSocket;
import com.pokeclip.chat.collector.engineio.Handshake;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * 수립 절차 ①~⑤를 한 덩어리로 돈다. <b>재진입 가능하다</b> —
 * POK-86이 강제 절단 뒤에 이 메서드를 통째로 다시 부른다.
 * 세션 URL은 약 30초면 만료되고 재사용이 안 되므로 절차를 처음부터 다시 탄다.
 *
 * <p><b>전체에 시한을 건다.</b> 시한이 없으면 connected가 안 올 때 무한 대기가
 * 되는데, 그때는 ping이 아직 시작 전이라 실패 조건이 하나도 안 걸리고
 * health도 DOWN이 아니고 로그도 안 나온다.
 */
public class ChatSession implements AutoCloseable {

    /** 수립 대기를 이만큼씩 끊어 중단 신호를 본다. 종료가 최대 이만큼 늦어진다. */
    private static final Duration ABORT_CHECK_SLICE = Duration.ofMillis(100);

    public record Established(Handshake handshake, EngineIoSocket socket) { }

    private final ChzzkSessionClient client;
    private final AtomicReference<EngineIoSocket> current = new AtomicReference<>();

    private volatile Consumer<EngineIoFrame> frameSink = frame -> { };
    private volatile Runnable closedSink = () -> { };

    /** 삼킨 싱크 예외의 수. 안 세면 수신은 사는데 처리가 통째로 죽은 것을 못 본다. */
    private final AtomicLong sinkFailures = new AtomicLong();

    public ChatSession(ChzzkSessionClient client) {
        this.client = client;
    }

    /** 종료할 때 구독 반납에 쓴다. 수립이 끝나야 채워진다. */
    private final AtomicReference<String> currentSessionKey = new AtomicReference<>();

    /** 삼킨 싱크 예외의 수. 삼키기만 하고 안 세면 조용한 실패를 우리가 만드는 것이다. */
    public long sinkFailureCount() { return sinkFailures.get(); }

    /** 종료할 때 구독을 반납하려면 이 값이 필요하다. 수립 전이면 null이다. */
    public String sessionKey() { return currentSessionKey.get(); }

    /**
     * 구독 반납의 결말. <b>{@code SKIPPED}와 {@code FAILED}를 한 값으로 묶으면
     * "반납할 세션 키가 없었다"(수립 실패)와 "반납을 보냈는데 실패했다"가 같은
     * 로그 한 줄이 되어 아무도 못 가른다.</b>
     */
    public enum Release {
        RETURNED("returned"),
        FAILED("failed"),
        SKIPPED("skipped");

        private final String label;

        Release(String label) { this.label = label; }

        @Override
        public String toString() { return label; }
    }

    /**
     * 구독을 반납하고 소켓을 닫는다. 반납이 먼저다 — 소켓을 먼저 닫으면
     * 서버가 세션을 정리하는 중이라 반납이 무의미해질 수 있다.
     *
     * @return 반납의 결말. <b>어느 결말이든 소켓은 닫는다</b>
     */
    public Release releaseAndClose() {
        String key = currentSessionKey.getAndSet(null);
        Release result;
        if (key == null || key.isBlank()) {
            result = Release.SKIPPED;
        } else {
            result = client.unsubscribeChatQuietly(key) ? Release.RETURNED : Release.FAILED;
        }
        close();
        return result;
    }

    public void onFrame(Consumer<EngineIoFrame> sink) { this.frameSink = sink; }
    public void onClosed(Runnable sink) { this.closedSink = sink; }

    /**
     * @param abort 우리가 멈추는 중인가. 시한과 별개로 <b>대기를 즉시 끝내는</b>
     *              신호다 — 없으면 종료가 시한만큼 매달리거나, 짧게 기다리고
     *              뒷정리 중인 스레드를 인터럽트하는 급사 경로를 만들어야 한다
     */
    public Established open(Duration deadline, BooleanSupplier abort) {
        long endAt = System.nanoTime() + deadline.toNanos();

        abortIfStopping(abort, EstablishStage.AUTH);
        String url = client.createSession();                        // ① AUTH

        AtomicReference<Handshake> handshake = new AtomicReference<>();
        AtomicReference<String> sessionKey = new AtomicReference<>();
        CountDownLatch connected = new CountDownLatch(1);
        CountDownLatch subscribed = new CountDownLatch(1);

        abortIfStopping(abort, EstablishStage.CONNECT);
        // 예산 계산을 try 밖에 둔다. 안에 두면 예산 만료가 아래 분류를 지나
        // CONNECT_FAILED로 둔갑한다.
        Duration connectBudget = remaining(endAt, EstablishStage.CONNECT);
        EngineIoSocket socket;
        try {                                                       // ② CONNECT
            socket = EngineIoSocket.open(
                    SessionUrl.toWebSocketUri(url),
                    frame -> handle(frame, handshake, sessionKey, connected, subscribed),
                    () -> closedSink.run(),
                    connectBudget, abort);
        } catch (Exception e) {
            // <b>중단이 먼저다.</b> 중단으로 빠져나온 접속은 아래 분류가 보면
            // 취소 예외라 CONNECT_FAILED로 떨어지고, 그러면 로그에서 "우리가 멈춘 것"과
            // "붙는 데 실패한 것"이 같은 줄이 된다 — 재연결이 반복 실패할 때 첫 단서다.
            abortIfStopping(abort, EstablishStage.CONNECT);
            // 진단용 구분이다. 재시도 여부는 이걸로 갈리지 않는다 —
            // 재시도 불가 사유는 AUTH의 401/403과 REVOKED뿐이고 둘 다 여기가 아니다.
            // 그래도 가르는 이유는, 재연결이 반복 실패할 때 로그가 한 줄이면
            // 시한 초과인지 DNS인지 TLS인지를 사람이 못 가르기 때문이다.
            //
            // 알고 남긴 구멍: CONNECT_REFUSED만 테스트가 지킨다(죽은 포트로 결정적으로
            // 만들 수 있다). CONNECT_TIMEOUT은 라우팅되지 않는 주소가 있어야 재현되는데
            // 느리고 환경을 타서 안 붙였다 — 그 분기를 지워도 아무 테스트도 안 깨진다.
            // 다시 조사하지 않도록 여기 적어 둔다.
            Throwable root = e.getCause() == null ? e : e.getCause();
            StopReason reason;
            if (root instanceof java.net.http.HttpTimeoutException
                    || root instanceof java.util.concurrent.TimeoutException) {
                reason = StopReason.CONNECT_TIMEOUT;
            } else if (root instanceof java.io.IOException) {
                reason = StopReason.CONNECT_REFUSED;
            } else {
                reason = StopReason.CONNECT_FAILED;
            }
            throw new SessionEstablishException(EstablishStage.CONNECT, reason,
                    "cause=" + root.getClass().getSimpleName());
        }
        current.set(socket);

        await(connected, endAt, abort, EstablishStage.WAITING_CONNECTED);   // ③

        // Handshake.parse는 깨진 본문에 null을 준다(예외를 던지면 수신이 멈춘다).
        // 타이밍을 못 읽으면 하트비트를 돌릴 수 없으므로 여기서 끊는다.
        if (handshake.get() == null) {
            throw new SessionEstablishException(EstablishStage.CONNECT,
                    StopReason.CONNECT_FAILED, "핸드셰이크를 읽지 못했다");
        }

        // 키를 세우기 전에 본다. 세운 뒤에 끊으면 구독한 적도 없는 키로 반납 REST가
        // 한 번 나간다 — 종료 경로에 없어도 되는 왕복이다.
        abortIfStopping(abort, EstablishStage.SUBSCRIBE);
        beforeSessionKey();
        currentSessionKey.set(sessionKey.get());
        client.subscribeChat(sessionKey.get());                     // ④ SUBSCRIBE
        await(subscribed, endAt, abort, EstablishStage.WAITING_SUBSCRIBED); // ⑤

        return new Established(handshake.get(), socket);
    }

    /**
     * <b>세션 키가 서기 직전. 검사가 여기에 다른 스레드를 통째로 끼워 넣는다.</b>
     * 평소에는 비어 있다.
     *
     * <p>이름을 준 이유는 {@code CollectorRunner.heartbeatListener(no)}와 같다 —
     * 이 자리에서 열리는 창에는 <b>붙잡을 I/O가 없어</b> 밖에서 끊기만 하면 두
     * 스레드의 경합이 되고 순서가 실행마다 갈린다.
     *
     * <p>고정하려는 순서는 이것이다: 절단 정리가 <b>키가 서기 전에</b> 통째로
     * 지나가면 그 정리는 반납할 키를 못 보고({@code subscription=skipped}) 가드만
     * 소모한다. 그 뒤에 ④가 만드는 구독은 <b>아무도 반납하지 않는다</b> —
     * 서버에 남아 연결 상한 3개 중 하나를 먹는다.
     * {@code CollectorRunner.releaseLate}가 막는 것이 정확히 그 상태다.
     */
    protected void beforeSessionKey() { }

    /**
     * <b>①②④ 앞에서 한 번씩 본다.</b> 셋은 REST와 WS 접속이라 <b>일단 시작하면
     * 조각으로 끊을 수 없다</b> — ①④는 접속 2초 + 읽기 5초, ②는 접속 시한 5초를
     * 통째로 쓴다. 그래서 여기서 할 수 있는 것은 <b>멈추라는 신호를 받은 뒤에 그런
     * 호출을 새로 시작하지 않는 것</b>뿐이고, 그것으로 충분하다: 이미 나간 호출은
     * 시한이 있어 반드시 돌아오고, 돌아오면 다음 단계 앞에서 여기에 걸린다.
     *
     * <p>이 검사가 없으면 {@code stop()}이 지나간 뒤에 ②가 소켓을 연다. 그 소켓은
     * 정리 가드가 이미 소모돼 아무도 안 닫고, 서버 쪽 자리는 죽은 전송을 알아챌
     * 때까지(실측 10초~4분 42초) 남는다.
     *
     * <p>사유는 {@code await()}의 중단과 같은 값이다 — 재시도 판단이 이걸로 안 갈린다.
     * 사람이 읽는 구분은 {@code detail}이 진다.
     *
     * <p><b>세 자리 중 검사가 지키는 것은 ①뿐이다.</b> 지우고 전체를 돌려 확인했다:
     * ①을 지우면 {@code 중단_신호가_이미_서_있으면_세션_발급조차_안_한다}가 단독으로
     * 깨지고, ②는 5회·④는 1회 전부 초록이다. <b>"원리적으로 필요 없다"가 아니라
     * "이 하네스에서 관측되지 않는다"다</b> — 둘은 다음 이유로 갈린다.
     *
     * <ul>
     *   <li>②를 지워도 {@code EngineIoSocket}의 조각 검사가 곧바로 걸려 접속을 버리는데,
     *       상대가 같은 JVM 루프백이라 <b>그 버리기가 TCP 핸드셰이크보다 빠르다.</b>
     *       실서버는 왕복이 있어 SYN이 이미 나간 뒤이고, 그때 서버가 접속을 성립시키면
     *       그것이 연결 상한 3개 중 하나를 먹는다. 검사를 여기 두면 SYN 자체가 안 나간다
     *   <li>④를 지워도 ⑤의 중단이 곧바로 걸리는데, <b>그 앞에 구독 REST 왕복이 통째로
     *       들어간다.</b> 로컬은 수 ms라 안 보이고 실서버는 접속 2초 + 읽기 5초까지 간다
     * </ul>
     */
    private static void abortIfStopping(BooleanSupplier abort, EstablishStage stage) {
        if (abort.getAsBoolean()) {
            throw new SessionEstablishException(stage, StopReason.ESTABLISH_TIMEOUT,
                    "stage=" + stage + " aborted");
        }
    }

    /**
     * 남은 수립 예산. <b>다 썼으면 그 자리에서 끊는다</b> — 0 이하를 접속 시한으로
     * 넘기면 {@code WebSocket.Builder}가 거부한다.
     */
    private static Duration remaining(long endAt, EstablishStage stage) {
        long left = endAt - System.nanoTime();
        if (left <= 0) {
            throw new SessionEstablishException(stage, StopReason.ESTABLISH_TIMEOUT,
                    "stage=" + stage);
        }
        return Duration.ofNanos(left);
    }

    private void handle(EngineIoFrame frame,
                        AtomicReference<Handshake> handshake,
                        AtomicReference<String> sessionKey,
                        CountDownLatch connected,
                        CountDownLatch subscribed) {
        switch (frame.type()) {
            case OPEN -> handshake.set(Handshake.parse(frame.payload()));
            // CONNECT(40)에는 답하지 않는다. auth는 핸드셰이크에서 이미 소비됐다.
            case CONNECT -> { }
            case EVENT -> {
                SystemEvent event = ChatEventDecoder.decodeSystem(frame.payload());
                if (event != null) {
                    switch (event.type()) {
                        case "connected" -> { sessionKey.set(event.sessionKey()); connected.countDown(); }
                        case "subscribed" -> subscribed.countDown();
                        default -> { }
                    }
                }
                emit(frame);
            }
            default -> emit(frame);
        }
    }

    /**
     * 싱크는 WS 수신 콜백 안에서 불린다. 예외가 밖으로 나가면 onError로 가
     * <b>그 한 건 때문에 방송 전체 수신이 멈춘다.</b> 디코더가 null을 돌려주도록
     * 방어한 것과 같은 이유로 여기도 막는다.
     *
     * <p>예외 자체는 안 남긴다 — 메시지에 본문이 딸려 올 수 있다. 종류만 센다.
     */
    private void emit(EngineIoFrame frame) {
        try {
            frameSink.accept(frame);
        } catch (RuntimeException e) {
            sinkFailures.incrementAndGet();
        }
    }

    /**
     * 래치를 기다리되 <b>조각으로 나눠 기다리며 매번 중단 신호를 본다.</b>
     *
     * <p>한 번에 시한 전체를 기다리면 멈추려는 쪽이 그만큼 매달린다 —
     * 운영 시한이 15초라 컨테이너 종료 유예를 넘기고, SIGKILL이 오면 구독 반납이
     * 통째로 안 나간다. 조각을 더 잘게 쪼개도 얻는 것이 없고(종료가 100ms 빨라질
     * 뿐이다) 깨어나는 횟수만 는다.
     *
     * <p><b>중단을 래치보다 먼저 본다.</b> 반대로 하면 "멈추는 중인데 마침 프레임이
     * 도착해서" 다음 단계로 넘어가는 길이 생기고, 그 세션은 아무도 안 닫는다.
     */
    private void await(CountDownLatch latch, long endAt, BooleanSupplier abort, EstablishStage stage) {
        try {
            while (true) {
                if (abort.getAsBoolean()) {
                    // 사유는 시한 초과와 같은 값이다. 재시도 판단이 이걸로 안 갈리고,
                    // 새 값을 만들면 재시도 분류표에 실제로는 안 오는 값이 한 줄 는다.
                    // 사람이 읽는 구분은 detail이 진다.
                    throw new SessionEstablishException(stage, StopReason.ESTABLISH_TIMEOUT,
                            "stage=" + stage + " aborted");
                }
                long remaining = endAt - System.nanoTime();
                if (remaining <= 0) {
                    throw new SessionEstablishException(stage, StopReason.ESTABLISH_TIMEOUT,
                            "stage=" + stage);
                }
                if (latch.await(Math.min(remaining, ABORT_CHECK_SLICE.toNanos()),
                        TimeUnit.NANOSECONDS)) {
                    return;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SessionEstablishException(stage, StopReason.ESTABLISH_TIMEOUT, "interrupted");
        }
    }

    @Override
    public void close() {
        EngineIoSocket socket = current.getAndSet(null);
        if (socket != null) socket.close();
    }
}
