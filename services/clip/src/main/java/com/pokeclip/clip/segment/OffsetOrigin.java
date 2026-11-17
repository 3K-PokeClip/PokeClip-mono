package com.pokeclip.clip.segment;

/**
 * 조각 안 오프셋을 어디서부터 재나 — 렌더 · 썸네일 주문서의 {@code offsetOrigin} 과 같은 이름 · 같은 값이다(POK-196 · 설계 r7 3.1).
 * {@code VIDEO_START} 는 영상 첫 표본, {@code FILE_HEAD} 는 파일 머리(모든 스트림 첫 DTS 의 최솟값)부터다.
 */
public enum OffsetOrigin {
    VIDEO_START,
    FILE_HEAD;

    /**
     * 행 판으로 원점을 고르는 규칙 하나 — 판 2 는 PDT 가 영상 첫 표본을 가리켜 {@code VIDEO_START}, 판 NULL(옛 식)은
     * {@code FILE_HEAD} 다(kty 2026-11-12 D1). 카드 썸네일 주문과 편집기 출처 렌더 주문이 같이 부른다 — 두 자리에 따로 두면
     * 갈라진다.
     *
     * @param timingVersion 장부 {@code timing_version}. null(옛 식) 또는 2
     */
    public static OffsetOrigin ofTimingVersion(Integer timingVersion) {
        return timingVersion != null && timingVersion == 2 ? VIDEO_START : FILE_HEAD;
    }
}
