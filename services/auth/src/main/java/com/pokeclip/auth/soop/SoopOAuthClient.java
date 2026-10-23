package com.pokeclip.auth.soop;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SOOP HTTP 셋: 교환·갱신({@code /auth/token}) · 아이디·닉네임({@code /validate/live/status} + {@code /user/stationinfo}).
 * HTTP만 안다. 저장·판정은 호출부.
 *
 * <p>치지직과 다른 점: 본문이 전부 form이다(공식 문서 2026-10-23). 토큰 응답은 snake_case 평면이고 API 응답은
 * {@code {"result":1,"data":{…}}}다. 폐기 API가 없어 revoke가 없다. 액세스 토큰도 헤더가 아니라 form 본문에 싣는다
 * (문서가 그 꼴이다). 어느 쪽이든 주소(쿼리)에는 싣지 않는다: 주소는 프록시·접근 로그에 남는다.
 */
@Component
public class SoopOAuthClient {

    private final RestClient restClient;
    private final SoopProperties properties;

    public SoopOAuthClient(RestClient.Builder builder, SoopProperties properties) {
        // builder를 주입받아야 spring.http.clients.* 시한이 걸린다. RestClient.create()는 안 된다(치지직과 같다).
        this.restClient = builder.build();
        this.properties = properties;
    }

    public SoopTokens exchange(String code) {
        MultiValueMap<String, String> form = appForm("authorization_code");
        form.add("code", code);
        SoopTokens t = tokens(form);
        // 교환에서 refresh가 없으면 이 연동은 첫 만료(문서 예시 8시간)에 반드시 죽는다. 저장하지 않게 일시 실패로 돌린다.
        if (t.refreshToken() == null) {
            throw malformed("refresh_token", null);
        }
        return t;
    }

    /** 응답에 refresh_token이 없으면 {@link SoopTokens#refreshToken()}이 null이다. 호출부가 옛 값을 유지한다. */
    public SoopTokens refresh(String refreshToken) {
        MultiValueMap<String, String> form = appForm("refresh_token");
        form.add("refresh_token", refreshToken);
        return tokens(form);
    }

    /**
     * 방송국 정보 {@code profile_image}의 대표 이미지 주소 꼴: {@code …/LOGO/<두 글자>/<아이디>/<아이디>.<확장자>…}(실측 2026-10-23).
     * 두 자리의 아이디가 같을 때만 믿는다(역참조 {@code \1}). 문서에 없는 꼴이라 SOOP이 바꾸면 여기서 못 읽는다(README 한계).
     */
    private static final Pattern LOGO_ID = Pattern.compile(".*/LOGO/[^/]+/([^/]+)/\\1\\.(jpg|png|jpeg|webp).*");

    /**
     * 아이디와 닉네임을 합친다. 아이디를 정하는 순서(실측 2026-10-23):
     * ① 방송 여부 체크의 {@code user_id}(빈 값 아님). 🔴 문서 예시와 달리 방송 중이 아니면 data가 {@code broad_no}·{@code status}뿐이라
     * 아이디가 없다 ② 없으면 방송국 정보 {@code profile_image} 주소에서 꺼낸다 ③ 둘 다 안 되면 {@link SoopIdentityUnavailableException}:
     * 대표 이미지가 없는 방송국은 방송 중에만 아이디가 온다. 거부로 보내면 「동의부터 다시」, 일시 장애로 보내면 「잠시 뒤 다시」라
     * 둘 다 틀린 안내다(사용자 결정 B, 2026-10-23).
     * 어느 주소든 {@code result} 음수는 지금처럼 거부다. 방송 여부 체크가 거부면 방송국 정보는 묻지 않는다.
     */
    public SoopIdentity fetchIdentity(String accessToken) {
        Map<?, ?> status = data(post("/validate/live/status", accessTokenForm(accessToken)));
        Map<?, ?> station = data(post("/user/stationinfo", accessTokenForm(accessToken)));
        if (!(station.get("user_nick") instanceof String nick)) {
            throw malformed("user_nick", station.get("user_nick"));
        }
        String userId = status.get("user_id") instanceof String s && !s.isBlank() ? s : idFromProfileImage(station);
        if (userId == null) {
            throw new SoopIdentityUnavailableException();   // 값은 안 싣는다
        }
        return new SoopIdentity(userId, nick);
    }

    private static String idFromProfileImage(Map<?, ?> station) {
        if (!(station.get("profile_image") instanceof String image)) {
            return null;
        }
        Matcher m = LOGO_ID.matcher(image);
        return m.matches() ? m.group(1) : null;
    }

    private SoopTokens tokens(MultiValueMap<String, String> form) {
        Map<?, ?> body = post("/auth/token", form);
        if (!(body.get("access_token") instanceof String at)) {
            throw malformed("access_token", body.get("access_token"));
        }
        Object rt = body.get("refresh_token");
        if (rt != null && !(rt instanceof String)) {
            throw malformed("refresh_token", rt);
        }
        Object scope = body.get("scope");
        return new SoopTokens(at, (String) rt, expiresIn(body.get("expires_in")),
                scope instanceof String s ? s : null);
    }

    private MultiValueMap<String, String> appForm(String grantType) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", grantType);
        form.add("client_id", properties.app().clientId());
        form.add("client_secret", properties.app().clientSecret());
        form.add("redirect_uri", properties.app().redirectUri());
        return form;
    }

    private static MultiValueMap<String, String> accessTokenForm(String accessToken) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("access_token", accessToken);
        return form;
    }

    /** Map으로 받는다: 치지직·GoogleTokenClient와 같은 이유(Jackson import 없이 instanceof로 칸을 고른다). */
    private Map<?, ?> post(String path, MultiValueMap<String, String> form) {
        Map<?, ?> body = call(() -> restClient.post().uri(properties.apiBaseUri() + path)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form)
                .retrieve().body(Map.class));
        if (body == null) {
            throw malformed("body", null);
        }
        return body;
    }

    /** API 응답의 {@code result}를 보고 {@code data}를 꺼낸다. 음수면 거부(status 200). {@code msg}는 옮기지 않는다. */
    private static Map<?, ?> data(Map<?, ?> body) {
        if (!(body.get("result") instanceof Number result)) {
            throw malformed("result", body.get("result"));
        }
        if (result.intValue() < 0) {
            throw new SoopRejectedException(200);
        }
        if (!(body.get("data") instanceof Map<?, ?> data)) {
            throw malformed("data", body.get("data"));
        }
        return data;
    }

    /** 문서 예시는 정수(28800)다. 치지직에서 문서와 실물의 타입이 갈린 적이 있어 숫자 문자열도 받는다. */
    private static Duration expiresIn(Object raw) {
        if (raw instanceof Number n) {
            return Duration.ofSeconds(n.longValue());
        }
        if (raw instanceof String s) {
            try {
                return Duration.ofSeconds(Long.parseLong(s.trim()));
            } catch (NumberFormatException e) {
                throw malformed("expires_in", raw);
            }
        }
        throw malformed("expires_in", raw);
    }

    /** 어느 필드가 어떤 타입이었는지만 남긴다. 값은 절대 안 넣는다(토큰 원문). */
    private static SoopUnavailableException malformed(String field, Object raw) {
        return new SoopUnavailableException("MalformedResponse field=" + field
                + " type=" + (raw == null ? "null" : raw.getClass().getSimpleName()));
    }

    /**
     * 400·401·403만 거부(status만), 나머지 상태는 전부 일시("Http"+status), 그 밖의 RestClientException(연결 실패·
     * 시간 초과·JSON 아닌 본문)은 일시(원인 타입 이름만). 응답 본문은 어디에도 옮기지 않는다.
     *
     * <p>치지직은 4xx 전부가 거부이고 예외 셋(429·408·INVALID_CLIENT)만 일시다. SOOP은 거꾸로 거부를 셋으로 좁혔다:
     * 토큰 주소의 오류 꼴이 문서에 없어 앱 설정 오류를 코드로 가를 수 없고, 거부는 갱신에서 BROKEN(되돌릴 수 없음)이
     * 되므로 모르는 4xx를 거부로 보내면 프록시·주소 오류 하나가 전 회원 연동을 닫는다(유튜브와 같은 판단).
     * 앱 설정 오류가 400·401로 오는 경우는 스케줄러의 연속 거절 브레이크가 막는다.
     */
    private static <T> T call(Supplier<T> action) {
        try {
            return action.get();
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 400 || status == 401 || status == 403) {
                throw new SoopRejectedException(status);
            }
            throw new SoopUnavailableException("Http" + status);
        } catch (RestClientException e) {
            throw new SoopUnavailableException(e.getClass().getSimpleName());
        }
    }
}
