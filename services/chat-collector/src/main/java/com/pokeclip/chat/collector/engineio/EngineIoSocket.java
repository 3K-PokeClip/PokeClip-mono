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

    private final WebSocket webSocket;
    private final Object sendLock = new Object();

    private EngineIoSocket(WebSocket webSocket) {
        this.webSocket = webSocket;
    }

    public static EngineIoSocket open(URI uri, Consumer<EngineIoFrame> onFrame, Runnable onClosed) {
        WebSocket ws = HttpClient.newHttpClient().newWebSocketBuilder()
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

        return new EngineIoSocket(ws);
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

    @Override
    public void close() {
        webSocket.abort();
    }
}
