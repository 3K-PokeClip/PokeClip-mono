package com.pokeclip.clip.segment;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 원점 표지를 판에서 고르는 규칙 하나(POK-196 · kty 2026-11-12 D1). 카드 썸네일 주문과 편집기 출처 렌더 주문이 같이 부른다 — 두
 * 자리에 따로 두면 갈라진다.
 */
class OffsetOriginTest {

    @Test
    void 판_2_는_영상_첫_표본이고_판_NULL_은_파일_머리다() {
        assertThat(OffsetOrigin.ofTimingVersion(2)).isEqualTo(OffsetOrigin.VIDEO_START);
        assertThat(OffsetOrigin.ofTimingVersion(null)).isEqualTo(OffsetOrigin.FILE_HEAD);
    }
}
