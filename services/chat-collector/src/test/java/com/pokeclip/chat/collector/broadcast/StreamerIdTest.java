package com.pokeclip.chat.collector.broadcast;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 편지의 {@code streamerId}를 우리 회원 번호로 읽는다.
 *
 * <p>1번이 다른 식별자 체계를 쓰고 있으면 모든 방송의 토큰 조회가 실패한다.
 * 그 실패가 예외로 새면 폴링 루프가 죽으므로 값으로 돌려준다.
 */
class StreamerIdTest {

    /**
     * 문항 2(자동으로 참이 되는 입력): {@code valid()}가 늘 false인 구현이면 무효 갈래
     * 셋이 전부 통과한다. 그래서 같은 검사 안에 유효한 값의 양성 대조를 둔다.
     */
    @Test
    void 숫자가_아닌_스트리머는_유효하지_않다() {
        assertThat(StreamerId.parse("uuid-form").valid()).isFalse();
        assertThat(StreamerId.parse("42").value()).isEqualTo(42L);
        assertThat(StreamerId.parse(null).valid()).isFalse();
        assertThat(StreamerId.parse("").valid()).isFalse();
        assertThat(StreamerId.parse("   ").valid()).isFalse();
        assertThat(StreamerId.parse(" 42 ").value()).isEqualTo(42L);
    }

    /**
     * 문항 4(단언을 통과시키는 잘못된 결과): 범위 초과 한 줄만 두면 "늘 무효"인 구현도,
     * 자릿수만 세는 구현도 통과한다. 경계 바로 아래를 양성 대조로 붙여 실제로
     * {@code Long}으로 읽는 갈래를 밟게 한다.
     * <p>문항 5(그 결함에서 빨간불): {@code catch}를 지우고 확인한다.
     */
    @Test
    void 아주_큰_숫자도_유효하지_않다() {
        // Long 범위를 넘으면 파싱이 던지는데, 그 예외가 폴링 루프까지 올라가면 안 된다.
        assertThat(StreamerId.parse("999999999999999999999").valid()).isFalse();
        assertThat(StreamerId.parse("9223372036854775808").valid()).isFalse();   // Long.MAX_VALUE + 1
        assertThat(StreamerId.parse("9223372036854775807").value()).isEqualTo(Long.MAX_VALUE);
    }
}
