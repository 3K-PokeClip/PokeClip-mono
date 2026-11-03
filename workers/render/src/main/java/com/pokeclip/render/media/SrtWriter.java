package com.pokeclip.render.media;

import com.pokeclip.render.recipe.Recipe.Cut;
import com.pokeclip.render.recipe.Recipe.SubtitleSegment;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 자막을 클립 축(결과 영상 0 기준 ms)의 SRT로 바꾼다. 옛 꼴은 방송 절대축 자막을 컷으로 옮기고({@link #clipped} · 0 =
 * {@code cut.inAtMs} · 계약6 2절 경계 규칙 — 아래 목록), 새 꼴은 계획 실행이 낸 결과 µs 를 ms 로 내린다({@link #onResult}).
 *
 * <ul>
 *   <li>컷 밖 자막은 무시한다(거부 아님). 컷을 넓히면 자연히 돌아온다.</li>
 *   <li>컷 경계에 걸치면 경계에서 자른다. 자른 결과가 0ms 이하면 뺀다.</li>
 *   <li>글자가 비었거나 공백뿐인 자막은 뺀다(clip이 저장은 받는다). 빈 줄을 번인하거나 srt에 넣지 않는다.</li>
 *   <li>남는 것이 없으면 빈 문자열. srt 파일을 만들지 않는다(빈 파일 금지, 계약1 3절).</li>
 * </ul>
 */
public final class SrtWriter {

    private static final long MICROS_PER_MS = 1_000;

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

    /**
     * 새 꼴 주문(렌더 계획이 있음)의 자막 하나를 결과 축 ms 로 옮긴다(PDT 설계 r7 7.6). 두 끝은 계획 실행이 낸 결과 µs 이고
     * ({@code RenderTimeline.subtitles()} — 결과 범위 자르기 · 길이 0 이하 빼기는 거기서 끝났다), 여기서는 둘 다 ms 로 내린다.
     * 번인은 프레임 시각을 ms 로 내려 {@code [시작, 끝)} 안인지 보므로(ffmpeg n8.0.1 {@code vf_subtitles.c:239-241} 이
     * libass 에 정수 ms 로 넘김), 프레임 자리에 놓인 끝점을 내리면 시작은 그 프레임부터 · 끝은 그 프레임 앞까지 보인다. 끝을 올리면
     * 끝 프레임 하나가 더 보인다.
     *
     * <p>옛 꼴 {@link #clipped}와 같은 규칙으로 뺀다 — 내린 길이가 0 이거나 글자가 비었거나 공백뿐이면 빈 값.
     *
     * @param text 레시피 {@code subtitles.segments[index].text}
     */
    public static Optional<SubtitleSegment> onResult(long startUs, long endUs, String text) {
        long start = Math.floorDiv(startUs, MICROS_PER_MS);
        long end = Math.floorDiv(endUs, MICROS_PER_MS);
        if (end <= start || text.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new SubtitleSegment(start, end, text));
    }

    /** @param clipped {@link #clipped} · {@link #onResult}의 결과(이미 클립 축) */
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
