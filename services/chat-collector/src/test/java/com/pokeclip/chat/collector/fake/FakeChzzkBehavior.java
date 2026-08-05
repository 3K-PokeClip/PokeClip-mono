package com.pokeclip.chat.collector.fake;

import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 테스트마다 가짜 서버의 동작을 바꾸는 스위치. 필드를 공개로 두는 이유는
 * 테스트 전용이기 때문이다 — 세터를 만들면 읽을 코드만 늘어난다.
 */
public class FakeChzzkBehavior {

    /** 실측 비율(60000/25000 = 2.4)을 유지하면서 값만 줄인다. */
    public volatile long pingIntervalMillis = 1000;
    public volatile long pingTimeoutMillis = 2400;

    /** false면 connected를 안 보낸다 — T13(수립 시한)이 쓴다. */
    public volatile boolean sendConnected = true;
    /**
     * false면 구독 REST가 200을 주고도 subscribed를 안 쏜다 — ⑤의 시한을 본다.
     * sendConnected와 대칭이다. 실제로 있는 상태다(연결은 살아 있는데 채팅만 안 온다).
     */
    public volatile boolean sendSubscribed = true;
    /** false면 ping에 답하지 않는다 — T11(pong 공백)이 쓴다. */
    public volatile boolean answerPong = true;
    /** false면 ping이 없어도 안 끊는다 — 간격만 재고 싶을 때. */
    public volatile boolean disconnectWhenPingMissing = true;
    /** 세션 발급 REST가 돌려줄 상태. 401이면 T9(만료 토큰). */
    public volatile int authStatus = 200;

    private final List<String> received = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> handshakeQuery = new AtomicReference<>("");
    private final AtomicReference<WebSocketSession> session = new AtomicReference<>();
    private final Object sendLock = new Object();

    /**
     * 서버가 2를 실제로 받은 시각. <b>ping 간격의 지상 진실은 여기다.</b>
     * 클라이언트의 자기 신고(Heartbeat.maxPingGap)로 재면 "지표 자체가 틀린 경우"를
     * 못 잡는다. T1·T2·T12가 전부 이 값을 쓴다 — 같은 자로 재야 T12가 의미를 갖는다.
     */
    private final AtomicLong lastPingNanos = new AtomicLong();
    private final AtomicLong maxPingGapNanos = new AtomicLong();

    /**
     * 세션 발급 호출 횟수. 재시도 루프가 생기면 여기서 걸린다 —
     * 재연결은 POK-86이고 이번 카드는 실패하면 사유만 남기고 멈춘다.
     */
    private final AtomicInteger authCalls = new AtomicInteger();

    public List<String> receivedFrames() { return List.copyOf(received); }
    public String handshakeQuery() { return handshakeQuery.get(); }
    public int authCallCount() { return authCalls.get(); }

    /** 마지막 ping 이후 흘러가는 중인 공백도 센다. 안 그러면 완전히 멈춘 상태가 0으로 보인다. */
    public Duration maxPingGapObserved() {
        long running = System.nanoTime() - lastPingNanos.get();
        return Duration.ofNanos(Math.max(maxPingGapNanos.get(), running));
    }

    /** 테스트가 채팅을 쏟는 입구. 이중 인코딩된 채로 나간다. */
    public void emitChat(String innerJson) {
        send("42[\"CHAT\",\"" + escape(innerJson) + "\"]");
    }

    public void emitSystem(String innerJson) {
        send("42[\"SYSTEM\",\"" + escape(innerJson) + "\"]");
    }

    /**
     * <b>가짜 서버가 WS로 내보내는 모든 것이 여기를 지난다.</b> 핸드셰이크·40·
     * pong·이벤트 전부. 스프링 세션은 동시 전송에 안전하지 않아서, 한 갈래라도
     * 락 밖에 두면 채팅 홍수와 pong이 겹치는 순간 서버가 스스로 무너진다
     * (IllegalStateException: ... state [TEXT_PARTIAL_WRITING]).
     *
     * <p>그 순간이 정확히 T1·T2가 겨냥한 상황이라, 무너지면 클라이언트를
     * 검증하는 대신 가짜 서버의 버그를 보게 된다.
     * EngineIoSocket이 송신을 한 지점으로 모은 것과 같은 이유다.
     *
     * <p>락을 세션 객체가 아니라 전용 sendLock에 건다. 세션은 재연결마다
     * 바뀌는데 락 대상이 같이 바뀌면 경계가 흔들린다.
     */
    public void send(String frame) {
        WebSocketSession s = session.get();
        if (s == null || !s.isOpen()) {
            throw new IllegalStateException("가짜 서버에 열린 세션이 없다. 붙기 전에 보냈다");
        }
        synchronized (sendLock) {
            try {
                s.sendMessage(new TextMessage(frame));
            } catch (Exception e) {
                throw new IllegalStateException("가짜 서버 전송 실패", e);
            }
        }
    }

    /**
     * 서버가 조용히 끊는다. ping이 제때 오는 정상 상태에서도 끊어야 하는
     * 테스트가 있어 리퍼와 별도로 둔다 — POK-86(강제 절단 후 재연결)이 그대로 쓴다.
     */
    public void closeSession() {
        WebSocketSession s = session.get();
        if (s == null || !s.isOpen()) {
            throw new IllegalStateException("가짜 서버에 열린 세션이 없다. 끊을 것이 없다");
        }
        try {
            s.close(org.springframework.web.socket.CloseStatus.NORMAL);
        } catch (Exception e) {
            throw new IllegalStateException("가짜 서버 절단 실패", e);
        }
    }

    void markPingReceived() {
        long now = System.nanoTime();
        long gap = now - lastPingNanos.getAndSet(now);
        maxPingGapNanos.accumulateAndGet(gap, Math::max);
    }

    void startPingClock() {
        lastPingNanos.set(System.nanoTime());
        maxPingGapNanos.set(0);
    }

    void record(String frame) { received.add(frame); }
    void countAuthCall() { authCalls.incrementAndGet(); }
    void rememberQuery(String query) { handshakeQuery.set(query == null ? "" : query); }
    void remember(WebSocketSession s) { session.set(s); }

    public void reset() {
        received.clear();
        authCalls.set(0);
        handshakeQuery.set("");
        pingIntervalMillis = 1000;
        pingTimeoutMillis = 2400;
        sendConnected = true;
        sendSubscribed = true;
        answerPong = true;
        disconnectWhenPingMissing = true;
        authStatus = 200;
    }

    private static String escape(String json) {
        return json.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
