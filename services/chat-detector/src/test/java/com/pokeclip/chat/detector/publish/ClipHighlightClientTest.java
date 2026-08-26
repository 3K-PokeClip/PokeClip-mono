package com.pokeclip.chat.detector.publish;

import com.pokeclip.chat.detector.config.ClipClientProperties;
import com.pokeclip.chat.detector.config.InternalApiProperties;
import com.pokeclip.chat.detector.publish.ClipHighlightClient.PublishResult;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ClipHighlightClientTest {

    private HttpServer server;
    private final List<String> bodies = new ArrayList<>();
    private final List<String> paths = new ArrayList<>();
    private final AtomicInteger calls = new AtomicInteger();
    private volatile int status = 201;

    private static final HighlightCard CARD = new HighlightCard(
            "s1", "detect-42", 12_500L, 10_000L, 15_000L, "{\"ratio\":4.0}");

    @BeforeEach
    void 가짜_clip을_띄운다() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/internal/broadcasts", exchange -> {
            calls.incrementAndGet();
            paths.add(exchange.getRequestURI().getPath());
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
    }

    @AfterEach
    void 내린다() {
        server.stop(0);
    }

    private ClipHighlightClient client(int maxAttempts) {
        return new ClipHighlightClient(RestClient.builder(),
                new ClipClientProperties("http://localhost:" + server.getAddress().getPort(), maxAttempts),
                new InternalApiProperties("secret-token"));
    }

    @Test
    void 새_카드는_201이다() {
        assertThat(client(3).publish(CARD)).isEqualTo(PublishResult.CREATED);
        assertThat(paths).containsExactly("/internal/broadcasts/s1/highlights");
    }

    /** 200은 clip이 이미 갖고 있다는 뜻이다. 실패가 아니다 — 재시도하면 안 된다. */
    @Test
    void 이미_있는_카드는_200이고_재시도하지_않는다() {
        status = 200;

        assertThat(client(3).publish(CARD)).isEqualTo(PublishResult.ALREADY_EXISTS);
        assertThat(calls).hasValue(1);
    }

    /** 본문 칸 이름이 clip의 HighlightRequest와 정확히 같아야 한다. 틀리면 400이다. */
    @Test
    void 계약대로_본문을_만든다() {
        client(3).publish(CARD);

        assertThat(bodies).singleElement().satisfies(body -> assertThat(body)
                .contains("\"eventId\":\"detect-42\"")
                .contains("\"source\":\"auto\"")
                .contains("\"streamTimestampMs\":12500")
                .contains("\"window\":{\"startMs\":10000,\"endMs\":15000}")
                .contains("\"evidence\":{\"ratio\":4.0}"));
    }

    /**
     * 🔴 400은 「이 요청이 잘못됐다」이다. 재시도하면 같은 본문이라 <b>영영 성공하지 못한다</b>
     * (clip 쪽이 실측하고 문서에 적어 둔 자리다).
     */
    @Test
    void 요청이_잘못됐으면_재시도하지_않는다() {
        status = 400;

        assertThat(client(3).publish(CARD)).isEqualTo(PublishResult.REJECTED);
        assertThat(calls).hasValue(1);
    }

    /** 500은 clip 쪽 사정이라 다시 하면 될 수 있다. */
    @Test
    void clip이_500이면_정해진_횟수만큼_재시도한다() {
        status = 500;

        assertThat(client(3).publish(CARD)).isEqualTo(PublishResult.FAILED);
        assertThat(calls).hasValue(3);
    }

    /**
     * 🔴 <b>404는 다른 4xx와 다르다 — 다시 보내면 답이 바뀐다</b>(봇 리뷰 1판, codex).
     *
     * <p>clip 은 그 방송을 아직 모를 때 404를 준다. 그런데 방송 시작 알림을 <b>수집기와 clip 이
     * 각자 다른 큐에서</b> 받으므로, clip 이 늦으면 <b>채팅은 이미 쌓이는데 clip 에는 방송 행이
     * 없다.</b> 그 사이 급증 카드를 영구 실패로 접으면 그 하이라이트는 영영 사라진다.
     *
     * <p><b>근거는 clip 코드 자신이다</b> — {@code JumpCardService.record} 가
     * 「FK 위반은 500이 되고, <b>판별기는 404를 받아야 재시도 상한을 센다</b>」라고 적어 뒀다.
     * clip 이 재시도를 전제로 설계한 자리다.
     *
     * <p><b>즉시 재시도는 안 한다</b> — 이 안에서 몇 밀리초 만에 다시 보내 봐야 clip 이 그 사이
     * 방송을 만들 리 없다. 바퀴를 넘겨 다시 시도하도록 부르는 쪽에 알린다.
     */
    @Test
    void clip이_방송을_아직_모르면_다음_바퀴로_미룬다() {
        status = 404;

        assertThat(client(3).publish(CARD)).isEqualTo(PublishResult.BROADCAST_NOT_FOUND);
        assertThat(calls).as("즉시 재시도는 낭비다").hasValue(1);
    }

    /** 401도 재시도로 안 풀린다 — 토큰이 틀린 것이라 같은 헤더로 몇 번을 보내도 같다. */
    @Test
    void 인증이_거부되면_재시도하지_않는다() {
        status = 401;

        assertThat(client(3).publish(CARD)).isEqualTo(PublishResult.REJECTED);
        assertThat(calls).hasValue(1);
    }

    @Test
    void clip이_안_뜨면_실패다() {
        server.stop(0);

        assertThat(client(2).publish(CARD)).isEqualTo(PublishResult.FAILED);
    }
}
