package com.pokeclip.clip.delegation;

import com.pokeclip.clip.config.InternalApiProperties;
import com.pokeclip.clip.support.FakeAuth;
import com.pokeclip.clip.support.IntegrationTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * auth 이름 창구 클라이언트(POK-430). 운영과 같은 빌더 둘(스프링이 주입한 것)로 만들어 진짜 소켓에 보낸다.
 * 목록 문 시험({@code BroadcastListControllerTest})이 실패 모양을 재고, 여기서는 본문 · 번호 고르기 · 시한을 잰다.
 */
class StreamerChannelNameClientTest extends IntegrationTestSupport {

    private static final String PATH = "/internal/streamer-channel-names";
    private static final String TOKEN = "internal-token-for-test";

    private final RestClient.Builder builder;
    private final ClientHttpRequestFactoryBuilder<?> factoryBuilder;
    private final ObjectMapper mapper;

    private FakeAuth auth;

    StreamerChannelNameClientTest(RestClient.Builder builder, ClientHttpRequestFactoryBuilder<?> factoryBuilder,
                                  ObjectMapper mapper) {
        this.builder = builder;
        this.factoryBuilder = factoryBuilder;
        this.mapper = mapper;
    }

    @BeforeEach
    void 시작() {
        auth = FakeAuth.start();
    }

    @AfterEach
    void 끝() {
        auth.close();
    }

    /** 요청한 번호만 읽는다: auth가 묻지 않은 번호를 덧붙여 줘도 결과에 안 들어간다. 칸 값이 글자가 아니면 그 칸만 null이다. */
    @Test
    void 요청한_번호의_이름만_읽는다() {
        auth.respondWith(200, """
                {"names":{"7":{"chzzk":"내 치지직","soop":null},"3":{"chzzk":12,"soop":"남의 숲"},"99":{"chzzk":"안 물은 번호","soop":null}}}""");

        Map<Long, ChannelNames> names = client().namesOf(List.of(7L, 3L, 5L));

        assertThat(auth.callCount(PATH)).isEqualTo(1);
        assertThat(auth.lastToken()).isEqualTo(TOKEN);
        assertThat(auth.lastBodyOf(PATH)).isEqualTo("{\"streamerUserIds\":[7,3,5]}");
        assertThat(names).containsOnlyKeys(7L, 3L);
        assertThat(names.get(7L)).isEqualTo(new ChannelNames("내 치지직", null));
        assertThat(names.get(3L)).isEqualTo(new ChannelNames(null, "남의 숲"));
    }

    @Test
    void 번호가_없으면_안_부른다() {
        assertThat(client().namesOf(List.of())).isEmpty();
        assertThat(auth.callCount()).isZero();
    }

    /**
     * 전용 시한(2초)이 실제로 걸려 있다. auth가 4초 붙들면: 전역 시한(읽기 5초)으로 부르는 구현은 다 기다려 이름을 받고, 이 클라이언트는
     * 2초에 끊고 빈 맵이다. 값이 아니라 걸린 시간으로 잰다.
     */
    @Test
    void 전용_시한이_걸려_있다() {
        auth.respondWith(200, "{\"names\":{\"7\":{\"chzzk\":\"늦은 이름\",\"soop\":null}}}");
        auth.holdFor(Duration.ofSeconds(4));

        long 시작 = System.nanoTime();
        Map<Long, ChannelNames> names = client().namesOf(List.of(7L));
        Duration 걸림 = Duration.ofNanos(System.nanoTime() - 시작);

        assertThat(auth.callCount()).as("요청을 안 보냈으면 시한을 잰 것이 아니다").isEqualTo(1);
        assertThat(names).as("시한을 넘긴 답을 받았다: 전용 시한이 안 걸렸다").isEmpty();
        assertThat(걸림).isLessThan(Duration.ofMillis(3_500));
    }

    private StreamerChannelNameClient client() {
        return new StreamerChannelNameClient(builder, factoryBuilder, new AuthClientProperties(auth.baseUrl()),
                new InternalApiProperties(TOKEN), mapper);
    }
}
