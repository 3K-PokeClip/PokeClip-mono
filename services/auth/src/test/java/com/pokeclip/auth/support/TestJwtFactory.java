package com.pokeclip.auth.support;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;

/** 구글 흉내를 내는 id_token을 테스트 키로 만든다. 네트워크를 쓰지 않는다. */
public class TestJwtFactory {

    private final KeyPair keyPair;

    public TestJwtFactory() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        this.keyPair = generator.generateKeyPair();
    }

    public RSAPublicKey publicKey() {
        return (RSAPublicKey) keyPair.getPublic();
    }

    public String idToken(String issuer, String audience, String sub,
                          String email, String name, String picture, Instant expiresAt) throws Exception {
        return idToken(issuer, audience, sub, email, name, picture, expiresAt, true);
    }

    /** {@code emailVerified}가 null이면 칸을 아예 안 싣는다(POK-256 차단 시험). */
    public String idToken(String issuer, String audience, String sub, String email, String name, String picture,
                          Instant expiresAt, Object emailVerified) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .audience(List.of(audience))
                .subject(sub)
                .claim("email", email)
                .claim("email_verified", emailVerified)
                .claim("name", name)
                .claim("picture", picture)
                .issueTime(Date.from(Instant.now().minusSeconds(10)))
                .expirationTime(Date.from(expiresAt))
                .build();

        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
        jwt.sign(new RSASSASigner((RSAPrivateKey) keyPair.getPrivate()));
        return jwt.serialize();
    }
}
