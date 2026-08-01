package com.pokeclip.core.auth.google;

import com.pokeclip.core.auth.AuthException;
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

        assertThat(client.exchangeCodeForIdToken("auth-code-1")).isEqualTo("the-id-token");
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

        assertThatThrownBy(() -> client.exchangeCodeForIdToken("expired-code"))
                .isInstanceOf(AuthException.class);
    }

    @Test
    void 응답에_id_token이_없으면_인증_실패다() {
        server.expect(requestTo(TOKEN_URI))
                .andRespond(withSuccess("""
                        {"access_token":"only-access-token"}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.exchangeCodeForIdToken("code-without-idtoken"))
                .isInstanceOf(AuthException.class);
    }
}
