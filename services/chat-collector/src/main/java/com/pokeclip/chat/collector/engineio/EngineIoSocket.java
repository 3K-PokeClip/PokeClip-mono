package com.pokeclip.chat.collector.engineio;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * java.net.http.WebSocket 하나를 감싼다. <b>WS로 나가는 모든 것이 이 클래스를 지난다.</b>
 *
 * <p>지금 나가는 것은 ping 하나뿐이라 실제 경합은 없지만, 하나 더 늘리는 변경이
 * 들어오면 여기서 걸린다 — 이전 send가 끝나기 전에 다음 send를 부르면
 * IllegalStateException이다.
 */
public final class EngineIoSocket implements AutoCloseable {

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

    public static EngineIoSocket open(URI uri, Consumer<EngineIoFrame> onFrame, Runnable onClosed) {
        HttpClient httpClient = HttpClient.newHttpClient();
        try {
            return new EngineIoSocket(httpClient, connect(httpClient, uri, onFrame, onClosed));
        } catch (RuntimeException e) {
            // 붙는 데 실패해도 스레드는 이미 떴다. 여기서 안 닫으면 실패할 때마다 쌓인다.
            httpClient.shutdownNow();
            throw e;
        }
    }

    private static WebSocket connect(HttpClient httpClient, URI uri,
                                     Consumer<EngineIoFrame> onFrame, Runnable onClosed) {
        return httpClient.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5))
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
                })
                .join();
    }

    /** 우리가 WS로 내보내는 유일한 프레임. */
    public void sendPing() {
        synchronized (sendLock) {
            try {
                webSocket.sendText(EngineIoFrame.PING_TEXT, true)
                        .get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                // URI를 메시지에 담지 않는다 — 쿼리에 auth 토큰이 들어 있다.
                throw new IllegalStateException("ping 송신 실패", e);
            }
        }
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
