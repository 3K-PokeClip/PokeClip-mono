package com.pokeclip.render.job;

/**
 * 결과 끝 경계(PDT 설계 r7 3.1). 오프셋 하나이거나 「그 조각 마지막 프레임까지」({@code toSegmentEnd}) 둘 중 하나다.
 *
 * @param offsetUs {@code toSegmentEnd} 면 null
 */
public record EndBoundary(long seq, Long offsetUs, boolean toSegmentEnd) {

    public EndBoundary {
        if (toSegmentEnd == (offsetUs != null)) {
            throw new IllegalArgumentException("끝은 offsetUs와 toSegmentEnd 가운데 하나만 적는다");
        }
    }

    /** 시작 {@code in}이 이 끝보다 앞인가. {@code toSegmentEnd}는 그 조각 안에서 무한대다. */
    public boolean isAfter(MediaCoord in) {
        if (in.seq() != seq) {
            return in.seq() < seq;
        }
        return toSegmentEnd || in.offsetUs() < offsetUs;
    }
}
