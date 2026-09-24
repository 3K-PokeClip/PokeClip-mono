package com.pokeclip.render.media;

import com.pokeclip.render.recipe.Recipe.Cut;
import com.pokeclip.render.recipe.Recipe.SubtitleSegment;

import java.util.ArrayList;
import java.util.List;

/**
 * 방송 절대축 자막을 클립 축(0 = {@code cut.inAtMs})의 SRT로 바꾼다(계약6 2절 경계 규칙).
 *
 * <ul>
 *   <li>컷 밖 자막은 무시한다(거부 아님). 컷을 넓히면 자연히 돌아온다.</li>
 *   <li>컷 경계에 걸치면 경계에서 자른다. 자른 결과가 0ms 이하면 뺀다.</li>
 *   <li>글자가 비었거나 공백뿐인 자막은 뺀다(clip이 저장은 받는다). 빈 줄을 번인하거나 srt에 넣지 않는다.</li>
 *   <li>남는 것이 없으면 빈 문자열. srt 파일을 만들지 않는다(빈 파일 금지, 계약1 3절).</li>
 * </ul>
 */
public final class SrtWriter {

    private SrtWriter() {
    }

    public static List<SubtitleSegment> clipped(List<SubtitleSegment> segments, Cut cut) {
        List<SubtitleSegment> inside = new ArrayList<>();
        for (SubtitleSegment s : segments) {
            long start = Math.max(s.startAtMs(), cut.inAtMs()) - cut.inAtMs();
            long end = Math.min(s.endAtMs(), cut.outAtMs()) - cut.inAtMs();
            if (end > start && !s.text().isBlank()) {
                inside.add(new SubtitleSegment(start, end, s.text()));
            }
        }
        return inside;
    }

    /** @param clipped {@link #clipped}의 결과(이미 클립 축) */
    public static String render(List<SubtitleSegment> clipped) {
        StringBuilder out = new StringBuilder();
        int index = 1;
        for (SubtitleSegment s : clipped) {
            out.append(index++).append('\n')
                    .append(time(s.startAtMs())).append(" --> ").append(time(s.endAtMs())).append('\n')
                    // 빈 줄은 SRT에서 「다음 자막」이라는 뜻이라 본문 안의 빈 줄을 접는다.
                    .append(s.text().replace("\r", "").replaceAll("\n{2,}", "\n").strip()).append("\n\n");
        }
        return out.toString();
    }

    static String time(long ms) {
        long h = ms / 3_600_000;
        long m = ms / 60_000 % 60;
        long s = ms / 1000 % 60;
        long milli = ms % 1000;
        return String.format("%02d:%02d:%02d,%03d", h, m, s, milli);
    }
}
