package com.pokeclip.render.job;

/**
 * 매체 자리 — 조각 {@code seq} 안 {@code offsetUs}(PDT 설계 r7 3.1). 원점은 계획의 {@link OffsetOrigin} 이다.
 * 순서는 seq 다음 오프셋의 사전 순이다.
 */
public record MediaCoord(long seq, long offsetUs) implements Comparable<MediaCoord> {

    @Override
    public int compareTo(MediaCoord other) {
        int bySeq = Long.compare(seq, other.seq);
        return bySeq != 0 ? bySeq : Long.compare(offsetUs, other.offsetUs);
    }
}
