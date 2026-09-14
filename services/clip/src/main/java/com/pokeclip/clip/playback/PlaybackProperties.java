package com.pokeclip.clip.playback;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;

/**
 * 영상 출입증(POK-122)의 재료 — CloudFront 서명 쿠키를 만들 키와 범위.
 *
 * <p><b>셋(키 번호·비밀키·자원 주소)이 다 비면 「꺼짐」이고 부팅은 산다.</b> 로컬에는 CloudFront가
 * 없고, 그때 죽는 것은 카드·채팅·목록까지 같이 죽이는 일이다 — {@code collector-client}와
 * 같은 판단이다. 꺼진 채로 출입증 문을 부르면 503 {@code playback_signing_unavailable}이다.
 *
 * <p><b>셋 중 일부만 채워지면 부팅을 거부한다.</b> 그것은 「일부러 안 켬」이 아니라 실수이고,
 * 실수를 조용히 「꺼짐」으로 접으면 운영에서 영상만 안 나오는데 로그가 조용하다
 * ({@code broadcast.intake}의 「켜졌는데 주소가 없으면 거부」와 같은 규칙).
 *
 * <p><b>비밀키는 PKCS#8 PEM 본문</b>({@code -----BEGIN PRIVATE KEY-----})이다. 환경변수 한 줄로
 * 넣을 수 있게 리터럴 {@code \n}은 개행으로 읽는다. {@code BEGIN RSA PRIVATE KEY}(PKCS#1)는
 * JDK가 못 읽으므로 <b>부팅에서 변환 명령까지 적어 거부한다</b> — 운영에서 첫 발급 때 500으로
 * 드러나게 두지 않는다.
 */
@ConfigurationProperties(prefix = "pokeclip.playback")
public class PlaybackProperties {

    private final String keyPairId;
    private final String privateKeyPem;
    private final String resourceBaseUrl;
    private final String cookieDomain;
    private final Duration ttl;

    private PrivateKey privateKey;

    public PlaybackProperties(String keyPairId,
                              String privateKeyPem,
                              String resourceBaseUrl,
                              String cookieDomain,
                              @DefaultValue("PT60M") Duration ttl) {
        this.keyPairId = blankToNull(keyPairId);
        this.privateKeyPem = blankToNull(privateKeyPem);
        this.resourceBaseUrl = blankToNull(resourceBaseUrl);
        this.cookieDomain = blankToNull(cookieDomain);
        this.ttl = ttl;
    }

    @PostConstruct
    void validate() {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalStateException("pokeclip.playback.ttl은 0보다 커야 한다: " + ttl);
        }
        int filled = (keyPairId != null ? 1 : 0) + (privateKeyPem != null ? 1 : 0) + (resourceBaseUrl != null ? 1 : 0);
        if (filled == 0) {
            return; // 꺼짐. 로그는 서명기가 남긴다(부팅 로그 한 줄).
        }
        if (filled != 3) {
            throw new IllegalStateException("pokeclip.playback은 key-pair-id · private-key-pem · resource-base-url "
                    + "셋을 다 주거나 다 비워야 한다 — 일부만 있으면 실수다"
                    + " (keyPairId=" + (keyPairId != null) + ", privateKeyPem=" + (privateKeyPem != null)
                    + ", resourceBaseUrl=" + (resourceBaseUrl != null) + ")");
        }
        if (resourceBaseUrl.endsWith("/")) {
            throw new IllegalStateException("pokeclip.playback.resource-base-url은 끝에 /가 없어야 한다");
        }
        this.privateKey = parsePkcs8(privateKeyPem);
    }

    /** 셋이 다 있으면 켜짐. {@link #validate()}가 일부만 있는 경우를 이미 걸렀다. */
    public boolean enabled() {
        return privateKey != null;
    }

    public String keyPairId() {
        return keyPairId;
    }

    public PrivateKey privateKey() {
        return privateKey;
    }

    public String resourceBaseUrl() {
        return resourceBaseUrl;
    }

    /** 비면 쿠키에 Domain 속성을 안 붙인다(호스트 전용). 운영은 {@code .pokeclip.com}. */
    public String cookieDomain() {
        return cookieDomain;
    }

    public Duration ttl() {
        return ttl;
    }

    /**
     * PKCS#8 PEM → {@link PrivateKey}. 값 자체는 어떤 예외 메시지에도 싣지 않는다 — 부팅 로그에
     * 비밀키 조각이 남으면 안 된다.
     */
    static PrivateKey parsePkcs8(String pem) {
        String text = pem.replace("\\n", "\n");
        if (text.contains("BEGIN RSA PRIVATE KEY")) {
            throw new IllegalStateException("pokeclip.playback.private-key-pem이 PKCS#1(BEGIN RSA PRIVATE KEY)이다. "
                    + "PKCS#8로 바꿔 넣는다: openssl pkcs8 -topk8 -nocrypt -in private_key.pem");
        }
        if (!text.contains("BEGIN PRIVATE KEY")) {
            throw new IllegalStateException("pokeclip.playback.private-key-pem이 PEM(BEGIN PRIVATE KEY)이 아니다");
        }
        String body = text.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        try {
            byte[] der = Base64.getDecoder().decode(body);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (IllegalArgumentException | InvalidKeySpecException e) {
            throw new IllegalStateException("pokeclip.playback.private-key-pem을 RSA PKCS#8 키로 읽지 못했다", e);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("RSA KeyFactory가 없다", e);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
