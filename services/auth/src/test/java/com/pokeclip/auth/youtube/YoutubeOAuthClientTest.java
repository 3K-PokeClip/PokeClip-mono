package com.pokeclip.auth.youtube;

import com.pokeclip.auth.support.FakeYoutubeServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class YoutubeOAuthClientTest {

    private FakeYoutubeServer google;
    private YoutubeOAuthClient client;

    @BeforeEach
    void setUp() {
        google = FakeYoutubeServer.start();
        client = new YoutubeOAuthClient(RestClient.builder(), props(google));
    }

    @AfterEach
    void tearDown() {
        google.close();
    }

    // --- 요청 모양 -------------------------------------------------------

    /** 치지직은 JSON camelCase였다. 구글은 form urlencoded에 snake_case다 — 한 글자만 틀려도 invalid_request다. */
    @Test
    void 교환은_form_본문으로_보내고_최상위에서_읽는다() {
        YoutubeTokens t = client.exchange("code-1");

        assertThat(t.accessToken()).isEqualTo("at-1");
        assertThat(t.refreshToken()).isEqualTo("rt-1");
        assertThat(t.expiresIn()).isEqualTo(Duration.ofSeconds(3600));
        assertThat(t.scope()).contains("youtube.upload");

        Map<String, String> sent = google.tokenRequests().get(0);
        assertThat(sent)
                .containsEntry("grant_type", "authorization_code")
                .containsEntry("code", "code-1")
                .containsEntry("client_id", "ycid")
                .containsEntry("client_secret", "ycsecret")
                .containsEntry("redirect_uri", "http://localhost:8081/oauth/youtube/callback");
        assertThat(google.lastTokenContentType()).startsWith("application/x-www-form-urlencoded");
    }

    @Test
    void 갱신은_refresh_token_grant를_form으로_보낸다() {
        client.refresh("rt-old");

        assertThat(google.tokenRequests().get(0))
                .containsEntry("grant_type", "refresh_token")
                .containsEntry("refresh_token", "rt-old")
                .containsEntry("client_id", "ycid")
                .containsEntry("client_secret", "ycsecret")
                .doesNotContainKey("code");
    }

    /** 구글 revoke에는 tokenTypeHint가 없다 — token 하나뿐이다. */
    @Test
    void revoke는_token만_보낸다() {
        client.revoke("rt-1");

        assertThat(google.revokedTokens()).containsExactly("rt-1");
        assertThat(google.lastRevokeRequest()).containsOnlyKeys("token");
    }

    @Test
    void 채널_목록은_Bearer로_묻고_경로와_쿼리를_지킨다() {
        List<YoutubeChannel> channels = client.listChannels("at-x");

        assertThat(channels).containsExactly(new YoutubeChannel("chan-default", "채널"));
        assertThat(google.lastChannelsBearer()).isEqualTo("Bearer at-x");
        assertThat(google.lastChannelsQuery()).contains("part=snippet").contains("mine=true");
    }

    // --- 오류 분류 -------------------------------------------------------

    @Test
    void 응답_4xx는_Rejected_5xx는_Unavailable로_갈린다() {
        google.tokenResponds(400, "{\"error\":\"invalid_grant\"}");
        assertThatThrownBy(() -> client.refresh("rt")).isInstanceOf(YoutubeRejectedException.class)
                .satisfies(e -> assertThat(((YoutubeRejectedException) e).status()).isEqualTo(400));

        google.tokenResponds(503, "{}");
        assertThatThrownBy(() -> client.refresh("rt")).isInstanceOf(YoutubeUnavailableException.class)
                .satisfies(e -> assertThat(((YoutubeUnavailableException) e).causeType()).isEqualTo("Http503"));
    }

    /** 앱 자격증명 오류는 우리 설정 문제다 — 영구로 닫으면 회원 전원이 재동의해야 한다. */
    @Test
    void 응답_4xx라도_invalid_client면_일시_장애로_본다() {
        google.tokenResponds(401, "{\"error\":\"invalid_client\"}");
        assertThatThrownBy(() -> client.refresh("rt")).isInstanceOf(YoutubeUnavailableException.class)
                .satisfies(e -> assertThat(((YoutubeUnavailableException) e).causeType()).isEqualTo("InvalidClient"));
    }

    /**
     * 🔴 <b>갱신은 화이트리스트다</b> — 영구는 {@code invalid_grant} 하나뿐이고, 본문이 JSON이 아니거나
     * {@code error}가 없거나 모르는 코드면 <b>전부 일시</b>다. 갱신의 영구 판정은 행을 되돌릴 수 없게 닫는데,
     * 철회 점검이 하루 한 번 전 회원을 훑으므로 모르는 오류 하나가 <b>전 회원을 한꺼번에 닫을 수 있다</b>
     * (봇 리뷰 2판). causeType에 응답 값을 옮기지 않는 것도 함께 잰다.
     */
    @Test
    void 갱신은_invalid_grant만_거부고_나머지_4xx는_일시다() {
        google.tokenResponds(400, "{\"error\":\"invalid_grant\"}");
        assertThatThrownBy(() -> client.refresh("rt")).isInstanceOf(YoutubeRejectedException.class);

        for (String body : new String[]{"not json", "{\"error_description\":\"x\"}",
                "<html>404</html>", "{\"error\":\"brand_new_code\"}", ""}) {
            google.tokenResponds(401, body);
            assertThatThrownBy(() -> client.refresh("rt"))
                    .as("본문 %s — 모르면 일시여야 한다", body)
                    .isInstanceOf(YoutubeUnavailableException.class)
                    .satisfies(e -> assertThat(((YoutubeUnavailableException) e).causeType())
                            .doesNotContain("brand_new_code").doesNotContain("html"));
        }
    }

    /**
     * 🔴 <b>교환은 반대다(블랙리스트).</b> 모르는 4xx를 일시로 돌리면 사용자가 <b>영영 재동의를 안내받지 못하고</b>
     * 502만 반복해서 본다. 그 경로의 영구 판정은 「동의부터 다시」라는 복구 가능한 안내라 손해가 되돌려진다.
     * 두 경로가 갈리지 않으면 정책 분리가 아무것도 안 하는 것이다.
     */
    /**
     * 🔴 403 할당량·속도 계열은 <b>전부</b> 일시다. codex는 {@code dailyLimitExceeded} 하나를 짚었지만
     * 전수로 세니 <b>셋</b>이 빠져 있었다(봇 3판 P2-3) — 「같은 뿌리, 한 자리만」이 또 났던 자리다.
     * 이 검사는 목록이 아니라 <b>규칙</b>을 지킨다: 「할당량·속도로 막힌 것은 시간이 지나면 풀린다 = 영구가 아니다」.
     */
    @org.junit.jupiter.params.ParameterizedTest(name = "[{index}] {0}")
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "quotaExceeded", "dailyLimitExceeded", "rateLimitExceeded",
            "userRateLimitExceeded", "servingLimitExceeded", "dailyLimitExceededUnreg"})
    void 할당량_속도_계열_403은_전부_일시다(String reason) {
        google.channelsResponds(403, "{\"error\":{\"code\":403,\"errors\":[{\"reason\":\"" + reason + "\"}]}}");

        assertThatThrownBy(() -> client.listChannels("at-x"))
                .as("%s를 영구로 닫으면 사용자가 재동의해도 안 풀린다", reason)
                .isInstanceOf(YoutubeUnavailableException.class);
    }

    /** 대조군 — 할당량이 아닌 403(권한 부족)은 영구다. 갈리지 않으면 「전부 일시」가 규칙이 아니라 실수가 된다. */
    @Test
    void 권한_부족_403은_영구다() {
        google.channelsResponds(403, "{\"error\":{\"code\":403,\"errors\":[{\"reason\":\"insufficientPermissions\"}]}}");

        assertThatThrownBy(() -> client.listChannels("at-x")).isInstanceOf(YoutubeRejectedException.class);
    }

    @Test
    void 교환은_모르는_4xx를_거부로_본다() {
        google.tokenResponds(401, "not json");
        assertThatThrownBy(() -> client.exchange("code")).isInstanceOf(YoutubeRejectedException.class);

        google.tokenResponds(401, "{\"error\":\"brand_new_code\"}");
        assertThatThrownBy(() -> client.exchange("code")).isInstanceOf(YoutubeRejectedException.class);
    }

    @Test
    void 응답_429와_408은_거부가_아니라_일시_장애로_본다() {
        google.tokenResponds(429, "{\"error\":\"rate_limit\"}");
        assertThatThrownBy(() -> client.refresh("rt")).isInstanceOf(YoutubeUnavailableException.class)
                .satisfies(e -> assertThat(((YoutubeUnavailableException) e).causeType()).isEqualTo("Http429"));

        google.tokenResponds(408, "{}");
        assertThatThrownBy(() -> client.refresh("rt")).isInstanceOf(YoutubeUnavailableException.class)
                .satisfies(e -> assertThat(((YoutubeUnavailableException) e).causeType()).isEqualTo("Http408"));
    }

    /**
     * YouTube Data API는 일 10,000유닛을 다 쓰면 <b>403</b>을 준다. 태평양시 자정에 리셋되는 일시 상태인데
     * 403을 영구 거절로 보면 그날 연동 시도가 전부 400이 되고 받은 토큰까지 버려진다(계획 검증 중대-3).
     */
    @Test
    void 유튜브_API의_403_할당량_오류는_일시_장애로_본다() {
        for (Map.Entry<String, String> each : Map.of(
                "quotaExceeded", "QuotaExceeded",
                "rateLimitExceeded", "RateLimitExceeded",
                "userRateLimitExceeded", "UserRateLimitExceeded").entrySet()) {
            google.channelsResponds(403, "{\"error\":{\"code\":403,\"errors\":[{\"reason\":\"" + each.getKey() + "\"}]}}");
            assertThatThrownBy(() -> client.listChannels("at"))
                    .as("403 %s", each.getKey())
                    .isInstanceOf(YoutubeUnavailableException.class)
                    .satisfies(e -> assertThat(((YoutubeUnavailableException) e).causeType()).isEqualTo(each.getValue()));
        }

        // 같은 403이라도 다른 사유(권한 부족)는 영구 거절이다.
        google.channelsResponds(403, "{\"error\":{\"code\":403,\"errors\":[{\"reason\":\"forbidden\"}]}}");
        assertThatThrownBy(() -> client.listChannels("at")).isInstanceOf(YoutubeRejectedException.class);
    }

    /**
     * 오류 본문 형식이 주소마다 다르다 — 토큰·철회는 {@code "error":"코드"}(문자열),
     * YouTube Data API는 {@code "error":{...,"errors":[{"reason":"코드"}]}}(객체).
     * 한쪽만 읽으면 다른 쪽의 특례가 영영 발동하지 않는다(계획 검증 중대-4).
     */
    @Test
    void 오류_코드는_문자열_error와_객체_errors_둘_다에서_읽힌다() {
        google.tokenResponds(400, "{\"error\":\"invalid_client\"}");
        assertThatThrownBy(() -> client.refresh("rt"))
                .satisfies(e -> assertThat(((YoutubeUnavailableException) e).causeType()).isEqualTo("InvalidClient"));

        google.tokenResponds(400, "{\"error\":{\"code\":400,\"errors\":[{\"reason\":\"invalid_client\"}]}}");
        assertThatThrownBy(() -> client.refresh("rt"))
                .satisfies(e -> assertThat(((YoutubeUnavailableException) e).causeType()).isEqualTo("InvalidClient"));
    }

    /** 읽기 타임아웃은 응답이 없는 것이지 거절이 아니다 — 재시도할 수 있어야 한다. */
    @Test
    void 응답이_늦으면_Unavailable로_본다() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setReadTimeout(Duration.ofMillis(200));
        YoutubeOAuthClient impatient =
                new YoutubeOAuthClient(RestClient.builder().requestFactory(factory), props(google));
        google.tokenDelays(Duration.ofSeconds(2));

        assertThatThrownBy(() -> impatient.refresh("rt")).isInstanceOf(YoutubeUnavailableException.class);
    }

    // --- 본문 파싱 -------------------------------------------------------

    @Test
    void expires_in은_정수도_숫자_문자열도_받는다() {
        assertThat(client.refresh("rt").expiresIn()).isEqualTo(Duration.ofSeconds(3600));

        google.tokenResponds(200, "{\"access_token\":\"a\",\"expires_in\":\"1800\"}");
        assertThat(client.refresh("rt").expiresIn()).isEqualTo(Duration.ofSeconds(1800));
    }

    @Test
    void expires_in이_숫자가_아니면_Unavailable로_본다() {
        google.tokenResponds(200, "{\"access_token\":\"a\",\"refresh_token\":\"r\",\"expires_in\":\"abc\"}");
        assertThatThrownBy(() -> client.refresh("rt")).isInstanceOf(YoutubeUnavailableException.class)
                .satisfies(e -> assertThat(((YoutubeUnavailableException) e).causeType())
                        .isEqualTo("MalformedResponse field=expires_in type=String"));

        google.tokenResponds(200, "{\"access_token\":\"a\",\"refresh_token\":\"r\",\"expires_in\":true}");
        assertThatThrownBy(() -> client.refresh("rt")).isInstanceOf(YoutubeUnavailableException.class)
                .satisfies(e -> assertThat(((YoutubeUnavailableException) e).causeType())
                        .isEqualTo("MalformedResponse field=expires_in type=Boolean"));
    }

    @Test
    void access_token이_없으면_Unavailable로_본다() {
        google.tokenResponds(200, "{\"expires_in\":3600}");
        assertThatThrownBy(() -> client.refresh("rt")).isInstanceOf(YoutubeUnavailableException.class)
                .satisfies(e -> assertThat(((YoutubeUnavailableException) e).causeType())
                        .isEqualTo("MalformedResponse field=access_token type=null"));
    }

    /**
     * 교환에 refresh_token이 없으면 갱신할 수 없는 반쪽 연동이라 실패로 다룬다. 그런데 access는 <b>이미 발급됐다</b> —
     * 예외에 실어 보내야 호출부가 버릴 수 있다. 안 실으면 물리적으로 못 버린다(계획 검증 중대-6).
     */
    @Test
    void 교환_응답에_refresh_token이_없으면_받은_access가_예외에_실려_온다() {
        google.tokenResponds(200, "{\"access_token\":\"at-orphan\",\"expires_in\":3600,\"scope\":\"s\"}");

        assertThatThrownBy(() -> client.exchange("code"))
                .isInstanceOf(YoutubeUnavailableException.class)
                .satisfies(e -> {
                    YoutubeUnavailableException u = (YoutubeUnavailableException) e;
                    assertThat(u.causeType()).isEqualTo("MalformedResponse field=refresh_token type=null");
                    assertThat(u.issuedTokens()).isPresent();
                    assertThat(u.issuedTokens().get().accessToken()).isEqualTo("at-orphan");
                    assertThat(u.issuedTokens().get().refreshToken()).isNull();
                    assertThat(renderFully(e)).as("토큰 원문이 메시지에 실렸다").doesNotContain("at-orphan");
                });
    }

    /** 갱신 응답에 refresh_token이 없는 것은 <b>정상</b>이다 — 기존 것을 계속 쓴다. */
    @Test
    void 갱신_응답에_refresh_token이_없으면_null로_돌려준다() {
        google.tokenResponds(200, "{\"access_token\":\"at-new\",\"expires_in\":3600}");

        YoutubeTokens t = client.refresh("rt-old");
        assertThat(t.accessToken()).isEqualTo("at-new");
        assertThat(t.refreshToken()).isNull();
        assertThat(t.scope()).isNull();
    }

    // --- 채널 목록 -------------------------------------------------------

    @Test
    void 채널_목록을_여러_개_읽는다() {
        google.channelsResponds(200, "{\"items\":["
                + "{\"id\":\"UC-a\",\"snippet\":{\"title\":\"가\"}},"
                + "{\"id\":\"UC-b\",\"snippet\":{\"title\":\"나\"}}]}");

        assertThat(client.listChannels("at"))
                .containsExactly(new YoutubeChannel("UC-a", "가"), new YoutubeChannel("UC-b", "나"));
    }

    @Test
    void items가_빈_배열이면_빈_목록이다() {
        google.channelsResponds(200, "{\"items\":[],\"pageInfo\":{\"totalResults\":0}}");
        assertThat(client.listChannels("at")).isEmpty();
    }

    /**
     * 채널이 없는 계정은 {@code items} 키를 아예 생략할 수 있다. 이것을 형식 붕괴(502)로 보면
     * 「채널을 먼저 만드세요」(400) 대신 재시도 안내가 나가 사용자가 원인을 못 본다(계획 검증 중대-5).
     */
    @Test
    void items_키가_없어도_빈_목록이다() {
        google.channelsResponds(200, "{\"kind\":\"youtube#channelListResponse\",\"pageInfo\":{\"totalResults\":0}}");
        assertThat(client.listChannels("at")).isEmpty();
    }

    @Test
    void items가_배열이_아니면_Unavailable로_본다() {
        google.channelsResponds(200, "{\"items\":{\"id\":\"UC-a\"}}");
        assertThatThrownBy(() -> client.listChannels("at")).isInstanceOf(YoutubeUnavailableException.class)
                .satisfies(e -> assertThat(((YoutubeUnavailableException) e).causeType())
                        .isEqualTo("MalformedResponse field=items type=LinkedHashMap"));
    }

    @Test
    void 채널_항목의_id나_제목이_문자열이_아니면_Unavailable로_본다() {
        google.channelsResponds(200, "{\"items\":[{\"snippet\":{\"title\":\"가\"}}]}");
        assertThatThrownBy(() -> client.listChannels("at")).isInstanceOf(YoutubeUnavailableException.class)
                .satisfies(e -> assertThat(((YoutubeUnavailableException) e).causeType())
                        .isEqualTo("MalformedResponse field=items[].id type=null"));

        // 진단이 어느 자리에서 깨졌는지까지 가른다 — snippet 자체가 없는 것과 title만 없는 것은 다른 사건이다.
        google.channelsResponds(200, "{\"items\":[{\"id\":\"UC-a\"}]}");
        assertThatThrownBy(() -> client.listChannels("at")).isInstanceOf(YoutubeUnavailableException.class)
                .satisfies(e -> assertThat(((YoutubeUnavailableException) e).causeType())
                        .isEqualTo("MalformedResponse field=items[].snippet type=null"));

        google.channelsResponds(200, "{\"items\":[{\"id\":\"UC-a\",\"snippet\":{\"description\":\"x\"}}]}");
        assertThatThrownBy(() -> client.listChannels("at")).isInstanceOf(YoutubeUnavailableException.class)
                .satisfies(e -> assertThat(((YoutubeUnavailableException) e).causeType())
                        .isEqualTo("MalformedResponse field=items[].snippet.title type=null"));

        google.channelsResponds(200, "{\"items\":[\"UC-a\"]}");
        assertThatThrownBy(() -> client.listChannels("at")).isInstanceOf(YoutubeUnavailableException.class)
                .satisfies(e -> assertThat(((YoutubeUnavailableException) e).causeType())
                        .isEqualTo("MalformedResponse field=items[] type=String"));
    }

    // --- 유출 · 가짜 서버 계약 -------------------------------------------

    /** {@code RestClientResponseException.getMessage()}에는 응답 본문이 붙는다 — cause로도 옮기면 안 된다. */
    @Test
    void 예외_메시지에_응답_본문이_없다() {
        google.tokenResponds(400, "{\"error\":\"invalid_grant\",\"error_description\":\"LEAK-body-marker\"}");
        assertThatThrownBy(() -> client.exchange("c"))
                .satisfies(e -> assertThat(renderFully(e)).doesNotContain("LEAK-body-marker"));

        google.tokenResponds(503, "{\"error_description\":\"LEAK-body-marker\"}");
        assertThatThrownBy(() -> client.exchange("c"))
                .satisfies(e -> assertThat(renderFully(e)).doesNotContain("LEAK-body-marker"));

        google.channelsResponds(500, "{\"error\":{\"message\":\"LEAK-body-marker\"}}");
        assertThatThrownBy(() -> client.listChannels("at"))
                .satisfies(e -> assertThat(renderFully(e)).doesNotContain("LEAK-body-marker"));
    }

    /**
     * 가짜 서버의 캐스케이드 모드가 실물처럼 도는지 여기서 잰다 — 구글 revoke는 「그 쌍」이 아니라
     * 그 사용자가 이 프로젝트에 준 동의 전부를 무효화한다. 태스크 6·7의 재연동 검사가 이 모드에 기댄다.
     */
    @Test
    void 캐스케이드_모드를_켜면_revoke_뒤의_갱신이_invalid_grant다() {
        google.cascadeOnRevoke(true);
        assertThat(client.refresh("rt-1").accessToken()).isNotNull();

        client.revoke("rt-1");

        assertThatThrownBy(() -> client.refresh("rt-1")).isInstanceOf(YoutubeRejectedException.class)
                .satisfies(e -> assertThat(((YoutubeRejectedException) e).status()).isEqualTo(400));
        // 교환(새 동의)은 여전히 된다 — 새 동의가 옛 grant를 대체하는 실물 거동.
        assertThat(client.exchange("code-new").refreshToken()).isNotNull();
    }

    /** 메시지 + cause 체인의 메시지를 전부 이어 붙인다. */
    private static String renderFully(Throwable e) {
        StringBuilder sb = new StringBuilder();
        for (Throwable t = e; t != null; t = t.getCause()) {
            sb.append(t.getClass().getName()).append(": ").append(t.getMessage()).append('\n');
        }
        return sb.toString();
    }

    private static YoutubeProperties props(FakeYoutubeServer google) {
        return new YoutubeProperties(
                new YoutubeProperties.App("ycid", "ycsecret", "http://localhost:8081/oauth/youtube/callback"),
                "https://accounts.google.com/o/oauth2/v2/auth",
                google.tokenUri(), google.revokeUri(), google.baseUrl(),
                Duration.ofMinutes(10), Duration.ofMinutes(30),
                new YoutubeProperties.Check(false, Duration.ofHours(1), Duration.ofHours(24)));
    }
}
