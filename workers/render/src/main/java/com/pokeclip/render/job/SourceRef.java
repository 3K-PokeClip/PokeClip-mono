package com.pokeclip.render.job;

/**
 * 주문서 {@code sourceKeys} 한 항목의 seq · 역할 · (준비 조각이면) 소유 묶음 머리 seq(PDT 설계 r7 3.3).
 *
 * @param ownerSeq {@link SourceRole#AUDIO_PRIME}만 갖고, 그 밖은 null
 */
public record SourceRef(long seq, SourceRole role, Long ownerSeq) {
}
