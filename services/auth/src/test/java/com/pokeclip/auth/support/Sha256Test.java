package com.pokeclip.auth.support;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class Sha256Test {

    @Test
    void 알려진_값의_해시가_맞는다() {
        assertThat(Sha256.hex("abc")).isEqualTo(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void 길이는_항상_64자다() {
        assertThat(Sha256.hex("")).hasSize(64);
        assertThat(Sha256.hex("a".repeat(1000))).hasSize(64);
    }
}
