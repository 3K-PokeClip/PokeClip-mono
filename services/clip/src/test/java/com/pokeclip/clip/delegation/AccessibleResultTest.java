package com.pokeclip.clip.delegation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link AccessibleResult#narrowTo}: 채널 스위처(POK-390)가 목록 둘을 한 스트리머로 좁히는 자리. */
class AccessibleResultTest {

    /**
     * 🔴 물어보지 못한 답을 좁혀도 「물어보지 못했다」로 남아야 한다. 참으로 바뀌면 호출 순서가 바뀌는 날
     * auth 장애(503)가 「볼 수 없다」(404)로 둔갑한다(계획 검증 m1). 컨트롤러 시험은 지금 순서에서는 이것을 못 잰다.
     */
    @Test
    void 물어보지_못한_답은_좁혀도_물어보지_못한_답이다() {
        assertThat(AccessibleResult.unavailable().narrowTo(3L).available()).isFalse();
    }

    @Test
    void 번호로_한_줄만_남기고_없으면_빈_목록이다() {
        AccessibleResult 둘 = AccessibleResult.of(List.of(
                new AccessibleResult.Entry(7, ResolveResult.OWNER),
                new AccessibleResult.Entry(3, ResolveResult.EDITOR)));

        assertThat(둘.narrowTo(3L).streamers()).containsExactly(new AccessibleResult.Entry(3, ResolveResult.EDITOR));
        assertThat(둘.narrowTo(5L).streamers()).isEmpty();
        assertThat(둘.narrowTo(5L).available()).isTrue();
        assertThat(둘.narrowTo(null)).isSameAs(둘);
    }
}
