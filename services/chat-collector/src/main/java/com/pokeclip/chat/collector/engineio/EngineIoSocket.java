package com.pokeclip.chat.collector.engineio;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * java.net.http.WebSocket 하나를 감싼다. <b>WS로 나가는 모든 것이 이 클래스를 지난다.</b>
 *
 * <p>지금 나가는 것은 ping 하나뿐이라 실제 경합은 없지만, 하나 더 늘리는 변경이
 * 들어오면 여기서 걸린다 — 이전 send가 끝나기 전에 다음 send를 부르면
 * IllegalStateException이다.
 */
public final class EngineIoSocket implements PingSender, AutoCloseable {

    /**
     * <b>참조를 들고 있어야 닫을 수 있다.</b> 셀렉터 스레드와 워커 풀을 소유하는데,
     * 소켓마다 새로 만들면서 안 닫으면 그 스레드가 통째로 남는다. 지금은 프로세스당
     * 한 번이라 영향이 작지만 {@code ChatSession.open()}은 재진입 가능하도록
     * 설계돼 있고, POK-86이 강제 절단 뒤에 그것을 다시 부른다.
     */
    private final HttpClient httpClient;

    private final WebSocket webSocket;
    private final Object sendLock = new Object();

    private EngineIoSocket(HttpClient httpClient, WebSocket webSocket) {
        this.httpClient = httpClient;
        this.webSocket = webSocket;
    }

    /** 접속 시한의 상한. 남은 수립 예산이 더 짧으면 그쪽을 쓴다. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    /**
     * 접속을 이만큼씩 끊어 기다리며 매번 중단 신호를 본다.
     * {@code ChatSession.await()}의 조각과 같은 값이다 — 종료가 최대 이만큼 늦어진다.
     */
    private static final Duration ABORT_CHECK_SLICE = Duration.ofMillis(100);

    /**
     * @param budget 남은 수립 예산. 접속만 시한을 따로 갖고 있으면 <b>수립 전체 시한을
     *               넘겨서까지</b> 붙으려 든다
     * @param abort  우리가 멈추는 중인가. <b>조각마다 본다.</b> 예전에는
     *               {@code join()}이라 인터럽트에도 중단 신호에도 반응하지 않았고,
     *               상대가 TCP만 받고 답이 없으면 접속 시한을 통째로 쓰는 동안
     *               {@code stop()}이 먼저 지나갔다 — 그 뒤 늦게 성립한 소켓은
     *               아무도 안 닫는다
     */
    public static EngineIoSocket open(URI uri, Consumer<EngineIoFrame> onFrame, Runnable onClosed,
                                      Duration budget, BooleanSupplier abort) {
        HttpClient httpClient = HttpClient.newHttpClient();
        CompletableFuture<WebSocket> pending =
                connect(httpClient, uri, onFrame, onClosed, budget);
        try {
            return new EngineIoSocket(httpClient, awaitConnected(pending, budget, abort));
        } catch (RuntimeException e) {
            // 붙는 데 실패해도 스레드는 이미 떴다. 여기서 안 닫으면 실패할 때마다 쌓인다.
            abandon(pending, httpClient);
            throw e;
        }
    }

    /**
     * 붙는 중이던 접속을 버린다. <b>늦게 성립하는 소켓까지 닫는다</b> — 중단 신호로
     * 빠져나온 뒤에 접속이 완료되면 그 소켓은 아무도 참조하지 않아 영영 안 닫히고,
     * 서버 쪽 자리는 죽은 전송을 알아챌 때까지(실측 10초~4분 42초) 남는다.
     * 연결 상한이 3개라 그것이 곧 다음 재시도를 막는다.
     */
    private static void abandon(CompletableFuture<WebSocket> pending, HttpClient httpClient) {
        pending.whenComplete((webSocket, failure) -> {
            if (webSocket != null) webSocket.abort();
        });
        pending.cancel(true);
        httpClient.shutdownNow();
    }

    /**
     * 조각으로 나눠 기다리며 매번 중단 신호를 본다.
     *
     * <p>실패는 {@code CompletionException}으로 감싼다 — {@code join()}이 주던 모양이라
     * 부르는 쪽의 원인 분류({@code getCause()})가 그대로 산다.
     */
    private static WebSocket awaitConnected(CompletableFuture<WebSocket> pending,
                                            Duration budget, BooleanSupplier abort) {
        long endAt = System.nanoTime() + budget.toNanos();
        while (true) {
            if (abort.getAsBoolean()) {
                throw new CompletionException(new CancellationException("중단 신호"));
            }
            long remaining = endAt - System.nanoTime();
            if (remaining <= 0) {
                throw new CompletionException(new TimeoutException("수립 예산 초과"));
            }
            try {
                return pending.get(Math.min(remaining, ABORT_CHECK_SLICE.toNanos()),
                        TimeUnit.NANOSECONDS);
            } catch (TimeoutException slice) {
                // 조각이 끝났을 뿐이다. 다시 중단 신호를 본다.
            } catch (ExecutionException e) {
                throw new CompletionException(e.getCause());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CompletionException(e);
            }
        }
    }

    private static CompletableFuture<WebSocket> connect(HttpClient httpClient, URI uri,
                                                        Consumer<EngineIoFrame> onFrame,
                                                        Runnable onClosed, Duration budget) {
        return httpClient.newWebSocketBuilder()
                // 예산이 더 짧으면 예산을 쓴다. 0 이하는 Builder가 거부하므로 부르는 쪽이 막는다.
                .connectTimeout(budget.compareTo(CONNECT_TIMEOUT) < 0 ? budget : CONNECT_TIMEOUT)
                .buildAsync(uri, new WebSocket.Listener() {

                    // 한 프레임이 여러 콜백으로 쪼개져 올 수 있다. last=true까지 모은다.
                    private final StringBuilder buffer = new StringBuilder();

                    @Override
                    public void onOpen(WebSocket socket) {
                        // 기본 구현이 하는 일이다. 오버라이드하고 안 부르면
                        // 연결은 성립하는데 메시지가 한 건도 안 온다 — 오류도 없다.
                        socket.request(1);
                    }

                    @Override
                    public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
                        buffer.append(data);
                        if (last) {
                            String raw = buffer.toString();
                            buffer.setLength(0);
                            onFrame.accept(EngineIoFrame.parse(raw));
                        }
                        socket.request(1);
                        return null;
                    }

                    @Override
                    public CompletionStage<?> onClose(WebSocket socket, int code, String reason) {
                        onClosed.run();
                        return null;
                    }

                    @Override
                    public void onError(WebSocket socket, Throwable error) {
                        onClosed.run();
                    }
                });
    }

    /**
     * 우리가 WS로 내보내는 유일한 프레임.
     *
     * <p><b>실패 원인을 가른다.</b> {@code sendText}는 이전 송신이 끝나기 전에 부르면
     * {@code IllegalStateException}을 내는데, 그건 연결이 죽은 것이 아니라
     * <b>우리가 잘못 쓴 것</b>이다. 묶어서 넘기면 재연결이 그 버그를 덮는다.
     */
    @Override
    public void sendPing() {
        synchronized (sendLock) {
            try {
                webSocket.sendText(EngineIoFrame.PING_TEXT, true)
                        .get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                // URI를 메시지에 담지 않는다 — 쿼리에 auth 토큰이 들어 있다.
                throw new PingFailure(classify(e), e);
            }
        }
    }

    /**
     * <b>JDK는 동시 송신 위반을 던지지 않고 실패한 future로 준다</b> —
     * {@code WebSocketImpl.sendText}가 {@code MinimalFuture.failedFuture(new
     * IllegalStateException("Send pending"))}를 돌려준다. 그래서 {@code get()}은
     * {@code ExecutionException}을 던지고, <b>cause를 안 풀면 그 갈래가 영영 안 잡힌다.</b>
     * 그러면 {@code MISUSE}도 {@code SEND_MISUSE}도 죽은 코드가 되고, 우리 버그가
     * 자동 복구에 덮이는 상태가 그대로 남는다.
     *
     * <p>동기 throw 경로도 함께 본다 — JDK 구현이 바뀌면 그리로 온다.
     *
     * <p>패키지 전용으로 열어 두는 이유는 <b>분류 자체를 지나는 테스트</b>를 두기
     * 위해서다. 실제 동시 송신은 {@code sendLock}이 막아 테스트에서 만들 수 없다.
     */
    static PingFailure.Cause classify(Exception e) {
        Throwable actual = (e instanceof ExecutionException && e.getCause() != null)
                ? e.getCause() : e;
        // 모르는 예외는 연결 죽음으로 본다. 채팅 유실이 유일한 치명 실패라,
        // 못 가른 것을 붙잡고 멈추는 쪽이 더 나쁘다. 대신 예외 타입을 메시지에 남긴다.
        return actual instanceof IllegalStateException
                ? PingFailure.Cause.MISUSE : PingFailure.Cause.CONNECTION_DEAD;
    }

    /**
     * <b>끊는다고 알리고</b> 닫는다. Engine.IO close 프레임 → WS close 핸드셰이크
     * 순서다. 그냥 abort하면 서버는 죽은 전송을 스스로 알아챌 때까지 세션을
     * 붙들고 있고, 실측에서 그 시간이 10초와 4분 42초로 갈렸다.
     *
     * <p>어느 단계가 실패해도 abort로 확실히 끝낸다 — 종료가 예외로 멈추면
     * 뒤따르는 정리가 통째로 건너뛰어진다.
     */
    @Override
    public void close() {
        synchronized (sendLock) {
            try {
                webSocket.sendText(EngineIoFrame.CLOSE_TEXT, true).get(1, TimeUnit.SECONDS);
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "").get(1, TimeUnit.SECONDS);
            } catch (Exception e) {
                // 이미 죽은 소켓이다. 알릴 상대가 없으니 그냥 끝낸다.
            }
            webSocket.abort();
            // close()가 아니라 shutdownNow()다. close()는 남은 작업이 끝날 때까지
            // 무기한 막는데, 여기는 종료 경로라 한 번 멈추면 뒤따르는 정리가
            // 통째로 건너뛰어진다.
            httpClient.shutdownNow();
        }
    }
}
