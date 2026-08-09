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

    public Established open(Duration deadline) {
        long endAt = System.nanoTime() + deadline.toNanos();

        String url = client.createSession();                        // ① AUTH

        AtomicReference<Handshake> handshake = new AtomicReference<>();
        AtomicReference<String> sessionKey = new AtomicReference<>();
        CountDownLatch connected = new CountDownLatch(1);
        CountDownLatch subscribed = new CountDownLatch(1);

        EngineIoSocket socket;
        try {                                                       // ② CONNECT
            socket = EngineIoSocket.open(
                    SessionUrl.toWebSocketUri(url),
                    frame -> handle(frame, handshake, sessionKey, connected, subscribed),
                    () -> closedSink.run());
        } catch (Exception e) {
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

        await(connected, endAt, EstablishStage.WAITING_CONNECTED);  // ③

        // Handshake.parse는 깨진 본문에 null을 준다(예외를 던지면 수신이 멈춘다).
        // 타이밍을 못 읽으면 하트비트를 돌릴 수 없으므로 여기서 끊는다.
        if (handshake.get() == null) {
            throw new SessionEstablishException(EstablishStage.CONNECT,
                    StopReason.CONNECT_FAILED, "핸드셰이크를 읽지 못했다");
        }

        currentSessionKey.set(sessionKey.get());
        client.subscribeChat(sessionKey.get());                     // ④ SUBSCRIBE
        await(subscribed, endAt, EstablishStage.WAITING_SUBSCRIBED);// ⑤

        return new Established(handshake.get(), socket);
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

    private void await(CountDownLatch latch, long endAt, EstablishStage stage) {
        long remaining = endAt - System.nanoTime();
        try {
            if (remaining <= 0 || !latch.await(remaining, TimeUnit.NANOSECONDS)) {
                throw new SessionEstablishException(stage, StopReason.ESTABLISH_TIMEOUT,
                        "stage=" + stage);
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
