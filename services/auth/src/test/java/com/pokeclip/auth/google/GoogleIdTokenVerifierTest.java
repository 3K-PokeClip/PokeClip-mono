package com.pokeclip.auth.google;

import com.pokeclip.auth.AuthException;
import com.pokeclip.auth.support.TestJwtFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GoogleIdTokenVerifierTest {

    private static final String ISSUER = "https://accounts.google.com";
    private static final String CLIENT_ID = "test-client-id";

    TestJwtFactory factory;
    GoogleIdTokenVerifier verifier;

    @BeforeEach
    void setUp() throws Exception {
        factory = new TestJwtFactory();
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(factory.publicKey()).build();
        verifier = new GoogleIdTokenVerifier(decoder, CLIENT_ID);
    }

    @Test
    void 정상_id_token에서_구글_사용자를_꺼낸다() throws Exception {
        String token = factory.idToken(ISSUER, CLIENT_ID, "sub-1",
                "a@example.com", "김태현", "https://img/1.png",
                Instant.now().plus(Duration.ofMinutes(5)));

        GoogleUser user = verifier.verify(token);

        assertThat(user.sub()).isEqualTo("sub-1");
        assertThat(user.email()).isEqualTo("a@example.com");
        assertThat(user.name()).isEqualTo("김태현");
        assertThat(user.profileImageUrl()).isEqualTo("https://img/1.png");
    }

    @Test
    void 우리_클라이언트가_아닌_audience는_거부한다() throws Exception {
        String token = factory.idToken(ISSUER, "someone-elses-client-id", "sub-1",
                "a@example.com", "김태현", null,
                Instant.now().plus(Duration.ofMinutes(5)));

        assertThatThrownBy(() -> verifier.verify(token))
                .isInstanceOf(AuthException.class);
    }

    /**
     * 구글은 iss를 두 형태 중 하나로 보낸다고 문서에 명시한다. 스킴 없는 쪽을
     * 거부하면 우리 잘못으로 모든 로그인이 401이 된다.
     */
    @Test
    void 스킴_없는_발급자도_구글로_인정한다() throws Exception {
        String token = factory.idToken("accounts.google.com", CLIENT_ID, "sub-1",
                "a@example.com", "김태현", null,
                Instant.now().plus(Duration.ofMinutes(5)));

        assertThat(verifier.verify(token).sub()).isEqualTo("sub-1");
    }

    @Test
    void 발급자가_구글이_아니면_거부한다() throws Exception {
        String token = factory.idToken("https://evil.example.com", CLIENT_ID, "sub-1",
                "a@example.com", "김태현", null,
                Instant.now().plus(Duration.ofMinutes(5)));

        assertThatThrownBy(() -> verifier.verify(token))
                .isInstanceOf(AuthException.class);
    }

    @Test
    void 만료된_id_token은_거부한다() throws Exception {
        String token = factory.idToken(ISSUER, CLIENT_ID, "sub-1",
                "a@example.com", "김태현", null,
                Instant.now().minus(Duration.ofMinutes(5)));

        assertThatThrownBy(() -> verifier.verify(token))
                .isInstanceOf(AuthException.class);
    }

    @Test
    void 서명이_깨진_id_token은_거부한다() throws Exception {
        String token = factory.idToken(ISSUER, CLIENT_ID, "sub-1",
                "a@example.com", "김태현", null,
                Instant.now().plus(Duration.ofMinutes(5)));

        assertThatThrownBy(() -> verifier.verify(token + "tampered"))
                .isInstanceOf(AuthException.class);
    }

    @Test
    void 프로필_이미지가_없어도_통과한다() throws Exception {
        String token = factory.idToken(ISSUER, CLIENT_ID, "sub-1",
                "a@example.com", "김태현", null,
                Instant.now().plus(Duration.ofMinutes(5)));

        assertThat(verifier.verify(token).profileImageUrl()).isNull();
    }
}
