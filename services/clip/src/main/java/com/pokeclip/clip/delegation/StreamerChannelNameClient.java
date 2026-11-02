package com.pokeclip.clip.delegation;

import com.pokeclip.clip.config.InternalApiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * 스트리머 번호 여럿의 플랫폼 채널 이름을 auth에 한 번에 묻는다(POK-430, {@code POST /internal/streamer-channel-names}).
 * 방송 목록 한 장이 부른다.
 *
 * <p><b>예외를 안 던진다.</b> 이름은 부가 칸이라 auth가 죽었거나 느리거나 모르는 모양을 주면 빈 맵이고 목록은 그대로 나간다
 * ({@code BroadcastTitleClient}와 같은 처리). 칸 값이 글자가 아니면 그 칸만 {@code null}이다.
 *
 * <p><b>요청한 번호만 읽는다.</b> 응답의 키를 훑지 않고 보낸 번호마다 찾는다: auth가 묻지 않은 번호를 덧붙여 줘도 목록에 안 섞인다.
 *
 * <p>시한이 전역({@code spring.http.clients.*}, 접속 2초 + 읽기 5초)보다 짧은 2초다. 사람이 목록을 기다리는 요청이고 그 앞에 자격 판정
 * 왕복 · 수집기 제목 왕복이 이미 차례로 있다({@code YoutubeLinkStatusClient}와 같은 이유). 되걸기 · 리다이렉트는 주입받은 팩토리 빌더가
 * 끈 채다: {@code detect()}를 새로 부르면 둘 다 되살아난다({@code AuthRetryContractTest}가 잰다).
 */
@Component
public class StreamerChannelNameClient {

    private static final Logger log = LoggerFactory.getLogger(StreamerChannelNameClient.class);

    static final Duration TIMEOUT = Duration.ofSeconds(2);

    private static final String PATH = "/internal/streamer-channel-names";
    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

    private final RestClient restClient;
    private final String baseUrl;
    private final String internalToken;
    private final ObjectMapper mapper;

    StreamerChannelNameClient(RestClient.Builder builder, ClientHttpRequestFactoryBuilder<?> factoryBuilder,
                              AuthClientProperties auth, InternalApiProperties internalApi, ObjectMapper mapper) {
        this.restClient = builder
                .requestFactory(factoryBuilder.build(HttpClientSettings.defaults().withTimeouts(TIMEOUT, TIMEOUT)))
                .build();
        this.baseUrl = auth.baseUrl();
        this.internalToken = internalApi.token();
        this.mapper = mapper;
    }

    /**
     * @param streamerUserIds 스트리머 번호. auth 상한(100)은 방송 목록 한 장의 상한과 같다
     * @return 번호별 이름. auth가 그 번호를 안 줬거나 못 물었으면 그 번호가 맵에 없다
     */
    public Map<Long, ChannelNames> namesOf(Collection<Long> streamerUserIds) {
        Map<Long, ChannelNames> out = new HashMap<>();
        if (streamerUserIds.isEmpty()) {
            return out;
        }
        try {
            String body = restClient.post()
                    .uri(baseUrl + PATH)
                    .header(INTERNAL_TOKEN_HEADER, internalToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(mapper.writeValueAsString(Map.of("streamerUserIds", streamerUserIds)))
                    .retrieve()
                    .body(String.class);
            // 본문이 비면(null) readTree가 IllegalArgumentException을, 공백뿐이면 null을 준다. 둘 다 「못 읽음」으로 한 자리에서 접는다
            JsonNode root = body == null ? null : mapper.readTree(body);
            JsonNode names = root == null ? null : root.get("names");
            if (names == null || !names.isObject()) {
                return unavailable("not_object");
            }
            for (Long id : streamerUserIds) {
                JsonNode one = names.path(String.valueOf(id));
                if (one.isObject()) {
                    out.put(id, new ChannelNames(text(one.path("chzzk")), text(one.path("soop"))));
                }
            }
            return out;
        } catch (RestClientResponseException e) {
            return unavailable("status=" + e.getStatusCode().value());
        } catch (RuntimeException e) {
            // 못 닿음 · 시한 초과 · 본문이 JSON이 아님 · 3xx(리다이렉트를 안 따라가 빈 본문으로 온다)
            return unavailable(e.getClass().getSimpleName());
        }
    }

    /** 받은 값 · 이름은 안 싣는다(SOOP 닉네임은 파기 대상이다). */
    private static Map<Long, ChannelNames> unavailable(String cause) {
        log.warn("clip.list.channel_names_unavailable cause={}", cause);
        return new HashMap<>();
    }

    private static String text(JsonNode node) {
        return node.isString() ? node.asString() : null;
    }
}
