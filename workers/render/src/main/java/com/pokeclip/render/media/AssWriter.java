package com.pokeclip.render.media;

import com.pokeclip.render.recipe.Recipe.Aspect;
import com.pokeclip.render.recipe.Recipe.SubtitlePosition;
import com.pokeclip.render.recipe.Recipe.SubtitleSegment;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * 자리가 있는 번인 자막(계약6 7절 {@code subtitles.position})을 ASS로 쓴다.
 *
 * <p><b>SRT가 아니라 ASS인 이유</b>: 자리를 결과 픽셀 좌표로 찍어야 한다. SRT를 태우면 ffmpeg가 384×288 기준 좌표로 옮기고
 * {@code force_style} 정렬 번호는 옛 SSA 방식이며, 줄 앞의 위치 태그({@code {\pos}})는 버린다(2026-09-27 실측). 그래서
 * 「가운데」를 한 줄 높이로 어림할 수밖에 없어 두 줄 자막이 반 줄 어긋났다(PR #201 codex). ASS는 기준 좌표를 결과 해상도로
 * 두고({@code PlayResX/Y}) 가운데 자막에 {@code \an5\pos}를 붙여, 줄 수와 상관없이 덩어리 가운데가 {@code y}에 온다.
 *
 * <p>글자는 편집기 미리보기의 글자(결과 칸 기준 폭 240px에서 11px·굵게, 좌우 8px, 위·아래 여백은 {@code y}가 이미 담았다)를
 * 결과 폭 비로 옮긴 값이다. <b>ASS 글꼴 크기는 CSS의 em이 아니라 줄 높이(ascent+descent)다</b>. Noto Sans CJK는 그 비가
 * 1.448이라 곱한다. 글자 그림자(미리보기 {@code 0 1px 3px})는 테두리 1px로 대신한다.
 */
public final class AssWriter {

    static final double FONT_EM = 11.0 / 240;
    static final double SIDE = 8.0 / 240;
    static final double OUTLINE = 1.0 / 240;
    static final double NOTO_CJK_LINE = 1.448;
    /** ASS 시각의 격자(100분의 1초). */
    static final long GRID_MS = 10;
    /**
     * 결과 프레임을 ASS 시각으로 가를 수 있는 가장 짧은 프레임 간격(ms). 간격이 이 값 이상이면 {@link #onFrames} 가 계획
     * 프레임만 덮는다 — 격자 한 칸(10ms)에, 정수 ms 에 놓인 프레임이 1ms 아래로 읽힐 수 있는 몫(1ms)을 더한 값이다.
     */
    public static final long MIN_FRAME_GAP_MS = 11;
    private static final long MICROS = 1_000_000;

    private AssWriter() {
    }

    /**
     * 옛 꼴 주문의 자막. 시각은 레시피 ms 를 10ms(ASS 의 100분의 1초) 격자로 옮긴다 — 시작은 내리고 끝은 올린다.
     *
     * @param clipped  {@link SrtWriter#clipped}의 결과(이미 클립 축, 빈 줄 없음)
     * @param position 자막 자리(null 아님 — 자리가 없는 v1 자막은 SRT + 예전 모양으로 태운다)
     */
    public static String render(List<SubtitleSegment> clipped, SubtitlePosition position, Aspect aspect) {
        Head head = head(position, aspect);
        StringBuilder out = new StringBuilder(head.text());
        for (SubtitleSegment s : clipped) {
            // 시작은 내리고 끝은 올린다 — 둘 다 내리면 10ms 미만 자막은 시작=끝이 되어 안 보이고 끝이 최대 9ms 짧아진다
            long start = s.startAtMs() / 10 * 10;
            long end = Math.max(start + 10, (s.endAtMs() + 9) / 10 * 10);
            dialogue(out, start, end, head.tag(), s.text());
        }
        return out.toString();
    }

    /**
     * 새 꼴 주문(렌더 계획이 있음)의 자막 하나를 결과 프레임 위 ASS 시각으로 옮긴다(PDT 설계 r7 7.6 · 16.2 P20). 덮을 프레임은
     * 결과 µs 두 끝 {@code [startUs, endUs)} 안에 든 결과 프레임이다(반 µs 여유 — 끝점 µs 는 프레임 자리를 반올림한 값이다). ASS
     * 시각은 10ms 격자라, 그 프레임들만 덮도록 격자 값을 고른다.
     *
     * <ul>
     *   <li>번인은 프레임 시각(ms · 실수)을 정수로 버려 libass 에 넘기고(ffmpeg n8.0.1 {@code vf_subtitles.c:239-241}),
     *       libass 는 {@code 시작 ≤ 그 값 < 끝} 인 자막을 그린다(libass 0.17.4 {@code ass_render.c:3151-3154}).</li>
     *   <li>시작 = 덮는 첫 프레임 바로 앞 프레임의 ms 보다 큰 가장 작은 격자 값(첫 프레임이 결과 첫 프레임이면 0) · 끝 = 덮는
     *       마지막 프레임의 ms 보다 큰 가장 작은 격자 값. 그러면 앞 프레임과 끝 다음 프레임이 빠지고, 다음 자막이 바로 이어
     *       시작하면 앞 자막의 끝과 그 시작이 같은 값이라 한 프레임을 둘이 함께 덮지 않는다.</li>
     *   <li>프레임 시각이 정수 ms 에 딱 놓이면 실수 곱셈이 그 아래로 떨어져 1ms 작게 읽힐 수 있다(90000 틱 200초 안 정수 ms
     *       프레임 자리 200,000곳 중 136곳 — 계산 확인). 그래서 첫 프레임은 1ms 작게 읽혀도 덮이게 본다.</li>
     *   <li>이웃 프레임 간격이 {@value #MIN_FRAME_GAP_MS}ms 이상이면 위 값이 늘 그 프레임들만 덮는다. 더 촘촘하면 격자보다
     *       촘촘한 프레임을 ASS 시각으로 가를 수 없다 — 그래서 새 꼴 렌더는 조각 안 원본 간격이 그만큼 촘촘한 주문에 자리
     *       있는 번인이 붙으면 {@link #denseGap} 으로 거절하고(D59), 이 함수가 촘촘한 갈래로 낸 값은 최종 번인에 쓰이지
     *       않는다(값은 낸 뒤 거절된다). 그 갈래는 시작을 첫 프레임의 ms 아래 격자로 내려 계획 프레임은 늘 덮지만, 경계마다
     *       이웃 프레임을 함께 덮을 수 있다(200fps 에서 앞 이웃 둘 · 이어진 두 자막이 두 프레임을 함께 덮음). 조각 이음
     *       자리는 거절하지 않으므로, 이음 두 프레임이 촘촘하고 자막 경계가 정확히 그 사이면 이 갈래가 그대로 쓰인다.</li>
     * </ul>
     *
     * <p>덮을 프레임이 없거나 글자가 비었거나 공백뿐이면 빈 값.
     *
     * @param frameTicks 결과 영상 프레임 시각(결과 축 틱 · 오름차순) — 계획이 고른 프레임 그대로
     * @param timescale  그 틱의 단위(1초 = timescale)
     */
    public static Optional<SubtitleSegment> onFrames(long startUs, long endUs, String text, long[] frameTicks,
                                                     long timescale) {
        int first = firstFrameFrom(frameTicks, startUs, timescale);
        int next = firstFrameFrom(frameTicks, endUs, timescale);
        if (first >= next || text.isBlank()) {
            return Optional.empty();
        }
        long start = first == 0 ? 0 : gridAbove(frameMs(frameTicks[first - 1], timescale));
        start = Math.min(start, floorDiv10(readMs(frameTicks[first], timescale)));
        long end = gridAbove(frameMs(frameTicks[next - 1], timescale));
        return Optional.of(new SubtitleSegment(start, end, text));
    }

    /**
     * 프레임 열에서 이웃 프레임 간격의 최솟값(틱)이 {@value #MIN_FRAME_GAP_MS}ms 보다 짧으면 그 값. 그만큼 촘촘한 프레임은
     * ASS 시각(10ms 격자)으로 가를 수 없어 {@link #onFrames} 가 계획 프레임만 덮는다고 보장하지 못한다(PDT 설계 r7 7.6 · D59).
     * 비교는 정수로 한다 — {@code 간격 × 1000 < 11 × timescale}.
     *
     * @param frameTicks 프레임 시각(오름차순) — 새 꼴 렌더는 조각 하나 안의 결과 프레임만 넘긴다(이음 자리 간격은 재지 않음)
     * @return 촘촘하지 않거나 프레임이 둘 미만이면 빈 값
     */
    public static OptionalLong denseGap(long[] frameTicks, long timescale) {
        if (frameTicks.length < 2) {
            return OptionalLong.empty();
        }
        long min = Long.MAX_VALUE;
        for (int i = 1; i < frameTicks.length; i++) {
            min = Math.min(min, frameTicks[i] - frameTicks[i - 1]);
        }
        // min < 11 × timescale ÷ 1000 을 넘침 없이: 정수 min 에 대해 min < ⌈11 × timescale ÷ 1000⌉ 와 같다
        long limit = Math.ceilDiv(Math.multiplyExact(MIN_FRAME_GAP_MS, timescale), 1_000L);
        return min < limit ? OptionalLong.of(min) : OptionalLong.empty();
    }

    /**
     * {@link #onFrames}로 고른 자막을 시각 그대로 쓴다(새 꼴 주문).
     *
     * @throws IllegalArgumentException 시각이 10ms 격자가 아니거나 끝이 시작보다 크지 않으면
     */
    public static String renderOnFrames(List<SubtitleSegment> framed, SubtitlePosition position, Aspect aspect) {
        Head head = head(position, aspect);
        StringBuilder out = new StringBuilder(head.text());
        for (SubtitleSegment s : framed) {
            if (s.startAtMs() % GRID_MS != 0 || s.endAtMs() % GRID_MS != 0 || s.endAtMs() <= s.startAtMs()) {
                throw new IllegalArgumentException("ASS 시각이 10ms 격자가 아니다: " + s.startAtMs() + "–" + s.endAtMs());
            }
            dialogue(out, s.startAtMs(), s.endAtMs(), head.tag(), s.text());
        }
        return out.toString();
    }

    /** 머리(스타일까지)와 줄 앞 위치 태그. */
    private record Head(String text, String tag) {
    }

    private static Head head(SubtitlePosition position, Aspect aspect) {
        int w = aspect.width();
        int h = aspect.height();
        double fontSize = FONT_EM * w * NOTO_CJK_LINE;
        int side = (int) Math.round(SIDE * w);
        int alignment;
        long marginV;
        String tag;
        switch (position.anchor()) {
            case TOP -> {
                alignment = 8;
                marginV = Math.round(position.y() * h);
                tag = "";
            }
            case BOTTOM -> {
                alignment = 2;
                marginV = Math.round((1 - position.y()) * h);
                tag = "";
            }
            default -> {
                alignment = 5;
                marginV = 0;
                tag = "{\\an5\\pos(" + w / 2 + "," + Math.round(position.y() * h) + ")}";
            }
        }
        String text = new StringBuilder()
                .append("[Script Info]\nScriptType: v4.00+\nPlayResX: ").append(w).append("\nPlayResY: ").append(h)
                .append("\nScaledBorderAndShadow: yes\nWrapStyle: 0\n\n")
                .append("[V4+ Styles]\nFormat: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, "
                        + "BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, "
                        + "Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\n")
                .append("Style: Default,Noto Sans CJK KR,").append(decimal(fontSize))
                .append(",&H00FFFFFF,&H00FFFFFF,&H00000000,&H00000000,-1,0,0,0,100,100,0,0,1,")
                .append(decimal(OUTLINE * w)).append(",0,").append(alignment).append(',').append(side).append(',')
                .append(side).append(',').append(marginV).append(",1\n\n")
                .append("[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n")
                .toString();
        return new Head(text, tag);
    }

    private static void dialogue(StringBuilder out, long startMs, long endMs, String tag, String raw) {
        out.append("Dialogue: 0,").append(time(startMs)).append(',').append(time(endMs))
                .append(",Default,,0,0,0,,").append(tag).append(text(raw)).append('\n');
    }

    /** 반 µs 여유로 {@code us} 자리 또는 그 뒤인 첫 프레임 번호(없으면 프레임 수). */
    private static int firstFrameFrom(long[] frameTicks, long us, long timescale) {
        long bound = Math.multiplyExact(2 * us - 1, timescale);
        for (int i = 0; i < frameTicks.length; i++) {
            if (Math.multiplyExact(2 * MICROS, frameTicks[i]) >= bound) {
                return i;
            }
        }
        return frameTicks.length;
    }

    /** 프레임 시각을 ms 로 버린 정확값. */
    private static long frameMs(long ticks, long timescale) {
        return Math.floorDiv(Math.multiplyExact(ticks, 1_000L), timescale);
    }

    /** libass 가 읽을 수 있는 가장 작은 ms — 정수 ms 에 딱 놓인 프레임은 1ms 아래로 읽힐 수 있다. */
    private static long readMs(long ticks, long timescale) {
        long ms = frameMs(ticks, timescale);
        boolean whole = Math.floorMod(Math.multiplyExact(ticks, 1_000L), timescale) == 0;
        return whole && ms > 0 ? ms - 1 : ms;
    }

    /** {@code ms} 보다 큰 가장 작은 격자 값. */
    private static long gridAbove(long ms) {
        return floorDiv10(ms) + GRID_MS;
    }

    private static long floorDiv10(long ms) {
        return Math.floorDiv(ms, GRID_MS) * GRID_MS;
    }

    /**
     * 자막 글을 ASS 본문으로. 줄바꿈은 {@code \N}. 중괄호는 꾸밈 태그의 시작이고 역슬래시는 제어 문자라, 사용자가 쓴 글이
     * 태그로 읽히지 않게 전각으로 바꾼다(빼면 글이 달라진다).
     */
    static String text(String raw) {
        return raw.replace("\r", "").strip()
                .replace("\\", "＼").replace("{", "｛").replace("}", "｝")
                .replaceAll("\n+", "\\\\N");
    }

    /** ASS 시각 {@code H:MM:SS.cc}(100분의 1초). */
    static String time(long ms) {
        long cs = ms / 10;
        return String.format(Locale.ROOT, "%d:%02d:%02d.%02d", cs / 360_000, cs / 6_000 % 60, cs / 100 % 60, cs % 100);
    }

    private static String decimal(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }
}
