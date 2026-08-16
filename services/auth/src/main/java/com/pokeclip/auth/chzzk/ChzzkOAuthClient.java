package com.pokeclip.auth.chzzk;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 치지직 HTTP 4개 — 교환·갱신·철회·내 채널. HTTP만 안다. 저장·판정은 호출부.
 *
 * <p>본문은 JSON camelCase(공식 문서·get-chzzk-token.sh 실측). 응답은 {@code content} 래핑.
 */
@Component
public class ChzzkOAuthClient {

    private final RestClient restClient;
    private final ChzzkProperties properties;

    public ChzzkOAuthClient(RestClient.Builder builder, ChzzkProperties properties) {
        // builder를 주입받아야 spring.http.clients.* 타임아웃이 걸린다. RestClient.create()는 안 된다.
        this.restClient = builder.build();
        this.properties = properties;
    }

    public ChzzkTokens exchange(String code, String state) {
        return tokens(Map.of("grantType", "authorization_code",
                "clientId", app().clientId(), "clientSecret", app().clientSecret(),
                "code", code, "state", state));
    }

    public ChzzkTokens refresh(String refreshToken) {
        return tokens(Map.of("grantType", "refresh_token", "refreshToken", refreshToken,
                "clientId", app().clientId(), "clientSecret", app().clientSecret()));
    }

    /** tokenTypeHint는 "access_token" | "refresh_token". 실패는 예외로 — 호출부가 삼킬지 정한다. */
    public void revoke(String token, String tokenTypeHint) {
        call(() -> restClient.post().uri(properties.apiBaseUri() + "/auth/v1/token/revoke")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("clientId", app().clientId(), "clientSecret", app().clientSecret(),
                        "token", token, "tokenTypeHint", tokenTypeHint))
                .retrieve().toBodilessEntity());
    }

    public ChzzkMe fetchMe(String accessToken) {
        Map<?, ?> content = content(call(() -> restClient.get().uri(properties.apiBaseUri() + "/open/v1/users/me")
                .header("Authorization", "Bearer " + accessToken)
                .retrieve().body(Map.class)));
        // 실물에는 nickname도 실려 온다(2026-08-17) — 안 쓰므로 무시된다.
        if (!(content.get("channelId") instanceof String id)) {
            throw malformed("channelId", content.get("channelId"));
        }
        if (!(content.get("channelName") instanceof String name)) {
            throw malformed("channelName", content.get("channelName"));
        }
        return new ChzzkMe(id, name);
    }

    private ChzzkTokens tokens(Map<String, String> body) {
        // Map으로 받는다 — GoogleTokenClient와 같은 이유(ParameterizedTypeReference 리플렉션 회피).
        Map<?, ?> c = content(call(() -> restClient.post().uri(properties.apiBaseUri() + "/auth/v1/token")
                .contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().body(Map.class)));
        if (!(c.get("accessToken") instanceof String at)) {
            throw malformed("accessToken", c.get("accessToken"));
        }
        if (!(c.get("refreshToken") instanceof String rt)) {
            throw malformed("refreshToken", c.get("refreshToken"));
        }
        Duration expiresIn = expiresIn(c.get("expiresIn"));
        // scope는 발급 응답에도 실려 온다(실물 2026-08-17). 오면 저장하고 없으면 null.
        Object scope = c.get("scope");
        return new ChzzkTokens(at, rt, expiresIn, scope instanceof String s ? s : null);
    }

    /**
     * 실물은 정수(2026-08-17 실측 {@code "expiresIn":86400}), 공식 문서 표는 String — 둘 다 받는다.
     * 문서만 믿고 문자열만 받았다가 실 동의 왕복이 502로 끝났다.
     */
    private static Duration expiresIn(Object raw) {
        if (raw instanceof Number n) {
            return Duration.ofSeconds(n.longValue());
        }
        if (raw instanceof String s) {
            try {
                return Duration.ofSeconds(Long.parseLong(s.trim()));
            } catch (NumberFormatException e) {
                throw malformed("expiresIn", raw);   // 값은 안 넣는다 — 숫자가 아닌 문자열도 타입 이름만
            }
        }
        throw malformed("expiresIn", raw);
    }

    /** 어느 필드가 어떤 타입이었는지만 남긴다 — 값은 절대 안 넣는다(토큰 원문). */
    private static ChzzkUnavailableException malformed(String field, Object raw) {
        return new ChzzkUnavailableException("MalformedResponse field=" + field
                + " type=" + (raw == null ? "null" : raw.getClass().getSimpleName()));
    }

    private static Map<?, ?> content(Map<?, ?> response) {
        if (response == null || !(response.get("content") instanceof Map<?, ?> content)) {
            throw malformed("content", response == null ? null : response.get("content"));
        }
        return content;
    }

    /**
     * 4xx는 Rejected(status만), 5xx는 Unavailable("Http5xx"), 그 외 RestClientException(타임아웃·IO)은 Unavailable(원인 타입
     * 이름만). 응답 본문은 어디에도 옮기지 않는다.
     * 예외 둘 — 429(rate limit)·408(request timeout)은 4xx지만 "이 토큰이 무효"가 아니라 일시 상태다. Rejected로 보내면
     * 갱신은 REFRESH_REJECTED(BROKEN)·교환은 INVALID_CODE·revoke는 token_already_dead로 영구 오분류된다 → Unavailable(재시도).
     */
    private static <T> T call(Supplier<T> action) {
        try {
            return action.get();
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 429 || status == 408) {
                throw new ChzzkUnavailableException("Http" + status);
            }
            if (e.getStatusCode().is4xxClientError()) {
                throw new ChzzkRejectedException(status);
            }
            throw new ChzzkUnavailableException("Http" + status);   // 5xx — 상태 코드가 예외 클래스 이름보다 관측에 쓸모 있다
        } catch (RestClientException e) {
            throw new ChzzkUnavailableException(e.getClass().getSimpleName());
        }
    }

    private ChzzkProperties.App app() {
        return properties.app();
    }
}
