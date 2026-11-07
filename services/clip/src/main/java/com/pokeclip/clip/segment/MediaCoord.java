package com.pokeclip.clip.segment;

/**
 * 매체 자리 — 조각 {@code seq} 파일 머리에서 {@code offsetUs} µs(POK-196 · 설계 r7 3.1). 렌더 주문서의 {@code plan.in} · 자막 끝점
 * 꼴과 같다. 편집기 출처 좌표라 원점은 늘 파일 머리({@code FILE_HEAD})다.
 */
public record MediaCoord(long seq, long offsetUs) {
}
