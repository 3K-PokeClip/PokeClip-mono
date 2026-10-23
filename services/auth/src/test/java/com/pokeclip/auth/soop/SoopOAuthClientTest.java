package com.pokeclip.auth.soop;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import com.pokeclip.auth.support.FakeSoopServer;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SoopOAuthClientTest {

    private static final String CLIENT_SECRET = "test-soop-client-secret";

    private FakeSoopServer soop;
    private SoopOAuthClient client;

    @BeforeEach
    void setUp() {
        soop = FakeSoopServer.start();
        client = new SoopOAuthClient(RestClient.builder(), props(soop.baseUrl()));
    }

    @AfterEach
    void tearDown() {
        soop.close();
    }

    // --- 교환·갱신 ---

    @Test
    void 교환은_form_본문으로_code와_앱_셋을_보내고_토큰을_읽는다() {
        SoopTokens t = client.exchange("code-1");

        assertThat(t.accessToken()).isEqualTo("at-1");
        assertThat(t.refreshToken()).isEqualTo("rt-1");
        assertThat(t.expiresIn()).isEqualTo(Duration.ofSeconds(28800));
        assertThat(t.scope()).isEqualTo("broad,station,chat");
        assertThat(soop.lastTokenContentType()).startsWith("application/x-www-form-urlencoded");
        assertThat(soop.tokenRequests().get(0))
                .containsEntry("grant_type", "authorization_code").containsEntry("code", "code-1")
                .containsEntry("client_id", "test-soop-client-id").containsEntry("client_secret", CLIENT_SECRET)
                .containsEntry("redirect_uri", "http://localhost:3000/oauth/soop/callback")
                .doesNotContainKey("refresh_token");
    }

    @Test
    void 갱신은_refresh_token_grant를_보낸다() {
        client.refresh("rt-old");

        assertThat(soop.tokenRequests().get(0))
                .containsEntry("grant_type", "refresh_token").containsEntry("refresh_token", "rt-old")
                .containsEntry("client_id", "test-soop-client-id").containsEntry("client_secret", CLIENT_SECRET)
                .doesNotContainKey("code");
    }

    /** 갱신 응답에 refresh_token이 없으면 null로 넘기고 호출부가 옛 값을 유지한다(null로 덮으면 연동이 죽는다). */
    @Test
    void 갱신_응답에_refresh_token이_없으면_null이다() {
        soop.tokenResponds(200, "{\"access_token\":\"at-new\",\"expires_in\":28800}");

        SoopTokens t = client.refresh("rt-old");

        assertThat(t.accessToken()).isEqualTo("at-new");
        assertThat(t.refreshToken()).isNull();
        assertThat(t.scope()).isNull();
    }

    /** 교환은 다르다: 처음 받는 refresh가 없으면 이 연동은 8시간 뒤 반드시 죽는다. 저장하지 않는다. */
    @Test
    void 교환_응답에_refresh_token이_없으면_Unavailable() {
        soop.tokenResponds(200, "{\"access_token\":\"at-new\",\"expires_in\":28800}");

        assertThatThrownBy(() -> client.exchange("c")).isInstanceOf(SoopUnavailableException.class)
                .satisfies(e -> assertThat(((SoopUnavailableException) e).causeType())
                        .isEqualTo("MalformedResponse field=refresh_token type=null"));
    }

    @Test
    void access_token이_없으면_Unavailable() {
        soop.tokenResponds(200, "{\"refresh_token\":\"r\",\"expires_in\":28800}");

        assertThatThrownBy(() -> client.refresh("rt")).isInstanceOf(SoopUnavailableException.class)
                .satisfies(e -> assertThat(((SoopUnavailableException) e).causeType())
                        .isEqualTo("MalformedResponse field=access_token type=null"));
    }

    @Test
    void expires_in은_정수도_숫자_문자열도_받는다() {
        soop.tokenResponds(200, "{\"access_token\":\"a\",\"refresh_token\":\"r\",\"expires_in\":\"3600\"}");

        assertThat(client.refresh("rt").expiresIn()).isEqualTo(Duration.ofSeconds(3600));
    }

    @Test
    void expires_in이_숫자가_아니면_타입만_남기고_Unavailable() {
        soop.tokenResponds(200, "{\"access_token\":\"a\",\"refresh_token\":\"r\",\"expires_in\":\"abc\"}");
        assertThatThrownBy(() -> client.refresh("rt")).isInstanceOf(SoopUnavailableException.class)
                .satisfies(e -> assertThat(((SoopUnavailableException) e).causeType())
                        .isEqualTo("MalformedResponse field=expires_in type=String"));

        soop.tokenResponds(200, "{\"access_token\":\"a\",\"refresh_token\":\"r\"}");
        assertThatThrownBy(() -> client.refresh("rt")).isInstanceOf(SoopUnavailableException.class)
                .satisfies(e -> assertThat(((SoopUnavailableException) e).causeType())
                        .isEqualTo("MalformedResponse field=expires_in type=null"));
    }

    // --- 분류 ---

    @Test
    void 응답_400_401_403은_Rejected로_상태만_품는다() {
        for (int status : new int[]{400, 401, 403}) {
            soop.tokenResponds(status, "{\"error\":\"invalid_grant\"}");
            assertThatThrownBy(() -> client.refresh("rt")).as("status %d", status)
                    .isInstanceOf(SoopRejectedException.class)
                    .satisfies(e -> assertThat(((SoopRejectedException) e).status()).isEqualTo(status));
        }
    }

    /**
     * 408·429·5xx는 일시다. 그 밖의 4xx(404·405·409 등)도 일시로 둔다: 토큰 주소의 오류 꼴이 문서에 없고,
     * 영구(BROKEN)로 닫으면 되돌릴 수 없어 프록시·주소 오류 하나가 전 회원 연동을 닫을 수 있다(유튜브와 같은 판단).
     */
    @Test
    void 응답_408_429_5xx_그_밖의_4xx는_Unavailable() {
        for (int status : new int[]{408, 429, 500, 502, 503, 404, 409}) {
            soop.tokenResponds(status, "{}");
            assertThatThrownBy(() -> client.refresh("rt")).as("status %d", status)
                    .isInstanceOf(SoopUnavailableException.class)
                    .satisfies(e -> assertThat(((SoopUnavailableException) e).causeType()).isEqualTo("Http" + status));
        }
    }

    @Test
    void 성공_응답인데_JSON이_아니면_Unavailable() {
        soop.tokenResponds(200, "<html>maintenance</html>");

        assertThatThrownBy(() -> client.refresh("rt")).isInstanceOf(SoopUnavailableException.class);
    }

    @Test
    void 연결이_안_되면_Unavailable() {
        FakeSoopServer closed = FakeSoopServer.start();
        String base = closed.baseUrl();
        closed.close();
        SoopOAuthClient dead = new SoopOAuthClient(RestClient.builder(), props(base));

        assertThatThrownBy(() -> dead.refresh("rt")).isInstanceOf(SoopUnavailableException.class);
    }

    /** 시한은 공용 spring.http.clients.*가 건다. 여기선 짧은 read timeout 팩토리로 「시간 초과도 일시」만 잰다. */
    @Test
    void 시간_초과는_Unavailable() {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
        factory.setReadTimeout(Duration.ofMillis(200));
        SoopOAuthClient impatient = new SoopOAuthClient(RestClient.builder().requestFactory(factory),
                props(soop.baseUrl()));
        soop.tokenDelays(Duration.ofSeconds(2));

        assertThatThrownBy(() -> impatient.refresh("rt")).isInstanceOf(SoopUnavailableException.class);
    }

    // --- 아이디·닉네임 ---

    @Test
    void 아이디와_닉네임을_두_주소에서_읽어_합치고_토큰은_form_본문에만_싣는다() {
        SoopIdentity id = client.fetchIdentity("at-x");

        assertThat(id.soopUserId()).isEqualTo(FakeSoopServer.DEFAULT_USER_ID);
        assertThat(id.nickname()).isEqualTo(FakeSoopServer.DEFAULT_NICK);
        assertThat(soop.statusRequests()).containsExactly(Map.of("access_token", "at-x"));
        assertThat(soop.stationRequests()).containsExactly(Map.of("access_token", "at-x"));
    }

    @Test
    void 아이디_주소의_result가_음수면_Rejected_200() {
        soop.statusResponds(200, "{\"result\":-1,\"msg\":\"invalid token\"}");

        assertThatThrownBy(() -> client.fetchIdentity("at")).isInstanceOf(SoopRejectedException.class)
                .satisfies(e -> assertThat(((SoopRejectedException) e).status()).isEqualTo(200));
        assertThat(soop.stationCalls()).as("아이디를 못 받았으면 닉네임을 묻지 않는다").isZero();
    }

    @Test
    void 닉네임_주소의_result가_음수면_Rejected_200() {
        soop.stationResponds(200, "{\"result\":-3,\"msg\":\"no permission\"}");

        assertThatThrownBy(() -> client.fetchIdentity("at")).isInstanceOf(SoopRejectedException.class)
                .satisfies(e -> assertThat(((SoopRejectedException) e).status()).isEqualTo(200));
    }

    /** 방송 중이면 방송 여부 체크가 user_id를 준다. 그것을 쓴다(방송국 정보 주소보다 먼저). */
    @Test
    void 방송_중이면_방송_여부_체크의_아이디를_쓴다() {
        soop.statusOnAir("onair-id");

        assertThat(client.fetchIdentity("at").soopUserId()).isEqualTo("onair-id");
    }

    /**
     * 🔴 실측(2026-10-23): 방송 중이 아니면 방송 여부 체크의 data가 {@code broad_no}·{@code status}뿐이라 아이디가 없다.
     * 그때는 방송국 정보 {@code profile_image}의 LOGO 꼴 주소에서 꺼낸다(두 자리가 같은 값일 때만). 빈 user_id도 없는 것과 같다.
     */
    @Test
    void 방송_중이_아니면_방송국_정보_주소에서_아이디를_꺼낸다() {
        assertThat(client.fetchIdentity("at").soopUserId()).isEqualTo(FakeSoopServer.DEFAULT_USER_ID);
        for (String data : new String[]{"{\"user_id\":\"\"}", "{\"user_id\":\"   \"}", "{\"broad_no\":\"0\"}"}) {
            soop.statusResponds(200, "{\"result\":1,\"data\":" + data + "}");
            assertThat(client.fetchIdentity("at").soopUserId()).as(data).isEqualTo(FakeSoopServer.DEFAULT_USER_ID);
        }
    }

    /**
     * 둘 다 안 되면 거부가 아니라 일시 장애다: 동의는 유효한데 우리가 누구인지 못 읽은 것이다. 거부로 보내면 화면이
     * 「동의부터 다시」(INVALID_CODE)를 그려 사용자가 헛수고한다. 원인은 타입 이름만 남긴다.
     */
    @Test
    void 아이디를_어디서도_못_읽으면_일시_장애다() {
        for (String image : new String[]{null, "https://x.test/plain.jpg",
                "https://x.test/LOGO/so/soop-a/soop-b.jpg", "https://x.test/LOGO/so/soop-a/soop-a.gif"}) {
            soop.stationResponds(200, FakeSoopServer.stationBody("닉", image));
            assertThatThrownBy(() -> client.fetchIdentity("at")).as(String.valueOf(image))
                    .isInstanceOf(SoopUnavailableException.class)
                    .satisfies(e -> assertThat(((SoopUnavailableException) e).causeType()).isEqualTo("IdentityUnavailable"));
        }
    }

    @Test
    void 닉네임이_문자열이_아니거나_result가_없으면_Unavailable() {
        soop.stationResponds(200, "{\"result\":1,\"data\":{\"user_nick\":7}}");
        assertThatThrownBy(() -> client.fetchIdentity("at")).isInstanceOf(SoopUnavailableException.class)
                .satisfies(e -> assertThat(((SoopUnavailableException) e).causeType())
                        .isEqualTo("MalformedResponse field=user_nick type=Integer"));

        soop.reset();
        soop.statusResponds(200, "{\"data\":{\"broad_no\":\"0\"}}");
        assertThatThrownBy(() -> client.fetchIdentity("at")).isInstanceOf(SoopUnavailableException.class)
                .satisfies(e -> assertThat(((SoopUnavailableException) e).causeType())
                        .isEqualTo("MalformedResponse field=result type=null"));
    }

    @Test
    void 아이디_주소가_401이면_Rejected_5xx면_Unavailable() {
        soop.statusResponds(401, "{}");
        assertThatThrownBy(() -> client.fetchIdentity("at")).isInstanceOf(SoopRejectedException.class);
        soop.statusResponds(503, "{}");
        assertThatThrownBy(() -> client.fetchIdentity("at")).isInstanceOf(SoopUnavailableException.class);
    }

    // --- 위생 ---

    /** 실패 경로 어디에도 토큰·code·앱 시크릿·응답 본문이 예외 메시지나 로그로 나가지 않는다. */
    @Test
    void 실패_경로가_비밀과_응답_본문을_남기지_않는다() {
        String bodyNeedle = "LEAK-soop-body-marker";
        try (LogCaptor captor = new LogCaptor()) {
            StringBuilder rendered = new StringBuilder();
            soop.tokenResponds(400, "{\"error\":\"" + bodyNeedle + "\"}");
            rendered.append(renderFully(catchThrowable(() -> client.exchange("LEAK-code-needle"))));
            soop.tokenResponds(503, "{\"error\":\"" + bodyNeedle + "\"}");
            rendered.append(renderFully(catchThrowable(() -> client.refresh("LEAK-refresh-needle"))));
            soop.tokenResponds(200, "{\"access_token\":\"LEAK-access-needle\",\"refresh_token\":\"x\",\"expires_in\":\"z\"}");
            rendered.append(renderFully(catchThrowable(() -> client.refresh("LEAK-refresh-needle"))));
            soop.statusResponds(200, "{\"result\":-1,\"msg\":\"" + bodyNeedle + "\"}");
            rendered.append(renderFully(catchThrowable(() -> client.fetchIdentity("LEAK-access-needle"))));

            StringBuilder logs = new StringBuilder();
            for (ILoggingEvent e : captor.events()) {
                logs.append(e.getFormattedMessage()).append('\n');
                for (IThrowableProxy t = e.getThrowableProxy(); t != null; t = t.getCause()) {
                    logs.append(t.getMessage()).append('\n');
                }
            }
            String all = rendered + "\n" + logs;
            assertThat(rendered).as("예외가 하나도 안 잡혔다").isNotEmpty();
            assertThat(all).doesNotContain("LEAK-code-needle", "LEAK-refresh-needle", "LEAK-access-needle",
                    CLIENT_SECRET, bodyNeedle);
        }
    }

    private static Throwable catchThrowable(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            return e;
        }
        throw new AssertionError("예외가 나야 했다");
    }

    private static String renderFully(Throwable e) {
        StringBuilder sb = new StringBuilder();
        for (Throwable t = e; t != null; t = t.getCause()) {
            sb.append(t.getClass().getName()).append(": ").append(t.getMessage()).append('\n');
        }
        return sb.toString();
    }

    static SoopProperties props(String baseUrl) {
        return new SoopProperties(
                new SoopProperties.App("test-soop-client-id", CLIENT_SECRET, "http://localhost:3000/oauth/soop/callback"),
                "https://openapi.sooplive.com/auth/code", baseUrl,
                Duration.ofMinutes(10), Duration.ofHours(2), Duration.ofHours(1),
                new SoopProperties.Check(false, Duration.ofHours(1), Duration.ofHours(24), 3, 25));
    }
}
