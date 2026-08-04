package com.pokeclip.auth.support;

import org.junit.jupiter.api.Test;
import java.security.SecureRandom;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CrockfordBase32Test {

    private final SecureRandom random = new SecureRandom();

    /** ADR-019: 0-9와 A-Z 중 I·L·O·U를 뺀 32글자. 사람이 복사·구술해도 안 헷갈린다. */
    @Test
    void 생성된_값에는_혼동_문자가_없다() {
        for (int i = 0; i < 200; i++) {
            assertThat(CrockfordBase32.random(random, 26))
                    .hasSize(26)
                    .matches("[0-9A-HJKMNP-TV-Z]+");
        }
    }

    @Test
    void 같은_값이_반복해서_나오지_않는다() {
        assertThat(CrockfordBase32.random(random, 26))
                .isNotEqualTo(CrockfordBase32.random(random, 26));
    }

    /** 사람이 XXXX-XXXX로 받아 소문자로 치고 O를 0으로 잘못 읽는다. 전부 흡수한다. */
    @Test
    void 하이픈과_소문자와_혼동_문자를_정규화한다() {
        assertThat(CrockfordBase32.normalize("abcd-efgh")).isEqualTo("ABCDEFGH");
        assertThat(CrockfordBase32.normalize("O0Il1")).isEqualTo("00111");
    }

    /**
     * U만 거부한다. Crockford가 U를 뺀 이유는 실수로 욕설이 만들어지는 것을
     * 막기 위해서고, I·L·O처럼 대체할 글자가 없다.
     */
    @Test
    void U가_들어오면_거부한다() {
        assertThatThrownBy(() -> CrockfordBase32.normalize("ABCDEFGU"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 거부된 값이 예외 메시지에 실리면 코드 원문이 로그로 샌다. */
    @Test
    void 거부_메시지에_입력값을_담지_않는다() {
        assertThatThrownBy(() -> CrockfordBase32.normalize("SECRETUU"))
                .hasMessageNotContaining("SECRETUU");
    }
}
