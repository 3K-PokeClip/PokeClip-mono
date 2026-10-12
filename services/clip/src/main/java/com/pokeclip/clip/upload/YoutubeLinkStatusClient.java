package com.pokeclip.clip.upload;

import com.pokeclip.clip.config.InternalApiProperties;
import com.pokeclip.clip.delegation.AuthClientProperties;
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
import java.util.Set;

/**
 * 「이 스트리머의 유튜브 채널이 연결돼 있나」를 auth에 묻는다(POK-291, {@code POST /internal/youtube-link/status}).
 * 「영상 만들기」가 비싼 렌더를 시작하기 전에 연결이 확실히 없는 경우만 막으려고 쓴다.
 *
 * <p><b>예외를 안 던지고, 모르면 막지 않는다(fail-open).</b> auth가 죽었거나 느리거나 모르는 모양을 주면 {@link Status#UNKNOWN}이고
 * 주문은 그대로 간다: 렌더를 auth 장애로 막지 않는다. 연결이 정말 없으면 업로드 일꾼이 그때 실패로 닫는다.
 *
 * <p>resolve 문을 안 쓰는 이유: 그 문은 액세스 토큰을 돌려주고 갱신까지 일으킨다. 「연결돼 있나」에 토큰이 clip으로 흘러올 이유가 없다.
 *
 * <p>시한이 전역({@code spring.http.clients.*}, 접속 2초 + 읽기 5초)보다 짧은 2초다. 사람이 「만들고 올리기」를 누른 채 기다리는
 * 요청이고, 그 앞에 자격 판정 왕복이 이미 있다. 되걸기·리다이렉트는 주입받은 팩토리 빌더가 끈 채다({@code CollectorHttpConfig}와
 * 같은 이유: {@code detect()}를 새로 부르면 둘 다 되살아난다).
 */
@Component
public class YoutubeLinkStatusClient {

    private static final Logger log = LoggerFactory.getLogger(YoutubeLinkStatusClient.class);

    static final Duration TIMEOUT = Duration.ofSeconds(2);

    private static final String PATH = "/internal/youtube-link/status";
    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";
    private static final Set<String> REASONS = Set.of("NOT_LINKED", "UNLINKED", "BROKEN");

    private final RestClient restClient;
    private final String baseUrl;
    private final String internalToken;
    private final ObjectMapper mapper;

    YoutubeLinkStatusClient(RestClient.Builder builder, ClientHttpRequestFactoryBuilder<?> factoryBuilder,
                            AuthClientProperties auth, InternalApiProperties internalApi, ObjectMapper mapper) {
        this.restClient = builder
                .requestFactory(factoryBuilder.build(HttpClientSettings.defaults().withTimeouts(TIMEOUT, TIMEOUT)))
                .build();
        this.baseUrl = auth.baseUrl();
        this.internalToken = internalApi.token();
        this.mapper = mapper;
    }

    /** 판정. {@code reason}은 {@code linked=false}일 때만 있다. */
    public record Status(Boolean linked, String reason) {
        public static final Status UNKNOWN = new Status(null, null);

        public boolean notLinked() {
            return Boolean.FALSE.equals(linked);
        }
    }

    public Status status(long userId) {
        try {
            String body = restClient.post()
                    .uri(baseUrl + PATH)
                    .header(INTERNAL_TOKEN_HEADER, internalToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    // 회원 번호는 long이라 문자열로 이어도 JSON이 깨지지 않는다.
                    .body("{\"userId\":" + userId + "}")
                    .retrieve()
                    .body(String.class);
            JsonNode json = body == null ? null : mapper.readTree(body);
            JsonNode linked = json == null ? null : json.get("linked");
            if (linked == null || !linked.isBoolean()) {
                return unknown(userId, "bad_body");
            }
            if (linked.booleanValue()) {
                return new Status(true, null);
            }
            String reason = json.path("reason").asString(null);
            // 모르는 사유 낱말은 그대로 안 싣는다(화면 문구가 갈린다). 「연결 안 됨」 자체는 확실하니 막는다.
            return new Status(false, REASONS.contains(reason == null ? "" : reason) ? reason : "NOT_LINKED");
        } catch (RestClientResponseException e) {
            return unknown(userId, "status=" + e.getStatusCode().value());
        } catch (RuntimeException e) {
            // 못 닿음·시한 초과·본문이 JSON이 아님.
            return unknown(userId, e.getClass().getSimpleName());
        }
    }

    private static Status unknown(long userId, String cause) {
        log.warn("clip.upload.link_check_skipped userId={} cause={}", userId, cause);
        return Status.UNKNOWN;
    }
}
