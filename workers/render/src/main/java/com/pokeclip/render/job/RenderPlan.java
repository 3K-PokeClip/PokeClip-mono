package com.pokeclip.render.job;

import java.util.List;

/**
 * 새 꼴 주문서의 렌더 계획(PDT 설계 r7 3.3 · 5.2). 검사(설계 5.3)를 통과한 것만 이 모양이 된다.
 *
 * @param mediaSeqs 결과에 드는 조각 seq 순서(매체뿐)
 * @param lengthMs  예상 결과 길이. 관측에만 쓴다
 */
public record RenderPlan(int planVersion, OffsetOrigin offsetOrigin, MediaCoord in, EndBoundary out,
                         List<Long> mediaSeqs, List<SubtitleSpan> subtitles, long lengthMs) {

    public RenderPlan {
        mediaSeqs = List.copyOf(mediaSeqs);
        subtitles = List.copyOf(subtitles);
    }
}
