package com.pokeclip.chat.collector.soop;

import com.pokeclip.chat.collector.link.LinkProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Set;

/**
 * 스트리머 한 명의 SOOP 연동을 auth에서 받는다({@code POST /internal/soop-link/resolve}, POK-396). 계약은 auth의
 * {@code SoopLinkResolveController}: 항상 HTTP 200이고 거절이면 {@code {valid:false, reason}}이다. 쌍둥이는 {@code link/ChzzkLinkClient}다
 * (같은 auth·같은 토큰 헤더·같은 영구 사유 셋).
 *
 * <p>🔴 <b>토큰과 SOOP 아이디를 어디에도 남기지 않는다.</b> 로그는 회원 번호·사유·만료 시각만, 예외는 타입 이름·상태 코드만이다
 * ({@code RestClientResponseException}의 메시지에는 응답 본문, 곧 토큰이 딸려 온다).
 */
public class SoopLinkClient {

    private static final Logger log = LoggerFactory.getLogger(SoopLinkClient.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String RESOLVE_PATH = "/internal/soop-link/resolve";

    /** auth의 {@code InternalTokenFilter}가 보는 이름. 빠지면 401이다. */
    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

    /**
     * 다시 물어도 안 바뀌는 사유만. 그 밖(REFRESH_UNAVAILABLE · auth에 못 닿음 · 모르는 사유)은 다음 틱에 다시 묻는다
     * ({@code ChzzkLinkClient}와 같은 판단: 모르는 것을 영구로 떨어뜨리면 그 방송 SOOP 값이 통째로 빠지는데 아무 데도 안 남는다).
     */
    private static final Set<String> PERMANENT_REASONS = Set.of("NOT_LINKED", "UNLINKED", "BROKEN");

    private final RestClient restClient;
    private final String baseUrl;
    private final String internalToken;

    /** {@code RestClient.create()}를 쓰지 않는다. 주입받은 빌더에 전역 시한이 걸려 있다. 토큰·주소가 비면 부팅을 죽인다. */
    public SoopLinkClient(RestClient.Builder builder, LinkProperties properties) {
        properties.validate();
        this.restClient = builder.build();
        this.baseUrl = properties.authBaseUrl();
        this.internalToken = properties.internalToken();
    }

    public SoopLink resolve(long userId) {
        try {
            String body = restClient.post()
                    .uri(baseUrl + RESOLVE_PATH)
                    .header(INTERNAL_TOKEN_HEADER, internalToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    // 회원 번호는 long이라 문자열로 이어도 JSON이 깨지지 않는다.
                    .body("{\"userId\":" + userId + "}")
                    .retrieve()
                    .body(String.class);
            return read(userId, body);
        } catch (RestClientResponseException e) {
            return unavailable(userId, "status=" + e.getStatusCode().value());
        } catch (Exception e) {
            return unavailable(userId, "cause=" + e.getClass().getSimpleName());
        }
    }

    private SoopLink read(long userId, String body) {
        JsonNode json = MAPPER.readTree(body);
        if (!json.path("valid").asBoolean(false)) {
            String reason = json.path("reason").asString("");
            boolean retryable = !PERMANENT_REASONS.contains(reason);
            // WARN이 아닌 이유: SOOP을 연동 안 한 스트리머의 방송 시작은 정상 트래픽이다.
            log.info("soop.link.rejected userId={} reason={} retryable={}", userId, reason, retryable);
            return SoopLink.refused(retryable);
        }
        String soopUserId = json.path("soopUserId").asString("");
        String accessToken = json.path("accessToken").asString("");
        Instant expiresAt = parseExpiry(json.path("expiresAt").asString(""));
        if (soopUserId.isBlank() || accessToken.isBlank() || expiresAt == null) {
            // 허락이라면서 필수 칸이 빠졌다. 다시 물어도 같은 답이라 영구로 둔다(ChzzkLinkClient의 chat.link.incomplete와 같은 자리).
            // 값이 아니라 있고 없음만 싣는다.
            log.warn("soop.link.incomplete userId={} soopUserIdMissing={} accessTokenMissing={} expiresAtUnreadable={}",
                    userId, soopUserId.isBlank(), accessToken.isBlank(), expiresAt == null);
            return SoopLink.refused(false);
        }
        log.info("soop.link.resolved userId={} expiresAt={}", userId, expiresAt);
        return SoopLink.granted(soopUserId, accessToken, expiresAt);
    }

    private static Instant parseExpiry(String raw) {
        try {
            return raw.isBlank() ? null : Instant.parse(raw);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private SoopLink unavailable(long userId, String detail) {
        log.warn("soop.link.unavailable userId={} {}", userId, detail);
        return SoopLink.refused(true);
    }
}
