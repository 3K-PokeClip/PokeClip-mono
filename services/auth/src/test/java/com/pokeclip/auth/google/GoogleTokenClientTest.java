package com.pokeclip.auth.google;

import com.pokeclip.auth.AuthException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GoogleTokenClientTest {

    private static final String TOKEN_URI = "https://oauth2.googleapis.com/token";

    /** RFC 7636 부록 B의 verifier. 형식은 요청 DTO(GoogleLoginRequest)가 이미 걸렀다는 전제다. */
    private static final String CODE_VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";

    GoogleAuthProperties properties;
    MockRestServiceServer server;
    GoogleTokenClient client;

    @BeforeEach
    void setUp() {
        properties = new GoogleAuthProperties("client-id", "client-secret",
                "http://localhost:3000/auth/callback", TOKEN_URI,
                "http://localhost/unused");

        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new GoogleTokenClient(builder, properties);
    }

    @Test
    void 인가_코드를_보내면_id_token을_받는다() {
        server.expect(requestTo(TOKEN_URI))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("code=auth-code-1")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("grant_type=authorization_code")))
                .andRespond(withSuccess("""
                        {"access_token":"ignored","id_token":"the-id-token","expires_in":3599}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.exchangeCodeForIdToken("auth-code-1", CODE_VERIFIER))
                .isEqualTo("the-id-token");
        server.verify();
    }

    /** PKCE(POK-483) — 동의 URL에 실린 challenge의 원문을 구글이 대조한다. 빠지면 구글이 교환을 거부한다. */
    @Test
    void 교환_요청에_code_verifier가_실린다() {
        server.expect(requestTo(TOKEN_URI))
                .andExpect(content().string(
                        org.hamcrest.Matchers.containsString("code_verifier=" + CODE_VERIFIER)))
                .andRespond(withSuccess("""
                        {"id_token":"the-id-token"}
                        """, MediaType.APPLICATION_JSON));

        client.exchangeCodeForIdToken("auth-code-1", CODE_VERIFIER);
        server.verify();
    }

    /**
     * 거부·허용의 스위치는 GoogleLoginRequest의 @NotBlank 하나다. 그것을 떼어 과도기 허용으로
     * 바꿨을 때 빈 code_verifier가 실려 구글이 거부하지 않도록, null이면 아예 싣지 않는다.
     */
    @Test
    void verifier가_null이면_code_verifier를_싣지_않는다() {
        server.expect(requestTo(TOKEN_URI))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("code_verifier"))))
                .andRespond(withSuccess("""
                        {"id_token":"the-id-token"}
                        """, MediaType.APPLICATION_JSON));

        client.exchangeCodeForIdToken("auth-code-1", null);
        server.verify();
    }

    @Test
    void 구글이_코드를_거부하면_인증_실패다() {
        server.expect(requestTo(TOKEN_URI))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .body("""
                              {"error":"invalid_grant"}
                              """)
                        .contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.exchangeCodeForIdToken("expired-code", CODE_VERIFIER))
                .isInstanceOf(AuthException.class);
    }

    @Test
    void 응답에_id_token이_없으면_인증_실패다() {
        server.expect(requestTo(TOKEN_URI))
                .andRespond(withSuccess("""
                        {"access_token":"only-access-token"}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(
                () -> client.exchangeCodeForIdToken("code-without-idtoken", CODE_VERIFIER))
                .isInstanceOf(AuthException.class);
    }
}
