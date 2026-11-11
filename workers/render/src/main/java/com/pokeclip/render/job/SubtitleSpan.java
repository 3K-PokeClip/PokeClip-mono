package com.pokeclip.render.job;

/**
 * 계획에 실린 자막 하나(PDT 설계 r7 3.3). 글자는 레시피 {@code subtitles.segments[index]}에 있다. 끝점 순서는 검사하지
 * 않는다 — 결과 길이가 0 이하면 렌더가 그 자막만 뺀다(설계 5.3 5).
 */
public record SubtitleSpan(int index, SubtitlePoint start, SubtitlePoint end) {
}
