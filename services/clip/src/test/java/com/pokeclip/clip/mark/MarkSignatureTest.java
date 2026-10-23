package com.pokeclip.clip.mark;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 계약4 4B의 파생 값. 플러그인이 {@code Authorization: Bearer <이 값>}으로 보내고 clip이
 * auth에서 받은 passphrase로 다시 만들어 대조한다.
 *
 * <p>값은 계약4가 적어 둔 시험 벡터만 쓴다: 실제 키처럼 보이는 값을 저장소에 남기지 않는다.
 */
class MarkSignatureTest {

    private static final String VECTOR_PASSPHRASE = "EXAMPLE-4b-test-vector-not-a-key";

    private static final String VECTOR_DERIVED =
            "136ef57bb5b3eaa8c35485438c358ff1eb392d925714b76f1e2eb756d2a04315";

    /** 플러그인과 같은 값을 내는가. 키·메시지를 뒤바꾸거나 메시지 글자가 틀리면 여기서 갈린다. */
    @Test
    void 계약4_시험_벡터와_같다() {
        assertThat(MarkSignature.derive(VECTOR_PASSPHRASE)).isEqualTo(VECTOR_DERIVED);
    }

    @Test
    void 같으면_참이다() {
        assertThat(MarkSignature.matches(VECTOR_PASSPHRASE, VECTOR_DERIVED)).isTrue();
    }

    /** 끝 글자 하나만 바꾼다: 앞 몇 글자만 보는 비교도 여기서 걸린다. */
    @Test
    void 한_글자라도_다르면_거짓이다() {
        String lastFlipped = VECTOR_DERIVED.substring(0, 63) + "6";

        assertThat(MarkSignature.matches(VECTOR_PASSPHRASE, lastFlipped)).isFalse();
    }

    /** 앞부분이 같은 짧은 값·긴 값. 짧은 쪽 길이만큼만 비교하는 구현이 여기서 걸린다. */
    @Test
    void 길이가_다르면_거짓이다() {
        assertThat(MarkSignature.matches(VECTOR_PASSPHRASE, VECTOR_DERIVED.substring(0, 32))).isFalse();
        assertThat(MarkSignature.matches(VECTOR_PASSPHRASE, VECTOR_DERIVED + "0")).isFalse();
    }

    /** 계약이 소문자 hex로 정했다. 대소문자를 접어 비교하면 계약에 없는 값을 받는다. */
    @Test
    void 대문자_hex는_거짓이다() {
        assertThat(MarkSignature.matches(VECTOR_PASSPHRASE, VECTOR_DERIVED.toUpperCase())).isFalse();
    }

    /** 다른 passphrase로 만든 값은 거짓이다: 남의 키 파생 값으로 이 키를 통과하지 못한다. */
    @Test
    void 다른_passphrase면_거짓이다() {
        assertThat(MarkSignature.matches(VECTOR_PASSPHRASE + "x", VECTOR_DERIVED)).isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    void 보낸_값이_없으면_거짓이고_던지지_않는다(String bearer) {
        assertThat(MarkSignature.matches(VECTOR_PASSPHRASE, bearer)).isFalse();
    }

    /** 빈 키는 {@code SecretKeySpec}이 던진다. 대조는 예외 없이 거짓이어야 한다. */
    @ParameterizedTest
    @NullAndEmptySource
    void passphrase가_없으면_거짓이고_던지지_않는다(String passphrase) {
        assertThat(MarkSignature.matches(passphrase, VECTOR_DERIVED)).isFalse();
    }
}
