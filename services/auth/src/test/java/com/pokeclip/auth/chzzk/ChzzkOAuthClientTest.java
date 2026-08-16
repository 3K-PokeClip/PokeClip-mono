package com.pokeclip.auth.chzzk;

import com.pokeclip.auth.support.FakeChzzkServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChzzkOAuthClientTest {

    private FakeChzzkServer chzzk;
    private ChzzkOAuthClient client;

    @BeforeEach
    void setUp() {
        chzzk = FakeChzzkServer.start();
        client = new ChzzkOAuthClient(RestClient.builder(), props(chzzk.baseUrl()));
    }

    @AfterEach
    void tearDown() {
        chzzk.close();
    }

    @Test
    void 교환은_JSON_본문으로_code와_state를_보내고_content를_읽는다() {
        ChzzkTokens t = client.exchange("code-1", "state-1");
        assertThat(t.accessToken()).isEqualTo("at-1");
        assertThat(t.refreshToken()).isEqualTo("rt-1");
        assertThat(t.expiresIn()).isEqualTo(Duration.ofSeconds(86400));
        Map<String, String> sent = chzzk.tokenRequests().get(0);
        assertThat(sent).containsEntry("grantType", "authorization_code").containsEntry("code", "code-1")
                .containsEntry("state", "state-1").containsEntry("clientId", "cid").containsEntry("clientSecret", "csecret");
    }

    @Test
    void 갱신은_refresh_token_grant를_보낸다() {
        client.refresh("rt-old");
        assertThat(chzzk.tokenRequests().get(0)).containsEntry("grantType", "refresh_token")
                .containsEntry("refreshToken", "rt-old").doesNotContainKey("code");
    }

    @Test
    void me는_Bearer_헤더로_묻고_채널을_읽는다() {
        ChzzkMe me = client.fetchMe("at-x");
        assertThat(me.channelId()).isEqualTo("chan-default");
        assertThat(me.channelName()).isEqualTo("채널");
        assertThat(chzzk.lastMeBearer()).isEqualTo("Bearer at-x");
    }

    @Test
    void revoke는_token과_hint를_보낸다() {
        client.revoke("rt-1", "refresh_token");
        assertThat(chzzk.revokedTokens()).containsExactly("rt-1");
    }

    @Test
    void 응답_4xx는_Rejected_5xx는_Unavailable로_갈린다() {
        chzzk.tokenResponds(401, "{\"code\":401,\"message\":\"INVALID_TOKEN\"}");
        assertThatThrownBy(() -> client.refresh("rt")).isInstanceOf(ChzzkRejectedException.class)
                .satisfies(e -> assertThat(((ChzzkRejectedException) e).status()).isEqualTo(401));
        chzzk.tokenResponds(503, "{}");
        assertThatThrownBy(() -> client.refresh("rt")).isInstanceOf(ChzzkUnavailableException.class);
    }

    /** 429·408은 4xx지만 "이 토큰이 무효"가 아니라 일시 상태다 — 영구(REFRESH_REJECTED·INVALID_CODE)로 오분류하면 안 된다. */
    @Test
    void 응답_429와_408은_거부가_아니라_일시_장애로_본다() {
        chzzk.tokenResponds(429, "{\"code\":429,\"message\":\"TOO_MANY_REQUESTS\"}");
        assertThatThrownBy(() -> client.refresh("rt")).isInstanceOf(ChzzkUnavailableException.class)
                .satisfies(e -> assertThat(((ChzzkUnavailableException) e).causeType()).isEqualTo("Http429"));
        chzzk.tokenResponds(408, "{}");
        assertThatThrownBy(() -> client.refresh("rt")).isInstanceOf(ChzzkUnavailableException.class)
                .satisfies(e -> assertThat(((ChzzkUnavailableException) e).causeType()).isEqualTo("Http408"));
    }

    /** RestClientResponseException.getMessage()에는 응답 본문이 붙는다 — cause로도 옮기면 안 된다. */
    @Test
    void 예외_메시지에_응답_본문이_없다() {
        chzzk.tokenResponds(400, "{\"message\":\"LEAK-body-marker\"}");
        assertThatThrownBy(() -> client.exchange("c", "s"))
                .satisfies(e -> assertThat(renderFully(e)).doesNotContain("LEAK-body-marker"));
        chzzk.tokenResponds(503, "{\"message\":\"LEAK-body-marker\"}");
        assertThatThrownBy(() -> client.exchange("c", "s"))
                .satisfies(e -> assertThat(renderFully(e)).doesNotContain("LEAK-body-marker"));
    }

    @Test
    void content가_없으면_Unavailable로_본다() {
        chzzk.tokenResponds(200, "{\"code\":200}");
        assertThatThrownBy(() -> client.refresh("rt")).isInstanceOf(ChzzkUnavailableException.class);
    }

    /** 실물은 정수(가짜 서버 기본값), 공식 문서 표는 String — 둘 다 받는다. 문자열만 받다가 실 왕복이 502로 끝났다(2026-08-17). */
    @Test
    void expiresIn은_정수도_숫자_문자열도_받는다() {
        ChzzkTokens fromInt = client.refresh("rt");
        assertThat(fromInt.expiresIn()).isEqualTo(Duration.ofSeconds(86400));
        chzzk.tokenResponds(200, "{\"code\":200,\"content\":{\"accessToken\":\"a\",\"refreshToken\":\"r\",\"expiresIn\":\"3600\"}}");
        ChzzkTokens fromString = client.refresh("rt");
        assertThat(fromString.expiresIn()).isEqualTo(Duration.ofSeconds(3600));
    }

    @Test
    void expiresIn이_숫자가_아니면_Unavailable로_본다() {
        chzzk.tokenResponds(200, "{\"code\":200,\"content\":{\"accessToken\":\"a\",\"refreshToken\":\"r\",\"expiresIn\":\"abc\"}}");
        assertThatThrownBy(() -> client.refresh("rt")).isInstanceOf(ChzzkUnavailableException.class)
                .satisfies(e -> assertThat(((ChzzkUnavailableException) e).causeType())
                        .isEqualTo("MalformedResponse field=expiresIn type=String"));
        // 그 외 타입(불리언·객체)은 어느 필드가 어떤 타입이었는지만 남긴다 — 값은 없다.
        chzzk.tokenResponds(200, "{\"code\":200,\"content\":{\"accessToken\":\"a\",\"refreshToken\":\"r\",\"expiresIn\":true}}");
        assertThatThrownBy(() -> client.refresh("rt")).isInstanceOf(ChzzkUnavailableException.class)
                .satisfies(e -> assertThat(((ChzzkUnavailableException) e).causeType())
                        .isEqualTo("MalformedResponse field=expiresIn type=Boolean"));
    }

    /** 메시지 + cause 체인의 메시지를 전부 이어 붙인다. */
    private static String renderFully(Throwable e) {
        StringBuilder sb = new StringBuilder();
        for (Throwable t = e; t != null; t = t.getCause()) {
            sb.append(t.getClass().getName()).append(": ").append(t.getMessage()).append('\n');
        }
        return sb.toString();
    }

    private static ChzzkProperties props(String baseUrl) {
        return new ChzzkProperties(
                new ChzzkProperties.App("cid", "csecret", "http://localhost:8081/oauth/chzzk/callback"),
                "https://chzzk.naver.com/account-interlock", baseUrl, Duration.ofMinutes(10), Duration.ofHours(6),
                Duration.ofHours(12), new ChzzkProperties.Refresh(false, Duration.ofMinutes(10)));
    }
}
