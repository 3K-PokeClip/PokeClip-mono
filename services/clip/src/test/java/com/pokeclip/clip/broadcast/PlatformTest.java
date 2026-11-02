package com.pokeclip.clip.broadcast;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 플랫폼 칸의 형식 판정(POK-398). 계약은 「대문자 영문·숫자·밑줄 1~16자」다. 칸 길이(16)를 넘는 값이 통과하면
 * 저장에서 영영 실패해 같은 방송의 뒤 편지가 전부 막힌다(FIFO). 그래서 경계 둘(16·17자)을 같이 잰다.
 */
class PlatformTest {

    @ParameterizedTest
    @ValueSource(strings = {"CHZZK", "SOOP", "A", "NEW_PLATFORM_2", "ABCDEFGHIJKLMNOP"})
    void 형식에_맞는_값(String value) {
        assertThat(Platform.isWellFormed(value)).isTrue();
    }

    /**
     * 소문자도 형식 밖이다. 대문자로 올려 받지 않는다: 받는 쪽이 고쳐 주면 발행 쪽 실수가 안 드러난다.
     * 빈 값·공백은 「안 보냄」으로 따로 다루므로({@link BroadcastEventProcessor}) 여기서는 형식 밖으로만 본다.
     */
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "chzzk", "Soop", "chzzk!", " SOOP", "SOOP ", "ABCDEFGHIJKLMNOPQ", "치지직",
            "SO-OP", "SOOP\nFAKE"})
    void 형식_밖의_값(String value) {
        assertThat(Platform.isWellFormed(value)).isFalse();
    }
}
