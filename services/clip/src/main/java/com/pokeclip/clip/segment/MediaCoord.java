package com.pokeclip.clip.segment;

/**
 * 매체 자리 — 조각 {@code seq} 파일 머리에서 {@code offsetUs} µs(POK-196 · 설계 r7 3.1). 렌더 주문서의 {@code plan.in} · 자막 끝점
 * 꼴과 같다. 원점은 계획의 {@link OffsetOrigin} 이 정한다(회차 판 — kty 2026-11-12 D1).
 */
public record MediaCoord(long seq, long offsetUs) {
}
