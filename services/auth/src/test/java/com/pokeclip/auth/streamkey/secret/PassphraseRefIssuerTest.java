package com.pokeclip.auth.streamkey.secret;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** 새 암호 이름 = 접두 + UUID(POK-272, 1번 설계 8-A 2)). */
class PassphraseRefIssuerTest {

    /** ARN 꼬리(하이픈 + 6자)와 헷갈리는 이름이면 부분 ARN으로 읽힌다. */
    private static final Pattern ARN_LIKE_TAIL = Pattern.compile("-[A-Za-z0-9]{6}$");

    @Test
    void 접두_뒤에_UUID를_붙이고_매번_다르다() {
        PassphraseRefIssuer issuer = issuer("pokeclip/dev/stream-key/");

        String first = issuer.issue();
        String second = issuer.issue();

        assertThat(first).matches("pokeclip/dev/stream-key/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        assertThat(first).isNotEqualTo(second);
        assertThat(first).doesNotMatch(".*" + ARN_LIKE_TAIL.pattern());
    }

    @Test
    void 가장_긴_접두에서도_255자_안이다() {
        String longest = "a".repeat(StreamKeySecretStoreProperties.MAX_PREFIX_LENGTH - 1) + "/";

        assertThat(issuer(longest).issue()).hasSize(255);
    }

    private static PassphraseRefIssuer issuer(String prefix) {
        return new PassphraseRefIssuer(new StreamKeySecretStoreProperties(
                StreamKeySecretStoreProperties.Type.POSTGRES, prefix,
                Set.of(StreamKeySecretStoreProperties.Type.POSTGRES), null, "ap-northeast-2"));
    }
}
