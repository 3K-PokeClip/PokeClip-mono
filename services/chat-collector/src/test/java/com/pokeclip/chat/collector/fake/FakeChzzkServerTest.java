package com.pokeclip.chat.collector.fake;

import com.pokeclip.chat.collector.engineio.EngineIoSocket;
import org.junit.jupiter.api.AfterEach;
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

    /** 수립 예산. 이 파일은 접속 자체를 보므로 넉넉히 준다. */
    private static final java.time.Duration BUDGET = java.time.Duration.ofSeconds(5);

    /** 중단 신호가 없는 호출. 중단은 EstablishCutCleanupTest가 본다. */
    private static final java.util.function.BooleanSupplier NO_ABORT = () -> false;

    @LocalServerPort int port;
    @Autowired FakeChzzkBehavior behavior;

    /**
     * behavior는 스프링 싱글턴이고 @FakeChzzkTest가 전부 같은 컨텍스트를 쓰므로
     * <b>테스트 클래스 사이에서도 공유된다.</b> 여기서 200/480으로 줄여 놓은 채
     * 두면, 값을 직접 세팅하지 않는 테스트가 하나 생기는 날 핸드셰이크가 그 값을
     * 광고해 파생 임계가 통째로 쪼그라들고 원인이 어디에도 안 보인다.
     */
    private final List<WebSocket> clients = new CopyOnWriteArrayList<>();

    /**
     * 여기서 연 날소켓을 먼저 끊는다. reset()이 "서버가 종료를 관측했나"를
     * 기다리도록 바뀌었으므로, 안 끊고 두면 매 테스트가 그 시한을 통째로 쓴다.
     */
    @AfterEach
    void tearDown() {
        clients.forEach(WebSocket::abort);
        clients.clear();
        behavior.reset();
    }

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

        clients.add(HttpClient.newHttpClient().newWebSocketBuilder()
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
                .join());

        assertThat(closed.await(5, TimeUnit.SECONDS))
                .as("ping을 한 번도 안 보냈는데 서버가 안 끊었다. 사고를 재현할 수 없다")
                .isTrue();
        // 양성 대조가 먼저다. 프레임을 한 건도 못 받았다면 noneMatch는 훑을 것이
        // 없어 그냥 참이 되고, "CLOSE 없이 조용히 끊었다"가 아니라 "아무것도 못
        // 봤다"를 통과로 읽게 된다.
        assertThat(frames)
                .as("핸드셰이크조차 안 왔다면 아래 단언은 아무것도 검사하지 않는다")
                .isNotEmpty();
        assertThat(frames).noneMatch(f -> f.startsWith("1"));   // CLOSE 프레임도 없이 조용히
    }

    /**
     * 종료 프레임 "1"은 클라이언트가 close()에서 보내고 <b>서버가 그것을
     * record()하는 것은 비동기다.</b> reset()이 그것을 안 기다리면 늦게 도착한
     * "1"이 다음 테스트의 receivedFrames()에 남는다.
     *
     * <p>같은 창이 ping "2"에도 열려 있다 — "1"은 마지막 "2" 뒤에 같은 TCP
     * 스트림으로 나가므로 "1"이 넘어오는 창은 "2"가 넘어오는 창을 포함한다.
     * 그래서 이건 단순 플레이키가 아니라 HeartbeatTest의 건수 단언이 부풀려진
     * 값으로 헛통과할 수 있는 구멍이다.
     *
     * <p><b>반복을 100번 돌리는 이유가 있다.</b> 25%는 창이 200ms 넘게 열려 있던
     * {@code EngineIoSocketTest}의 값이고 이 테스트의 값이 아니다 — 여기는
     * {@code reset()} 직후 {@code open()} 몇 ms가 창의 전부다. 20회로 돌렸을 때
     * {@code reset()}의 {@code awaitSessionClosed()}를 지운 상태에서 <b>19회 중
     * 7회(37%)</b>만 빨간불이었다. 회귀가 들어와도 5번 중 3번은 CI가 초록이라는 뜻이다.
     *
     * <p>시행당 누출 확률이 2%대라 반복만 늘리면 검출률이 곧바로 올라간다.
     * 100회로 올려 같은 회귀를 다시 재니 <b>10회 중 9회(90%)</b>였다. 대가는 몇 초다
     * (이 클래스 전체가 33초). 100%는 아니므로 <b>한 번 초록이 났다고 회귀가 없다는
     * 뜻은 아니다.</b> 터진 시행 번호는 1·1·1·2·5·11·18·18·32로 앞쪽에 몰린다.
     */
    @Test
    void 앞_세션의_종료_프레임이_reset을_넘어오지_않는다() {
        for (int i = 0; i < 100; i++) {
            EngineIoSocket socket = EngineIoSocket.open(uri(), frame -> { }, () -> { }, BUDGET, NO_ABORT);

            assertThat(behavior.receivedFrames())
                    .as("앞 시행이 닫은 소켓의 프레임이 reset을 넘어왔다 (시행 %d)", i)
                    .isEmpty();

            socket.close();
            behavior.reset();
        }
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
        clients.add(HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(uri(), new WebSocket.Listener() {
                    @Override public void onOpen(WebSocket ws) { ws.request(1); }
                    @Override public CompletionStage<?> onText(WebSocket ws, CharSequence d, boolean last) {
                        frames.add(d.toString());
                        got.countDown();
                        ws.request(1);
                        return null;
                    }
                })
                .join());
    }
}
