package com.pokeclip.render.media;

import com.pokeclip.render.recipe.Recipe.Aspect;
import com.pokeclip.render.recipe.Recipe.AudioTrack;
import com.pokeclip.render.recipe.Recipe.SubtitlePosition;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * ffmpeg 명령 셋을 조립한다. 순수 함수라 명령 문자열 그대로 시험한다.
 *
 * <ol>
 *   <li>{@link #concat}. 조각 파일을 재인코딩 없이 한 파일로 잇는다. 조각마다 머리(ftyp+moov)가 있는 완전한 fMP4라
 *       concat demuxer가 파일 경계에서 시각을 이어 준다.</li>
 *   <li>{@link #measureLoudness}. 고른 트랙을 섞은 소리의 크기를 잰다.</li>
 *   <li>{@link #render}. output 하나를 만든다. 자르기(-ss·-t)·crop·scale·번인·섞기·평준화·인코딩.</li>
 * </ol>
 *
 * <p>자르기는 입력 쪽 {@code -ss}다. 재인코딩하면 ffmpeg가 키프레임이 아니라 정확한 프레임부터 낸다. 
 * 그리고 출력 시각이 0에서 시작해 번인 자막의 클립 축과 맞는다.
 */
public final class RenderCommands {

    public static final String SOURCE = "source.mp4";
    public static final String CONCAT_LIST = "concat.txt";
    public static final String BURN_SRT = "burn.srt";

    /** 번인 글꼴. 크기·여백은 libass의 기준 높이(288) 단위라 출력 해상도를 따라 커진다. v1(자리 없음) 자막의 모양이다. */
    static final String BURN_STYLE = "FontName=Noto Sans CJK KR,FontSize=13,Outline=1,Shadow=0,MarginV=60";

    /**
     * v2 번인 자막 — 편집기 미리보기의 글자(시안 11px·굵게, 좌우 8px 여백)를 결과 칸 기준 폭 240px로 옮긴 값({@link Composition}의
     * 그림자와 같은 기준). SRT를 태우면 libass 좌표가 384×288이라 세로 값은 ×288/높이, 가로 값은 ×384/폭으로 옮긴다.
     * <b>ASS 글꼴 크기는 CSS의 em이 아니라 줄 높이(ascent+descent)다</b> — Noto Sans CJK는 그 둘의 비가 1.448이라 곱한다.
     */
    static final double BURN_FONT_EM = 11.0 / 240;
    static final double BURN_SIDE = 8.0 / 240;
    static final double NOTO_CJK_LINE = 1.448;

    private RenderCommands() {
    }

    public static List<String> concat(String ffmpeg) {
        return List.of(ffmpeg, "-hide_banner", "-nostdin", "-y", "-f", "concat", "-safe", "0", "-i", CONCAT_LIST,
                "-map", "0", "-c", "copy", "-f", "mp4", SOURCE);
    }

    /**
     * concat demuxer 목록. 파일 이름만 쓴다(주문 폴더가 작업 폴더라 경로 탈출 문자가 끼지 않는다).
     *
     * <p><b>조각마다 {@code duration}을 적는 것이 요점이다.</b> 적지 않으면 demuxer가 파일 길이(가장 늦게 끝나는 트랙)만큼
     * 다음 조각을 민다. MediaMTX 조각은 파일 머리가 소리 시작이고 영상은 수십 ms 뒤에 시작하며 마지막 프레임 길이도
     * 부풀어 있어, 경계마다 0.05~0.15초 빈틈이 생기고 쌓인다(실측: 조각 3개에 영상 86·150ms, 3분이면 2초 넘게 밀린다).
     * 소리는 AAC 프레임 단위라 길이가 정확하다. 그래서 <b>앞 조각 소리가 끝난 자리에 다음 조각 소리를 붙인다</b>.
     * 그렇게 이으면 영상도 30fps 격자에 정확히 맞는다(실측: 360프레임, 빈틈 0).
     *
     * @param durationsUs 조각마다 다음 조각까지의 간격(마이크로초). 마지막 조각 몫은 쓰지 않는다
     */
    public static String concatList(List<String> fileNames, List<Long> durationsUs) {
        StringBuilder out = new StringBuilder("ffconcat version 1.0\n");
        for (int i = 0; i < fileNames.size(); i++) {
            out.append("file '").append(fileNames.get(i)).append("'\n");
            if (i < fileNames.size() - 1) {
                out.append("duration ").append(microsAsSeconds(durationsUs.get(i))).append('\n');
            }
        }
        return out.toString();
    }

    static String microsAsSeconds(long micros) {
        return String.format(Locale.ROOT, "%d.%06d", micros / 1_000_000, micros % 1_000_000);
    }

    public static List<String> measureLoudness(String ffmpeg, long offsetMs, long durationMs, List<AudioTrack> tracks) {
        List<String> cmd = new ArrayList<>(seek(ffmpeg, offsetMs, durationMs));
        cmd.addAll(List.of("-filter_complex", audioGraph(tracks, Loudness.measureFilter()),
                "-map", "[aout]", "-f", "null", "-"));
        return cmd;
    }

    /**
     * @param video        {@link Composition#plan}의 결과. 그림은 부르는 쪽이 작업 폴더에 먼저 써 둔다
     * @param loudnessFix  {@link Loudness#correctFilter}의 결과. null이면 평준화 없음(무음)
     * @param burnStyle    번인 자막 모양({@link #burnStyle}). null이면 번인하지 않는다
     */
    public static List<String> render(String ffmpeg, long offsetMs, long durationMs, Composition.Plan video,
                                      List<AudioTrack> tracks, String loudnessFix, String burnStyle,
                                      String fontsDir, String outputFile) {
        String chain = video.graph();
        if (burnStyle != null) {
            chain += ",subtitles=" + BURN_SRT
                    + (fontsDir == null || fontsDir.isBlank() ? "" : ":fontsdir=" + fontsDir)
                    + ":force_style='" + burnStyle + "'";
        }
        String graph = chain + "[vout];" + audioGraph(tracks, loudnessFix);
        // 꾸밈 그림은 원본 뒤 입력으로 넣고 영상 내내 되풀이한다. 길이(-t)는 그 뒤에 둬야 출력 쪽 옵션이 된다 —
        // 그림 앞에 두면 ffmpeg가 그것을 다음 입력(그림)의 길이로 읽는다.
        List<String> cmd = new ArrayList<>(List.of(ffmpeg, "-hide_banner", "-nostdin", "-ss", seconds(offsetMs), "-i",
                SOURCE));
        for (Composition.Image image : video.images()) {
            cmd.addAll(List.of("-loop", "1", "-i", image.name()));
        }
        cmd.addAll(List.of("-t", seconds(durationMs)));
        cmd.addAll(List.of("-filter_complex", graph, "-map", "[vout]", "-map", "[aout]",
                "-c:v", "libx264", "-preset", "veryfast", "-crf", "20", "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-b:a", "160k",
                "-movflags", "+faststart", "-y", outputFile));
        return cmd;
    }

    /**
     * 번인 자막의 {@code force_style}. 자리가 없으면(v1) 예전 모양 그대로다.
     *
     * <p>가운데 붙임(MIDDLE)은 ASS에서 세로 여백이 먹지 않아 <b>아래 붙임으로 바꿔</b> 한 줄 높이의 절반만큼 올린다 — 한 줄 자막이면
     * 가운데가 정확히 {@code y}에 온다(미리보기의 「경계」 자리가 이렇게 그린다).
     */
    public static String burnStyle(SubtitlePosition position, Aspect aspect) {
        if (position == null) {
            return BURN_STYLE;
        }
        int w = aspect.width();
        int h = aspect.height();
        double fontPx = BURN_FONT_EM * w;
        double fontSize = fontPx * NOTO_CJK_LINE * 288 / h;
        int side = (int) Math.round(BURN_SIDE * 384);
        int alignment;
        double marginV;
        switch (position.anchor()) {
            case TOP -> {
                // force_style의 정렬 번호는 옛 SSA 방식이다(아래 1·2·3, 위 5·6·7, 가운데 9·10·11). 요즘 ASS의 숫자판 배치로
                // 위 가운데인 8을 주면 왼쪽 가운데에 뜬다(2026-09-27 실측)
                alignment = 6;
                marginV = position.y() * 288;
            }
            case BOTTOM -> {
                alignment = 2;
                marginV = (1 - position.y()) * 288;
            }
            default -> {
                alignment = 2;
                marginV = (1 - position.y()) * 288 - fontSize / 2;
            }
        }
        return "FontName=Noto Sans CJK KR,FontSize=" + decimal1(fontSize) + ",Bold=1,Outline=1,Shadow=0"
                + ",Alignment=" + alignment + ",MarginL=" + side + ",MarginR=" + side
                + ",MarginV=" + Math.max(0, Math.round(marginV));
    }

    private static String decimal1(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    /**
     * 고른 트랙만 섞는다(계약6 2절: 넣은 트랙만 믹스, BGM 제외 = 그 트랙을 빼기). trackId N = N번째 소리 스트림.
     * 섞은 뒤 48kHz 스테레오로 맞추고, {@code tail}(평준화 필터)이 있으면 붙인다. 끝 라벨은 {@code [aout]}.
     */
    static String audioGraph(List<AudioTrack> tracks, String tail) {
        StringBuilder graph = new StringBuilder();
        for (AudioTrack t : tracks) {
            graph.append("[0:a:").append(t.trackId()).append("]volume=").append(decimal(t.gain()))
                    .append("[a").append(t.trackId()).append("];");
        }
        for (AudioTrack t : tracks) {
            graph.append("[a").append(t.trackId()).append(']');
        }
        if (tracks.size() > 1) {
            // normalize=0: 섞는 트랙 수로 나누지 않는다. 나누면 트랙을 하나 더 넣을 때마다 전체가 작아진다.
            graph.append("amix=inputs=").append(tracks.size()).append(":duration=longest:normalize=0,");
        }
        graph.append("aformat=sample_rates=48000:channel_layouts=stereo");
        if (tail != null) {
            graph.append(',').append(tail);
        }
        return graph.append("[aout]").toString();
    }

    private static List<String> seek(String ffmpeg, long offsetMs, long durationMs) {
        return List.of(ffmpeg, "-hide_banner", "-nostdin", "-ss", seconds(offsetMs), "-i", SOURCE,
                "-t", seconds(durationMs));
    }

    static String seconds(long ms) {
        return String.format(Locale.ROOT, "%d.%03d", ms / 1000, ms % 1000);
    }

    private static String decimal(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }
}
