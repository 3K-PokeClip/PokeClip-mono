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
