package com.pokeclip.chat.collector.demo;

import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;

/**
 * clip의 {@code POST /internal/broadcasts/{streamId}/highlights}(계약 2A — 판별기가 카드를 넣는 문)에 데모 카드를 넣는다.
 *
 * <p>쌍둥이는 {@code platforms/ClipPlatformClient}(같은 clip · 같은 토큰 헤더 · 「주입받은 빌더를 쓴다」)다. 주소 · 토큰이 비었을 때 이것을
 * 만들지 않는 것은 {@link DemoConfiguration}이 정한다.
 *
 * <p>던지지 않는다. 다시 보내는 것은 부르는 쪽({@link DemoHighlight}) 몫이다 — 같은 카드는 clip이 (방송, 구간 시작) 유일 색인으로 한 장만 남긴다.
 */
final class ClipDemoCardClient implements DemoHighlight.Sink {

    static final String PATH = "/internal/broadcasts/{streamId}/highlights";
    static final Duration CONNECT_TIMEOUT = Duration.ofMillis(500);
    /** clip은 카드 저장 · 장면 조회를 한 번 하고 답한다. 중계(1초)보다 넉넉히 둔다 */
    static final Duration READ_TIMEOUT = Duration.ofSeconds(3);

    /** clip의 {@code InternalTokenFilter}가 보는 이름. 빠지면 401이다. */
    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

    private final RestClient restClient;
    private final String baseUrl;
    private final String internalToken;

    ClipDemoCardClient(RestClient.Builder builder, String baseUrl, String internalToken) {
        // 값은 메시지에 넣지 않는다 — 부팅 실패 메시지는 로그에 그대로 찍힌다
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("pokeclip.demo.clip-base-url이(가) 비어 있다. CLIP_BASE_URL 환경변수를 준다.");
        }
        if (internalToken == null || internalToken.isBlank()) {
            throw new IllegalStateException("pokeclip.link.internal-token이(가) 비어 있다. INTERNAL_API_TOKEN 환경변수를 준다.");
        }
        this.restClient = builder.build();
        this.baseUrl = baseUrl;
        this.internalToken = internalToken;
    }

    /**
     * 운영 배선. 🔴 주입받은 팩토리 빌더를 쓴다: {@code ClientHttpRequestFactoryBuilder.detect()}를 새로 부르면 클래스패스의
     * httpclient5가 잡혀 스택이 조용히 바뀐다({@code RelayConfiguration}과 같은 이유).
     */
    static ClipDemoCardClient create(RestClient.Builder builder, ClientHttpRequestFactoryBuilder<?> factoryBuilder,
                                     String baseUrl, String internalToken) {
        return new ClipDemoCardClient(builder.requestFactory(factoryBuilder.build(
                HttpClientSettings.defaults().withTimeouts(CONNECT_TIMEOUT, READ_TIMEOUT))), baseUrl, internalToken);
    }

    @Override
    public int send(String streamId, DemoHighlight.Card card) {
        try {
            return restClient.post()
                    .uri(baseUrl + PATH, streamId)
                    .header(INTERNAL_TOKEN_HEADER, internalToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body(card))
                    .exchange((request, response) -> response.getStatusCode().value());
        } catch (RuntimeException e) {
            // 상태를 못 받았다(접속 실패 · 시한 초과). 예외 메시지에는 주소가 실려 남기지 않는다 — 부르는 쪽이 상태 -1로 찍는다
            return -1;
        }
    }

    /** 판별기 {@code HighlightCard.toJson}과 같은 모양이다. 근거는 화면이 읽는 {@code messageCount}만 싣는다 */
    static Map<String, Object> body(DemoHighlight.Card card) {
        return Map.of(
                "eventId", card.eventId(),
                "source", "auto",
                "streamTimestampMs", card.streamTimestampMs(),
                "window", Map.of("startMs", card.startMs(), "endMs", card.endMs()),
                "evidence", Map.of("demo", true, "messageCount", card.messageCount()));
    }
}
