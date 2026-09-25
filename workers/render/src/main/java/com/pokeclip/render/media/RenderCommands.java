package com.pokeclip.render.media;

import com.pokeclip.render.recipe.Recipe.Aspect;
import com.pokeclip.render.recipe.Recipe.AudioTrack;

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

    /** 번인 글꼴. 크기·여백은 libass의 기준 높이(288) 단위라 출력 해상도를 따라 커진다. */
    static final String BURN_STYLE = "FontName=Noto Sans CJK KR,FontSize=13,Outline=1,Shadow=0,MarginV=60";

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
     * @param crop         {@link CropGeometry#toPixels}의 결과
     * @param loudnessFix  {@link Loudness#correctFilter}의 결과. null이면 평준화 없음(무음)
     * @param burn         번인 자막 파일({@link #BURN_SRT})을 쓸지
     */
    public static List<String> render(String ffmpeg, long offsetMs, long durationMs, CropGeometry.Pixels crop,
                                      Aspect aspect, List<AudioTrack> tracks, String loudnessFix, boolean burn,
                                      String fontsDir, String outputFile) {
        String video = "[0:v:0]setsar=1," + crop.filter() + ",scale=" + aspect.width() + ":" + aspect.height()
                + ":flags=lanczos,setsar=1";
        if (burn) {
            video += ",subtitles=" + BURN_SRT
                    + (fontsDir == null || fontsDir.isBlank() ? "" : ":fontsdir=" + fontsDir)
                    + ":force_style='" + BURN_STYLE + "'";
        }
        String graph = video + "[vout];" + audioGraph(tracks, loudnessFix);
        List<String> cmd = new ArrayList<>(seek(ffmpeg, offsetMs, durationMs));
        cmd.addAll(List.of("-filter_complex", graph, "-map", "[vout]", "-map", "[aout]",
                "-c:v", "libx264", "-preset", "veryfast", "-crf", "20", "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-b:a", "160k",
                "-movflags", "+faststart", "-y", outputFile));
        return cmd;
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
