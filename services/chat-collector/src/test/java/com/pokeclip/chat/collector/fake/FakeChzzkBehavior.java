package com.pokeclip.chat.collector.fake;

import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
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
    /**
     * 0이 아니면 세션 url의 포트를 이 값으로 바꾼다.
     *
     * <p><b>②(WS 접속)를 결정적으로 실패시키는 유일한 손잡이다.</b> 발급은 200으로
     * 성공하는데 그 url로는 못 붙는 상태를 만든다 — 죽은 포트를 주면 루프백이
     * 즉시 거부하므로 접속 거부 분류가 매번 같은 값으로 나온다.
     */
    public volatile int sessionUrlPort = 0;
    /**
     * 세션 발급 REST가 응답 전에 붙들고 있는 시간.
     *
     * <p><b>연결은 받아 놓고 답을 안 주는 상태</b>를 만든다. 거부(401)와 다르다 —
     * 거부는 즉시 사유가 나오지만 이쪽은 read-timeout이 없으면 영영 매달린다.
     * 그러면 부팅이 안 끝나고, 같은 클라이언트를 쓰는 종료 시 구독 반납도 안 끝난다.
     */
    public volatile Duration authDelay = Duration.ZERO;
    /**
     * true면 구독 REST가 <b>200을 돌려주기 전에</b> 소켓을 끊고, 클라이언트가 그
     * 절단을 처리했다는 증거(구독 반납 도착)를 기다린 뒤 응답한다.
     *
     * <p><b>"수립 직후 절단"을 우연이 아니라 결정적으로 재현하는 장치다.</b>
     * 프레임은 순서대로 오므로 subscribed는 절단보다 먼저 도착했고, 반납이 왔다는
     * 것은 클라이언트의 절단 처리가 이미 끝났다는 뜻이다. 그 뒤에야 부팅 스레드가
     * 구독 HTTP 응답을 받고 수립 마무리(스케줄러 기동·상태 전이)로 들어간다.
     * 그냥 끊기만 하면 두 스레드의 경합이라 순서가 실행마다 달라진다.
     *
     * <p>실제로 있는 상태다 — 토큰 revoke나 연결 상한 초과가 이 창에 떨어진다.
     */
    public volatile boolean closeAfterSubscribed = false;
    /**
     * subscribed를 쏜 <b>직후, 구독 REST가 200을 돌려주기 전에</b> 도는 훅.
     *
     * <p><b>"수립이 아직 안 끝난 세션에 프레임을 밀어 넣는" 지점을 결정적으로
     * 고정한다.</b> 이 훅이 도는 동안 클라이언트의 수립 스레드는 ④의 HTTP 응답을
     * 기다리며 막혀 있고, 그동안 소켓은 이미 열려 있어 우리가 쏜 프레임은 곧장
     * 수신 콜백으로 간다. 훅이 그 프레임이 도착한 것까지 확인하고 돌아가면,
     * 수립 마무리(하트비트 기동·상태 전이·절단 구간 닫기)는 <b>반드시 그 뒤</b>다.
     *
     * <p>그냥 쏘기만 하면 두 스레드의 경합이라 순서가 실행마다 갈린다
     * (CP5가 같은 상황을 10회 중 3회만 재현했다).
     */
    public volatile Runnable onSubscribeBeforeResponse = () -> { };
    /**
     * 구독 반납 REST가 돌려줄 상태. 200이 아니면 반납이 실패한다.
     * <b>이 스위치가 없으면 반납 실패 갈래를 밟는 테스트가 0개다</b> —
     * 예외가 종료 훅 밖으로 나가면 소켓 닫기가 통째로 건너뛰어진다는
     * try/catch의 존재 이유가 무검사로 남는다.
     */
    public volatile int unsubscribeStatus = 200;
    /**
     * 구독 반납 REST가 <b>도착을 센 뒤</b> 응답 전에 붙들고 있는 시간.
     *
     * <p><b>뒷정리 스레드를 원하는 지점에 세워 두는 장치다.</b> 반납은 실서버에서
     * 약 1초 걸리는 왕복이라(CLAUDE.md 실측), 그 사이에 다음 세션이 시작되는 창이
     * 실제로 열린다. 지연이 없으면 그 창이 로컬에서 1ms라 경합이 영영 안 재현되고,
     * 그러면 "앞 세션 정리가 새 세션을 지운다"는 결함이 초록 아래 그대로 남는다.
     *
     * <p>도착을 먼저 세므로 {@code unsubscribeCallCount()}가 1이 된 시점이
     * 곧 <b>뒷정리 스레드가 왕복에 갇힌 시점</b>이다 — 테스트가 그 시점을 노린다.
     */
    public volatile Duration unsubscribeDelay = Duration.ZERO;

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

    /**
     * WS 접속이 성립한 시각. <b>T13이 "실제로 기다렸는가"를 재는 자다.</b>
     * 테스트가 자기 시계로 재면 ①②가 느린 경우와 ③에서 기다린 경우를 못 가른다.
     */
    private final AtomicLong connectionEstablishedNanos = new AtomicLong();
    private final AtomicInteger connections = new AtomicInteger();

    private final AtomicLong authRequestNanos = new AtomicLong();
    private final AtomicInteger unsubscribeCalls = new AtomicInteger();
    private final AtomicInteger closedSessions = new AtomicInteger();
    private final AtomicBoolean unsubscribeSawOpenSession = new AtomicBoolean();

    public List<String> receivedFrames() { return List.copyOf(received); }
    public String handshakeQuery() { return handshakeQuery.get(); }
    public int authCallCount() { return authCalls.get(); }

    /**
     * 세션 발급 요청이 <b>서버에 도착한 뒤</b> 흐른 시간.
     *
     * <p>시한 검사는 이 자를 쓴다. 테스트가 직접 잰 시간에는 스프링 부팅이 통째로
     * 섞여 들어와, 시한이 걸렸는지를 가르려면 여유를 몇 초씩 둬야 하고 그러면
     * 자가 무뎌진다. 요청 도착 시각부터 재면 부팅 시간이 빠진다.
     */
    public Duration sinceAuthRequest() {
        return Duration.ofNanos(System.nanoTime() - authRequestNanos.get());
    }

    /** ②가 끝난 뒤 흐른 시간. 접속 전이면 0이다. */
    public Duration sinceConnectionEstablished() {
        long at = connectionEstablishedNanos.get();
        return at == 0 ? Duration.ZERO : Duration.ofNanos(System.nanoTime() - at);
    }

    /**
     * WS 접속이 성립한 횟수. <b>"멈추라고 한 뒤에 새로 붙었나"를 재는 유일한 자다.</b>
     *
     * <p>{@link #closedSessionCount()}로는 못 잰다 — 열고 곧바로 닫아도 둘 다 늘어
     * "안 열었다"와 "열었다가 닫았다"가 같은 값이 된다. 세션 참조도 못 쓴다 —
     * 끊기면 비워진다.
     */
    public int connectionCount() { return connections.get(); }

    void markConnectionEstablished() {
        connectionEstablishedNanos.set(System.nanoTime());
        connections.incrementAndGet();
    }

    /** 종료 시 구독 반납이 실제로 왔는지. 안 오면 세션을 우리 손으로 안 닫은 것이다. */
    public int unsubscribeCallCount() { return unsubscribeCalls.get(); }

    /**
     * 반납 REST가 도착했을 때 WS 세션이 열려 있었는가.
     * <b>순서를 실제로 보는 유일한 자다</b> — 건수만 세면 소켓을 먼저 닫도록
     * 뒤집어도 그대로 초록이다(실측으로 확인됨).
     */
    public boolean unsubscribeSawOpenSession() { return unsubscribeSawOpenSession.get(); }

    /** 서버가 관측한 WS 종료 횟수. 클라이언트가 끊는다고 알렸는지를 여기서 본다. */
    public int closedSessionCount() { return closedSessions.get(); }

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

    /** 클라이언트의 절단 처리가 반납까지 도달하기를 기다리는 시한. */
    private static final Duration AWAIT_UNSUBSCRIBE_TIMEOUT = Duration.ofSeconds(5);

    /**
     * 클라이언트가 절단을 처리해 <b>구독 반납까지 보냈음</b>을 확인한다.
     * {@link #closeAfterSubscribed}가 기대는 장벽이다.
     *
     * <p><b>시한을 다 쓰면 터진다.</b> 조용히 돌아가면 재현 순서가 다시 우연에
     * 맡겨지고, 그 테스트는 초록이어도 아무것도 안 지킨다.
     */
    void awaitUnsubscribeCall() {
        long deadline = System.nanoTime() + AWAIT_UNSUBSCRIBE_TIMEOUT.toNanos();
        while (unsubscribeCalls.get() == 0 && System.nanoTime() < deadline) {
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        if (unsubscribeCalls.get() == 0) {
            throw new IllegalStateException("끊었는데 클라이언트의 구독 반납이 "
                    + AWAIT_UNSUBSCRIBE_TIMEOUT.toSeconds() + "초 안에 안 왔다. 재현 순서를 보장할 수 없다");
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
    void countAuthCall() {
        authRequestNanos.set(System.nanoTime());
        authCalls.incrementAndGet();
    }
    void rememberQuery(String query) { handshakeQuery.set(query == null ? "" : query); }
    void remember(WebSocketSession s) { session.set(s); }

    /**
     * 끊긴 세션을 현재 세션 자리에서 치운다. <b>같은 세션일 때만</b> 치운다 —
     * 앞 접속이 늦게 끊기면서 이미 들어온 새 접속을 지우면, 뒤 테스트가
     * "열린 세션이 없다"로 간헐 실패한다. 실제로 그렇게 났다.
     */
    void forget(WebSocketSession s) {
        closedSessions.incrementAndGet();
        session.compareAndSet(s, null);
    }

    void countUnsubscribeCall() {
        WebSocketSession s = session.get();
        unsubscribeSawOpenSession.set(s != null && s.isOpen());
        unsubscribeCalls.incrementAndGet();
    }

    /**
     * <b>앞 접속이 끝난 것을 서버가 관측할 때까지 기다린 뒤 지운다.</b>
     * 클라이언트의 close()가 보내는 종료 프레임 "1"은 서버에 비동기로 도착하는데,
     * 안 기다리고 지우면 늦게 도착한 "1"이 다음 테스트의 receivedFrames()에 남는다.
     * 같은 창이 ping "2"에도 열려 있어 건수 단언이 부풀 수도 있다.
     *
     * <p>프레임은 종료보다 먼저 같은 스트림으로 오므로, 서버가 종료를 관측했다면
     * 그 앞의 프레임은 이미 record()를 지났다.
     *
     * <p>안 닫고 끝내는 테스트는 여기서 시한을 통째로 쓴다. 그래서
     * FakeChzzkServerTest가 자기 소켓을 tearDown에서 끊는다.
     */
    public void reset() {
        awaitSessionClosed();
        received.clear();
        authCalls.set(0);
        unsubscribeCalls.set(0);
        closedSessions.set(0);
        unsubscribeSawOpenSession.set(false);
        // 안 지우면 앞 테스트의 접속 시각이 남아 다음 테스트가 큰 값을 본다.
        connectionEstablishedNanos.set(0);
        connections.set(0);
        handshakeQuery.set("");
        // 테스트 클래스들이 스프링 컨텍스트 하나를 공유하므로 이 객체도 하나뿐이다.
        // 앞 클래스가 쓰던 세션을 남겨 두면 뒤 클래스가 그것으로 보내려다 실패한다.
        session.set(null);
        pingIntervalMillis = 1000;
        pingTimeoutMillis = 2400;
        sendConnected = true;
        sendSubscribed = true;
        answerPong = true;
        disconnectWhenPingMissing = true;
        authStatus = 200;
        sessionUrlPort = 0;
        authDelay = Duration.ZERO;
        closeAfterSubscribed = false;
        onSubscribeBeforeResponse = () -> { };
        unsubscribeStatus = 200;
        unsubscribeDelay = Duration.ZERO;
    }

    /** 앞 세션이 닫히기를 기다리는 시한. 실측 최대 23ms에 200배 여유다. */
    private static final Duration AWAIT_CLOSED_TIMEOUT = Duration.ofSeconds(5);

    /**
     * <b>시한을 다 쓰면 터진다.</b> 조용히 돌아가면 그대로 {@code received.clear()}로
     * 넘어가고, 그 순간 CP6 결함 #1의 오염이 <b>신호 없이</b> 되살아난다 —
     * 늦게 도착한 종료 프레임 "1"이 다음 테스트의 {@code receivedFrames()}에 남고,
     * 같은 창이 ping "2"에도 열려 있어 건수 단언이 부풀 수 있다.
     *
     * <p>그 결함은 25% 간헐 실패로 나타나 원인을 찾는 데 오래 걸렸다.
     * <b>삼키기만 하고 안 세면 조용한 실패를 우리가 만드는 것이다.</b>
     *
     * <p>실측(2026-08-06, 전체 1회): 호출 66회 · 시한 초과 0회 · 최대 23ms · 합계 579ms.
     * 지금은 안 걸린다. 걸리는 날은 안 닫고 끝낸 테스트가 생겼거나
     * {@code forget()}의 {@code compareAndSet}이 실패한 날이고, 둘 다 알아야 한다.
     */
    private void awaitSessionClosed() {
        long deadline = System.nanoTime() + AWAIT_CLOSED_TIMEOUT.toNanos();
        while (session.get() != null && System.nanoTime() < deadline) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        if (session.get() != null) {
            throw new IllegalStateException("앞 세션이 " + AWAIT_CLOSED_TIMEOUT.toSeconds()
                    + "초 안에 안 닫혔다. 닫지 않고 끝낸 테스트가 있으면 그 프레임이 다음 테스트로 넘어간다");
        }
    }

    private static String escape(String json) {
        return json.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
