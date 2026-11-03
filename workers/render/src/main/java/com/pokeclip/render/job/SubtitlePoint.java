package com.pokeclip.render.job;

/** 자막 끝점(PDT 설계 r7 3.3 · 5.2). 매체 자리이거나, 이어 붙어 사라지는 틈 안 자리다. */
public sealed interface SubtitlePoint {

    /** 조각 안 자리. */
    record AtMedia(MediaCoord at) implements SubtitlePoint {
    }

    /** 조각 {@code afterSeq} 끝 뒤 틈으로 {@code intoGapMs} 들어간 자리. */
    record InGap(long afterSeq, long intoGapMs) implements SubtitlePoint {
    }
}
