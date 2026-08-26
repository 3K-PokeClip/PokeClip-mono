package com.pokeclip.chat.detector.publish;

import com.pokeclip.chat.detector.config.CollectorClientProperties;
import com.pokeclip.chat.detector.config.InternalApiProperties;
import com.pokeclip.chat.detector.publish.VideoPosition.State;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
// 🔴 org.springframework.boot.web.client.RestClientBuilderConfigurer를 import하지 마라.
// Boot 3.x 좌표이고 4.1에는 그 패키지가 없다(계획 검증 F3 — 쓰지도 않는데 컴파일이 깨졌다).
// 4.1의 실제 자리는 org.springframework.boot.restclient.autoconfigure다.
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 가짜 수집 서버를 실제 HTTP로 띄운다. 모킹하지 않는 이유: 헤더가 실제로 나가는지,
 * 시한이 실제로 걸리는지는 모킹으로 잴 수 없다.
 */
class VideoPositionClientTest {

    private HttpServer server;
    private final List<String> receivedTokens = new ArrayList<>();
    private final List<String> receivedQueries = new ArrayList<>();
    private volatile int status = 200;
    private volatile String body = "";
    private volatile Duration delay = Duration.ZERO;

    @BeforeEach
    void 가짜_수집서버를_띄운다() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/internal/streams", exchange -> {
            receivedTokens.add(String.valueOf(exchange.getRequestHeaders().getFirst("X-Internal-Token")));
            receivedQueries.add(exchange.getRequestURI().getQuery());
            try {
                Thread.sleep(delay.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    @AfterEach
    void 내린다() {
        server.stop(0);
    }

    private VideoPositionClient client() {
        RestClient.Builder builder = RestClient.builder()
                .requestFactory(new org.springframework.http.client.SimpleClientHttpRequestFactory() {{
                    setConnectTimeout(1_000);
                    setReadTimeout(1_000);
                }});
        return new VideoPositionClient(builder,
                new CollectorClientProperties("http://localhost:" + server.getAddress().getPort()),
                new InternalApiProperties("secret-token"));
    }

    @Test
    void 변환된_위치를_읽는다() {
        body = """
                {"streamId":"s1","state":"converted","positionMs":12345,"segmentSeq":3,"appliedOffsetMs":3900}
                """;

        VideoPosition position = client().locate("s1", 1_787_529_601_000L);

        assertThat(position.state()).isEqualTo(State.CONVERTED);
        assertThat(position.positionMs()).isEqualTo(12_345L);
        assertThat(position.appliedOffsetMs()).isEqualTo(3_900L);
    }

    /** 내부 토큰이 없으면 401이다. 헤더 이름이 틀려도 마찬가지라 이름까지 잰다. */
    @Test
    void 내부_토큰을_헤더에_싣는다() {
        body = """
                {"state":"converted","positionMs":1,"appliedOffsetMs":0}
                """;

        client().locate("s1", 1_787_529_601_000L);

        assertThat(receivedTokens).containsExactly("secret-token");
    }

    /** 창구가 epoch ms 형식을 받는다. ISO로 보내면 형식이 갈려 실측한 함정에 걸린다. */
    @Test
    void 채팅_시각을_epoch_ms로_보낸다() {
        body = """
                {"state":"converted","positionMs":1,"appliedOffsetMs":0}
                """;

        client().locate("s1", 1_787_529_601_000L);

        assertThat(receivedQueries).singleElement()
                .isEqualTo("messageTime=1787529601000");
    }

    /**
     * 🔴 세 판정을 뭉치면 안 된다. "아직 없음"은 다시 물으면 되고 "영영 없음"은 안 온다 —
     * 뭉치면 부르는 쪽이 영영 안 올 것을 영원히 다시 묻는다(창구 쪽 설계와 같은 이유).
     */
    @Test
    void 아직_없음과_영영_없음을_가른다() {
        body = """
                {"state":"not_yet_indexed","positionMs":null,"appliedOffsetMs":3900}
                """;
        assertThat(client().locate("s1", 1L).state()).isEqualTo(State.NOT_YET_INDEXED);

        body = """
                {"state":"no_footage","positionMs":null,"appliedOffsetMs":3900}
                """;
        assertThat(client().locate("s1", 1L).state()).isEqualTo(State.NO_FOOTAGE);
    }

    /** 변환 안 된 답에 위치가 없다. 0으로 접으면 「0초 지점」이라는 그럴듯하게 틀린 답이 된다. */
    @Test
    void 변환_안_된_답의_위치는_비어_있다() {
        body = """
                {"state":"no_footage","positionMs":null,"appliedOffsetMs":3900}
                """;

        assertThat(client().locate("s1", 1L).positionMs()).isNull();
    }

    /**
     * 🔴 {@code converted}인데 위치가 없으면 <b>모름</b>이다. 0으로 접으면 「영상 0초 지점」이라는
     * <b>그럴듯하게 틀린 답</b>이 되고, 그 카드는 편집자를 방송 맨 앞으로 보낸다 — 못 쓴 카드보다
     * 나쁘다(틀린 줄 모르기 때문이다).
     *
     * <p>구현에 그 갈래와 주석이 있는데도 <b>재는 검사가 없었다</b> — 0으로 접는 주입에
     * 쉰아홉 건이 전부 초록이었다(직접 실측). 계약상 창구가 이 조합을 안 보내지만
     * ({@code positionMs}는 {@code converted}일 때만 값이 있다), 그것은 <b>저쪽 약속</b>이라
     * 우리가 안 지키는 근거가 못 된다.
     */
    @Test
    void 위치가_없는_converted는_0이_아니라_모름이다() {
        body = """
                {"state":"converted","positionMs":null,"appliedOffsetMs":3900}
                """;

        VideoPosition position = client().locate("s1", 1L);

        assertThat(position.state()).isEqualTo(State.UNAVAILABLE);
        assertThat(position.positionMs()).isNull();
    }

    /** 창구가 죽었거나 500이면 판정 불가다. NO_FOOTAGE로 접으면 멀쩡한 카드를 영영 버린다. */
    @Test
    void 창구가_500이면_모름이다() {
        status = 500;
        body = "{}";

        assertThat(client().locate("s1", 1L).state()).isEqualTo(State.UNAVAILABLE);
    }

    @Test
    void 창구가_안_뜨면_모름이다() {
        server.stop(0);

        assertThat(client().locate("s1", 1L).state()).isEqualTo(State.UNAVAILABLE);
    }

    /** 모르는 판정 이름이 오면 통과가 아니라 모름이다. 창구가 값을 늘리는 날 카드를 안 낸다. */
    @Test
    void 모르는_판정_이름은_모름이다() {
        body = """
                {"state":"something_new","positionMs":42,"appliedOffsetMs":0}
                """;

        assertThat(client().locate("s1", 1L).state()).isEqualTo(State.UNAVAILABLE);
    }

    /** 창구가 연결만 받고 답을 안 하면 시한이 유일한 탈출구다. */
    @Test
    void 답이_늦으면_시한에_걸려_모름이다() {
        delay = Duration.ofSeconds(3);
        body = "{}";

        long started = System.nanoTime();
        VideoPosition position = client().locate("s1", 1L);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        assertThat(position.state()).isEqualTo(State.UNAVAILABLE);
        assertThat(elapsedMs).isLessThan(2_500);
    }
}
