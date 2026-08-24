package com.pokeclip.auth.youtube;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 구글 HTTP 4개 — 교환·갱신·철회·채널 목록. HTTP만 안다. 저장·판정은 호출부.
 *
 * <p>치지직({@code chzzk/ChzzkOAuthClient})과 다른 점 넷: ① 본문이 <b>form urlencoded</b>에 snake_case
 * ② 응답에 {@code content} 래핑이 없어 최상위에서 읽는다 ③ 오류 본문 형식이 주소마다 다르다
 * ④ revoke에 tokenTypeHint가 없다(그리고 한 번이면 grant 전체가 죽는다 — 호출 횟수는 호출부가 정한다).
 */
@Component
public class YoutubeOAuthClient {

    private final RestClient restClient;
    private final YoutubeProperties properties;

    public YoutubeOAuthClient(RestClient.Builder builder, YoutubeProperties properties) {
        // builder를 주입받아야 spring.http.clients.* 타임아웃이 걸린다. RestClient.create()는 안 된다.
        this.restClient = builder.build();
        this.properties = properties;
    }

    public YoutubeTokens exchange(String code) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("code", code);
        form.add("client_id", app().clientId());
        form.add("client_secret", app().clientSecret());
        form.add("redirect_uri", app().redirectUri());
        form.add("grant_type", "authorization_code");
        return tokens(form, true);
    }

    public YoutubeTokens refresh(String refreshToken) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("refresh_token", refreshToken);
        form.add("client_id", app().clientId());
        form.add("client_secret", app().clientSecret());
        form.add("grant_type", "refresh_token");
        return tokens(form, false);
    }

    /**
     * 철회. 실패는 예외로 — 호출부가 삼킬지 정한다.
     *
     * <p><b>한 번이면 충분하고, 아무 데서나 부르면 안 된다</b> — 구글 revoke는 그 사용자가 이 프로젝트에
     * 준 동의 <b>전부</b>를 무효화한다(access·refresh 모두, 언제 발급됐든). 부르는 자리는 사용자 해제와
     * 갱신 거부 정리 둘뿐이다(계획 2절 결정 8).
     */
    public void revoke(String token) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("token", token);
        call(() -> restClient.post().uri(properties.revokeUri())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve().toBodilessEntity());
    }

    /**
     * 내 채널 목록. {@code items} 키가 없거나 빈 배열이면 <b>채널 0개</b>다 — 형식 붕괴가 아니다.
     * 채널 없는 계정이 키를 생략해도 호출부가 「채널을 먼저 만드세요」(400)로 안내할 수 있어야 한다.
     */
    public List<YoutubeChannel> listChannels(String accessToken) {
        Map<?, ?> body = call(() -> restClient.get()
                .uri(properties.apiBaseUri() + "/youtube/v3/channels?part=snippet&mine=true")
                .header("Authorization", "Bearer " + accessToken)
                .retrieve().body(Map.class));
        if (body == null) {
            throw malformed("body", null);
        }
        Object items = body.get("items");
        if (items == null) {
            return List.of();
        }
        if (!(items instanceof List<?> list)) {
            throw malformed("items", items);
        }
        List<YoutubeChannel> channels = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                throw malformed("items[]", item);
            }
            if (!(map.get("id") instanceof String id)) {
                throw malformed("items[].id", map.get("id"));
            }
            Object snippet = map.get("snippet");
            if (!(snippet instanceof Map<?, ?> s)) {
                throw malformed("items[].snippet", snippet);
            }
            if (!(s.get("title") instanceof String title)) {
                throw malformed("items[].snippet.title", s.get("title"));
            }
            channels.add(new YoutubeChannel(id, title));
        }
        return channels;
    }

    /**
     * @param requireRefresh 교환이면 true — refresh_token이 없는 연동은 갱신할 수 없는 반쪽이라 실패로 다룬다.
     *                       갱신이면 false — 구글은 보통 refresh를 다시 주지 않고, 그것이 정상이다.
     */
    private YoutubeTokens tokens(MultiValueMap<String, String> form, boolean requireRefresh) {
        // Map으로 받는다 — GoogleTokenClient와 같은 이유(ParameterizedTypeReference 리플렉션 회피).
        Map<?, ?> body = call(() -> restClient.post().uri(properties.tokenUri())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form)
                .retrieve().body(Map.class));
        if (body == null) {
            throw malformed("body", null);
        }
        if (!(body.get("access_token") instanceof String at)) {
            throw malformed("access_token", body.get("access_token"));
        }
        String scope = body.get("scope") instanceof String s ? s : null;
        String rt = body.get("refresh_token") instanceof String r ? r : null;

        Duration expiresIn;
        try {
            expiresIn = expiresIn(body.get("expires_in"));
        } catch (YoutubeUnavailableException e) {
            // 토큰은 이미 읽혔다 = 구글엔 발급됐다. 예외에 실어 호출부가 버릴지 정하게 한다(값은 메시지에 안 실린다).
            throw new YoutubeUnavailableException(e.causeType(), new YoutubeTokens(at, rt, null, scope));
        }
        if (requireRefresh && rt == null) {
            // 같은 이유로 access를 실어 보낸다 — 안 실으면 호출부가 물리적으로 못 버린다.
            throw new YoutubeUnavailableException(
                    malformedCause("refresh_token", body.get("refresh_token")),
                    new YoutubeTokens(at, null, expiresIn, scope));
        }
        return new YoutubeTokens(at, rt, expiresIn, scope);
    }

    /** 구글은 정수 3600을 준다(문서). 치지직에서 문서와 실물이 갈렸던 자리라 문자열도 받는다. */
    private static Duration expiresIn(Object raw) {
        if (raw instanceof Number n) {
            return Duration.ofSeconds(n.longValue());
        }
        if (raw instanceof String s) {
            try {
                return Duration.ofSeconds(Long.parseLong(s.trim()));
            } catch (NumberFormatException e) {
                throw malformed("expires_in", raw);   // 값은 안 넣는다 — 숫자가 아닌 문자열도 타입 이름만
            }
        }
        throw malformed("expires_in", raw);
    }

    /** 어느 필드가 어떤 타입이었는지만 남긴다 — 값은 절대 안 넣는다(토큰 원문). */
    private static YoutubeUnavailableException malformed(String field, Object raw) {
        return new YoutubeUnavailableException(malformedCause(field, raw));
    }

    private static String malformedCause(String field, Object raw) {
        return "MalformedResponse field=" + field
                + " type=" + (raw == null ? "null" : raw.getClass().getSimpleName());
    }

    /**
     * 4xx인데 「이 토큰이 무효」가 아닌 코드들 → 일시(재시도). 값은 열거 코드와 <b>비교만</b> 하고,
     * causeType에는 여기 고정된 이름만 실린다(응답 값을 옮기지 않는다).
     *
     * <p>{@code invalid_client}는 우리 앱 설정 문제(시크릿 회전·오타)라 영구로 닫으면 회원 전원이 재동의해야 한다.
     * 403 할당량 셋은 YouTube Data API가 일 10,000유닛을 소진했을 때다 — 태평양시 자정에 스스로 풀린다.
     */
    private static final Map<String, String> TEMPORARY_ERROR_CODES = Map.of(
            "invalid_client", "InvalidClient",
            "quotaExceeded", "QuotaExceeded",
            "rateLimitExceeded", "RateLimitExceeded",
            "userRateLimitExceeded", "UserRateLimitExceeded");

    /**
     * 4xx는 Rejected(status만), 5xx는 Unavailable("Http5xx"), 그 외 RestClientException(타임아웃·IO)은
     * Unavailable(원인 타입 이름만). 응답 본문은 어디에도 옮기지 않는다 — 읽는 것은 오류 코드 하나뿐이고
     * 그것도 위 표와 비교만 한다. 429·408과 표에 있는 코드는 4xx라도 일시로 본다.
     */
    private static <T> T call(Supplier<T> action) {
        try {
            return action.get();
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 429 || status == 408) {
                throw new YoutubeUnavailableException("Http" + status);
            }
            if (e.getStatusCode().is4xxClientError()) {
                String code = errorCode(e);
                // Map.of(...)는 get(null)에서 NPE다(ImmutableCollections). 본문이 JSON이 아니거나
                // 오류 코드가 없으면 여기 null이 온다 — 그때는 코드가 없으니 Rejected 그대로.
                String temporary = code == null ? null : TEMPORARY_ERROR_CODES.get(code);
                if (temporary != null) {
                    throw new YoutubeUnavailableException(temporary);
                }
                throw new YoutubeRejectedException(status);
            }
            throw new YoutubeUnavailableException("Http" + status);   // 5xx — 상태 코드가 클래스 이름보다 관측에 쓸모 있다
        } catch (RestClientException e) {
            throw new YoutubeUnavailableException(e.getClass().getSimpleName());
        }
    }

    /**
     * 오류 코드만 꺼낸다. 형식이 주소마다 다르다 — ① 토큰·철회({@code oauth2.googleapis.com})는
     * {@code "error":"invalid_grant"}로 <b>문자열</b> ② YouTube Data API는
     * {@code "error":{"errors":[{"reason":"quotaExceeded"}]}}로 <b>객체</b>다.
     * 한쪽만 읽으면 다른 쪽의 일시 특례가 영영 발동하지 않는다. 어디에도 옮기지 않는다.
     */
    private static String errorCode(RestClientResponseException e) {
        try {
            Map<?, ?> body = e.getResponseBodyAs(Map.class);
            if (body == null) {
                return null;
            }
            Object error = body.get("error");
            if (error instanceof String code) {
                return code;
            }
            if (error instanceof Map<?, ?> m && m.get("errors") instanceof List<?> l
                    && !l.isEmpty() && l.get(0) instanceof Map<?, ?> first
                    && first.get("reason") instanceof String reason) {
                return reason;
            }
            return null;
        } catch (RuntimeException parseFailure) {
            return null;
        }
    }

    private YoutubeProperties.App app() {
        return properties.app();
    }
}
