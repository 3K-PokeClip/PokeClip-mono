package com.pokeclip.render.job;

/**
 * 주문서 좌표의 µs 오프셋을 조각 파일 안 어디서부터 재는가(PDT 설계 r7 3.1). 이름이 그대로 주문서 {@code plan.offsetOrigin}
 * 값이다.
 */
public enum OffsetOrigin {
    /** 그 조각 영상 첫 DTS 부터. */
    VIDEO_START,
    /** 그 조각 파일 머리부터. 편집기 출처는 늘 이것이다. */
    FILE_HEAD
}
