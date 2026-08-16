package com.pokeclip.chat.collector.archive;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 부팅 검증 — {@code @ConfigurationPropertiesScan}이 이 record를 빈으로 올리는 순간
 * {@code @Validated}가 건다. 여기서는 스프링 없이 {@code Validator}로 같은 제약을 직접 잰다.
 */
class ArchivePropertiesValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void pendingMax가_0이면_검증에_걸린다() {
        ArchiveProperties p = new ArchiveProperties("b", "ap-northeast-2", "", false, 0, 10_000);
        assertThat(validator.validate(p)).isNotEmpty();
    }

    @Test
    void bucket이_비면_enabled가_false다() {
        assertThat(new ArchiveProperties("", "ap-northeast-2", "", false, 60, 10_000).enabled()).isFalse();
        assertThat(new ArchiveProperties(null, "ap-northeast-2", "", false, 60, 10_000).enabled()).isFalse();
        assertThat(new ArchiveProperties("b", "ap-northeast-2", "", false, 60, 10_000).enabled()).isTrue();
    }
}
