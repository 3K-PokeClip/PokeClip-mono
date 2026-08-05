package com.pokeclip.chat.collector.chzzk;

import com.pokeclip.chat.collector.StopReason;
import com.pokeclip.chat.collector.engineio.EngineIoFrame;
import com.pokeclip.chat.collector.engineio.EngineIoSocket;
import com.pokeclip.chat.collector.engineio.Handshake;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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

    public ChatSession(ChzzkSessionClient client) {
        this.client = client;
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
            throw new SessionEstablishException(EstablishStage.CONNECT,
                    StopReason.CONNECT_FAILED, "cause=" + e.getClass().getSimpleName());
        }
        current.set(socket);

        await(connected, endAt, EstablishStage.WAITING_CONNECTED);  // ③

        // Handshake.parse는 깨진 본문에 null을 준다(예외를 던지면 수신이 멈춘다).
        // 타이밍을 못 읽으면 하트비트를 돌릴 수 없으므로 여기서 끊는다.
        if (handshake.get() == null) {
            throw new SessionEstablishException(EstablishStage.CONNECT,
                    StopReason.CONNECT_FAILED, "핸드셰이크를 읽지 못했다");
        }

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
                frameSink.accept(frame);
            }
            default -> frameSink.accept(frame);
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
