package com.pokeclip.render.media;

import com.pokeclip.render.recipe.Recipe.Anchor;
import com.pokeclip.render.recipe.Recipe.Aspect;
import com.pokeclip.render.recipe.Recipe.SubtitlePosition;
import com.pokeclip.render.recipe.Recipe.SubtitleSegment;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AssWriterTest {

    private static final List<SubtitleSegment> ONE = List.of(new SubtitleSegment(1_500, 3_000, "첫 줄\n둘째 줄"));

    @Test
    void 기준_좌표는_결과_해상도이고_글자는_편집기_글자를_옮긴_값이다() {
        String ass = AssWriter.render(ONE, new SubtitlePosition(Anchor.BOTTOM, 0.9), Aspect.VERT_9_16);
        assertThat(ass).contains("PlayResX: 1080\nPlayResY: 1920\n");
        // 11/240 × 1080 = 49.5px, 줄 높이로 × 1.448 = 71.7 · 테두리 1/240 × 1080 = 4.5 · 좌우 8/240 × 1080 = 36
        assertThat(ass).contains("Style: Default,Noto Sans CJK KR,71.7,")
                .contains(",-1,0,0,0,100,100,0,0,1,4.5,0,2,36,36,192,1\n");
        assertThat(ass).contains("Dialogue: 0,0:00:01.50,0:00:03.00,Default,,0,0,0,,첫 줄\\N둘째 줄\n");
    }

    @Test
    void 위는_위_가운데_정렬에_위_여백이다() {
        String ass = AssWriter.render(ONE, new SubtitlePosition(Anchor.TOP, 0.05), Aspect.VERT_9_16);
        assertThat(ass).contains(",1,4.5,0,8,36,36,96,1\n").doesNotContain("\\pos");
    }

    @Test
    void 가운데는_줄마다_덩어리_가운데를_y에_찍는다() {
        // 여러 줄이어도 덩어리 가운데가 y — 한 줄 높이로 어림하면 두 줄 자막이 반 줄 위로 간다(PR #201 codex)
        String ass = AssWriter.render(ONE, new SubtitlePosition(Anchor.MIDDLE, 0.6), Aspect.VERT_9_16);
        assertThat(ass).contains(",,{\\an5\\pos(540,1152)}첫 줄\\N둘째 줄\n");
    }

    @Test
    void 사용자_글의_중괄호와_역슬래시는_태그로_읽히지_않게_바꾼다() {
        assertThat(AssWriter.text("{\\b1}굵게\r\n\n끝 ")).isEqualTo("｛＼b1｝굵게\\N끝");
    }

    @Test
    void 시각은_100분의_1초다() {
        assertThat(AssWriter.time(3_723_456)).isEqualTo("1:02:03.45");
    }

    @Test
    void 끝은_올려서_10ms_미만_자막도_보인다() {
        String ass = AssWriter.render(List.of(new SubtitleSegment(1_003, 1_008, "짧음"), new SubtitleSegment(2_000, 2_991, "끝")),
                new SubtitlePosition(Anchor.BOTTOM, 0.9), Aspect.VERT_9_16);
        assertThat(ass).contains("Dialogue: 0,0:00:01.00,0:00:01.01,").contains("Dialogue: 0,0:00:02.00,0:00:03.00,");
    }

    // ---------------------------------------------------------------- 새 꼴(결과 프레임 위)

    private static final long TS = 90_000;
    private static final SubtitlePosition BOTTOM = new SubtitlePosition(Anchor.BOTTOM, 0.9);

    /**
     * 30fps 결과 프레임 열에서 자막 [16번 프레임, 106번 프레임)(결과 533,333 · 3,533,333µs). 옛 규칙(끝 올림)이면 3,540ms 가
     * 되어 106번 프레임(3,533.33ms)까지 그린다. 덮는 프레임은 직렬화한 ASS 를 다시 읽어 libass 판정으로 구한다.
     */
    @Test
    void 새_꼴_ASS_는_계획_프레임만_덮는다() {
        long[] frames = grid(3_000, 300);

        String ass = framed(frames, cue(533_333, 3_533_333, "가"));

        assertThat(ass).contains("Dialogue: 0,0:00:00.51,0:00:03.51,");
        assertThat(covered(ass, frames)).containsExactly(range(16, 106));
    }

    /** 다음 자막이 앞 자막 끝 프레임에서 바로 시작해도 한 프레임을 둘이 함께 덮지 않는다. */
    @Test
    void 이어진_두_자막은_한_프레임을_함께_덮지_않는다() {
        long[] frames = grid(3_000, 300);

        String ass = framed(frames, cue(533_333, 3_533_333, "가"), cue(3_533_333, 5_000_000, "나"));

        assertThat(covered(ass, frames)).containsExactly(range(16, 106), range(106, 150));
    }

    /** 끝점 µs 가 프레임 자리를 올림한 값이어도(566,666.67 → 566,667) 그 프레임부터 덮는다(반 µs 여유). */
    @Test
    void 올림한_끝점도_그_프레임을_덮는다() {
        long[] frames = grid(3_000, 300);

        assertThat(covered(framed(frames, cue(566_667, 1_000_000, "가")), frames)).containsExactly(range(17, 30));
    }

    /** 60fps(16.67ms 간격)도 격자 값이 계획 프레임만 덮는다. */
    @Test
    void 육십_fps_도_계획_프레임만_덮는다() {
        long[] frames = grid(1_500, 600);

        String ass = framed(frames, cue(1_016_667, 2_000_000, "가"), cue(2_000_000, 2_516_667, "나"));

        assertThat(covered(ass, frames)).containsExactly(range(61, 120), range(120, 151));
    }

    /**
     * 정수 ms 에 딱 놓인 프레임을 실수 곱셈 뒤 버림이 1ms 아래로 읽어도(725,850틱 = 정확히 8,065ms → 8,064) 계획 프레임만
     * 덮는다. 프레임 열은 격자에서 2,850틱 밀린 30fps 다(결과 프레임은 조각 이동량만큼 격자 밖에 놓일 수 있다).
     */
    @Test
    void 정수_ms_프레임을_1ms_아래로_읽어도_계획_프레임만_덮는다() {
        long[] frames = LongStream.range(0, 400).map(i -> 2_850 + i * 3_000).toArray();
        assertThat(frames[241]).isEqualTo(725_850);
        assertThat(libassMs(725_850)).as("실수 계산이 1ms 아래로 읽는 자리").isEqualTo(8_064);

        String ass = framed(frames, cue(8_065_000, 9_065_000, "가"), cue(9_065_000, 10_065_000, "나"));

        assertThat(covered(ass, frames)).containsExactly(range(241, 271), range(271, 301));
    }

    /** 결과 첫 프레임부터 덮으면 시작은 0 · 결과 끝까지 덮으면 끝은 마지막 프레임 뒤 격자다. */
    @Test
    void 결과_처음과_끝까지_덮는다() {
        long[] frames = grid(3_000, 30);

        assertThat(covered(framed(frames, cue(0, 1_000_000, "가")), frames)).containsExactly(range(0, 30));
    }

    /** 120fps(8.33ms — 격자보다 촘촘)는 계획 프레임을 늘 덮고, 경계마다 10ms 안 이웃 프레임만 더할 수 있다. */
    @Test
    void 격자보다_촘촘한_프레임은_계획_프레임을_덮고_이웃은_10ms_안이다() {
        long[] frames = grid(750, 1_200);

        for (long from = 100; from < 140; from++) {
            long startUs = Math.round(frames[(int) from] * 1e6 / TS);
            long endUs = Math.round(frames[(int) from + 50] * 1e6 / TS);
            List<Long> got = covered(framed(frames, cue(startUs, endUs, "가")), frames).getFirst();
            assertThat(got).as("시작 프레임 %d", from).containsAll(range(from, from + 50));
            assertThat(got.getFirst()).isGreaterThanOrEqualTo(from - 1);
            assertThat(got.getLast()).isLessThanOrEqualTo(from + 50);
        }
    }

    /**
     * 100fps(10ms 간격 — 격자와 같은 촘촘함)에서 첫 프레임이 정수 ms 에 놓이고 1ms 아래로 읽히는 자리(1,451,700틱 = 16,130ms →
     * 16,129)여도 그 프레임을 덮는다. 앞 프레임 바로 위 격자(16,130)를 그대로 쓰면 첫 프레임이 빠진다.
     */
    @Test
    void 격자_간격_프레임이_1ms_아래로_읽혀도_첫_프레임을_덮는다() {
        long[] frames = grid(900, 2_000);
        assertThat(libassMs(1_451_700)).isEqualTo(16_129);

        List<Long> got = covered(framed(frames, cue(16_130_000, 16_630_000, "가")), frames).getFirst();

        assertThat(got).containsAll(range(1_613, 1_663));
        assertThat(got.getFirst()).isGreaterThanOrEqualTo(1_612);
        assertThat(got.getLast()).isLessThanOrEqualTo(1_663);
    }

    @Test
    void 덮을_프레임이_없거나_글자가_비면_뺀다() {
        long[] frames = grid(3_000, 30);

        assertThat(AssWriter.onFrames(500_001, 520_000, "가", frames, TS)).isEqualTo(Optional.empty());
        assertThat(AssWriter.onFrames(0, 1_000_000, " \n ", frames, TS)).isEqualTo(Optional.empty());
    }

    @Test
    void 격자가_아닌_시각은_쓰지_않는다() {
        assertThatThrownBy(() -> AssWriter.renderOnFrames(List.of(new SubtitleSegment(533, 3_540, "가")), BOTTOM,
                Aspect.VERT_9_16)).isInstanceOf(IllegalArgumentException.class);
    }

    /** 새 꼴의 머리 · 줄 꼴은 옛 꼴과 같다(시각만 다르다). */
    @Test
    void 새_꼴_머리와_줄_꼴은_옛_꼴과_같다() {
        List<SubtitleSegment> one = List.of(new SubtitleSegment(1_500, 3_000, "첫 줄\n둘째 줄"));

        assertThat(AssWriter.renderOnFrames(one, BOTTOM, Aspect.VERT_9_16))
                .isEqualTo(AssWriter.render(one, BOTTOM, Aspect.VERT_9_16));
    }

    private record Cue(long startUs, long endUs, String text) {
    }

    private static Cue cue(long startUs, long endUs, String text) {
        return new Cue(startUs, endUs, text);
    }

    private static String framed(long[] frames, Cue... cues) {
        List<SubtitleSegment> out = new ArrayList<>();
        for (Cue c : cues) {
            AssWriter.onFrames(c.startUs(), c.endUs(), c.text(), frames, TS).ifPresent(out::add);
        }
        return AssWriter.renderOnFrames(out, BOTTOM, Aspect.VERT_9_16);
    }

    /** 0 부터 {@code step} 틱 간격 프레임 {@code n} 개. */
    private static long[] grid(long step, int n) {
        return LongStream.range(0, n).map(i -> i * step).toArray();
    }

    /** 프레임 번호 {@code [from, to)}. */
    private static List<Long> range(long from, long to) {
        return LongStream.range(from, to).boxed().toList();
    }

    /** ffmpeg n8.0.1 {@code vf_subtitles.c:239-241} 그대로 — 실수 ms 를 libass 의 정수 ms 로(버림). */
    private static long libassMs(long ticks) {
        double timeMs = ticks * (1.0 / TS) * 1000;
        return (long) timeMs;
    }

    private static final Pattern DIALOGUE = Pattern.compile(
            "^Dialogue: 0,(\\d+):(\\d{2}):(\\d{2})\\.(\\d{2}),(\\d+):(\\d{2}):(\\d{2})\\.(\\d{2}),", Pattern.MULTILINE);

    /** 직렬화한 ASS 의 자막마다 그리는 프레임 번호 — libass 0.17.4 {@code ass_render.c:3151-3154}: 시작 ≤ ms < 끝. */
    private static List<List<Long>> covered(String ass, long[] frames) {
        List<List<Long>> out = new ArrayList<>();
        Matcher m = DIALOGUE.matcher(ass);
        while (m.find()) {
            long start = assMs(m, 1);
            long end = assMs(m, 5);
            List<Long> shown = new ArrayList<>();
            for (int i = 0; i < frames.length; i++) {
                long now = libassMs(frames[i]);
                if (now >= start && now < end) {
                    shown.add((long) i);
                }
            }
            out.add(shown);
        }
        return out;
    }

    private static long assMs(Matcher m, int from) {
        return Long.parseLong(m.group(from)) * 3_600_000 + Long.parseLong(m.group(from + 1)) * 60_000
                + Long.parseLong(m.group(from + 2)) * 1_000 + Long.parseLong(m.group(from + 3)) * 10;
    }
}
