package com.pokeclip.chat.collector.fake;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 가짜 서버 자체를 검증한다. 이게 없으면 뒤의 테스트 8개가 "우리가 만든
 * 서버가 우리 기대대로 답했다"만 확인하는 셈이 된다.
 */
@FakeChzzkTest
class FakeChzzkServerTest {

    @LocalServerPort int port;
    @Autowired FakeChzzkBehavior behavior;

    @Test
    void 붙으면_핸드셰이크와_서버_CONNECT와_connected가_순서대로_온다() throws Exception {
        List<String> frames = new CopyOnWriteArrayList<>();
        CountDownLatch got = new CountDownLatch(3);

        connect(frames, got);

        assertThat(got.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(frames.get(0)).startsWith("0{");
        assertThat(frames.get(1)).isEqualTo("40");
        assertThat(frames.get(2)).startsWith("42[\"SYSTEM\"");
    }

    /** 실측대로 이중 인코딩이다. 안쪽이 문자열이 아니면 함정 5를 재현하지 못한다. */
    @Test
    void 이벤트_본문은_이중_인코딩이다() throws Exception {
        List<String> frames = new CopyOnWriteArrayList<>();
        CountDownLatch got = new CountDownLatch(3);
        connect(frames, got);
        assertThat(got.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(frames.get(2)).contains("\\\"type\\\":\\\"connected\\\"");
    }

    /** 이 동작이 없으면 함정 3 테스트는 간격만 재는 것이고 사고를 재현하지 못한다. */
    @Test
    void ping이_생존_시한을_넘겨_안_오면_오류_프레임_없이_끊는다() throws Exception {
        behavior.pingIntervalMillis = 200;
        behavior.pingTimeoutMillis = 480;   // 생존 시한 680ms

        List<String> frames = new CopyOnWriteArrayList<>();
        CountDownLatch closed = new CountDownLatch(1);

        HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(uri(), new WebSocket.Listener() {
                    @Override public void onOpen(WebSocket ws) { ws.request(1); }
                    @Override public CompletionStage<?> onText(WebSocket ws, CharSequence d, boolean last) {
                        frames.add(d.toString());
                        ws.request(1);
                        return null;
                    }
                    @Override public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
                        closed.countDown();
                        return null;
                    }
                })
                .join();

        assertThat(closed.await(5, TimeUnit.SECONDS))
                .as("ping을 한 번도 안 보냈는데 서버가 안 끊었다. 사고를 재현할 수 없다")
                .isTrue();
        assertThat(frames).noneMatch(f -> f.startsWith("1"));   // CLOSE 프레임도 없이 조용히
    }

    @Test
    void 접속_쿼리를_그대로_기록한다() throws Exception {
        CountDownLatch got = new CountDownLatch(1);
        connect(new CopyOnWriteArrayList<>(), got);
        assertThat(got.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(behavior.handshakeQuery()).contains("EIO=3");
    }

    private URI uri() {
        return URI.create("ws://localhost:" + port + "/socket.io/?EIO=3&transport=websocket&auth=T");
    }

    private void connect(List<String> frames, CountDownLatch got) {
        HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(uri(), new WebSocket.Listener() {
                    @Override public void onOpen(WebSocket ws) { ws.request(1); }
                    @Override public CompletionStage<?> onText(WebSocket ws, CharSequence d, boolean last) {
                        frames.add(d.toString());
                        got.countDown();
                        ws.request(1);
                        return null;
                    }
                })
                .join();
    }
}
