package com.pokeclip.render.media;

import com.pokeclip.render.recipe.Recipe.Aspect;
import com.pokeclip.render.recipe.Recipe.AudioTrack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.IntFunction;

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
 *
 * <p>위 셋은 옛 꼴 주문(렌더 계획 없음)의 명령이다. 새 꼴 주문은 두 단계로 간다 — 자를 틱 · 놓을 자리 · 소리 묶음은 부르는
 * 쪽이 렌더 계획에서 한 번 정해 넘기고, 여기서는 문자열로만 옮긴다.
 *
 * <ol>
 *   <li>{@link #cutPiece}. 영상 프레임이 남는 조각마다 원래 틱으로 잘라 무손실(FFV1 · NUT) 중간 파일 하나를 쓴다.</li>
 *   <li>{@link #joinList} · {@link #runList}. 중간 파일 이음 목록과 소리 묶음마다의 concat demuxer 목록.</li>
 *   <li>{@link #measureLoudnessJoined} · {@link #renderJoined}. 이음 목록 하나(영상)와 소리 묶음 입력으로 출력마다 한 번
 *       인코딩한다. 원본 조각을 영상 입력으로 다시 읽지 않고, 시간 창({@code -ss} · {@code -t})이 없다.</li>
 * </ol>
 */
public final class RenderCommands {

    public static final String SOURCE = "source.mp4";
    public static final String CONCAT_LIST = "concat.txt";
    public static final String BURN_SRT = "burn.srt";
    public static final String JOIN_LIST = "mid_list.ffconcat";

    /** 번인 글꼴. 크기·여백은 libass의 기준 높이(288) 단위라 출력 해상도를 따라 커진다. 자리가 없는 v1 자막(SRT)의 모양이다. */
    static final String BURN_STYLE = "FontName=Noto Sans CJK KR,FontSize=13,Outline=1,Shadow=0,MarginV=60";

    /**
     * 평준화 뒤 소리 정리 — 시각을 표본 수로 다시 매기고 48kHz 로 되돌린다. 2단계 평준화가 동적 모드로 떨어지면(최고점 대 크기 비
     * 12.5dB 초과 · 잰 LRA 11 초과 · 잰 LRA 0 — {@code linear=true}여도) loudnorm 이 192kHz 로 내보내고, 입력 끝 2.9초 앞 자리에서
     * 시각이 {@code 100ms − (입력 길이 mod 100ms)}만큼 뛰어 그 뒤 소리가 영상보다 늦다. 표본은 빼거나 더하지 않으므로 표본 수로
     * 다시 매기면 제자리다(운영 판 8.0.1 실측 — 뜀 0 · 싱크 그대로 · 크기 그대로).
     */
    static final String LOUDNESS_SETTLE = "asetpts=N/SR/TB,aresample=48000";

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
     * @param burnFile     번인할 자막 파일. {@link #BURN_SRT}면 예전 모양({@link #BURN_STYLE})을 입히고, ASS({@link AssWriter})는
     *                     파일이 모양을 다 갖고 있다. null이면 번인하지 않는다
     */
    public static List<String> render(String ffmpeg, long offsetMs, long durationMs, Composition.Plan video,
                                      List<AudioTrack> tracks, String loudnessFix, String burnFile,
                                      String fontsDir, String outputFile) {
        // 평준화가 있을 때만 정리를 붙인다(무음은 평준화가 없어 시각이 뛰지 않는다). 끝 자르기는 출력 -t 가 한다 — ffmpeg 가 출력
        // 소리 사슬 끝에 atrim(duration)을 넣고, 정리 뒤라 그 자르기가 표본 수 시각 위에서 정확하다
        String tail = loudnessFix == null ? null : loudnessFix + "," + LOUDNESS_SETTLE;
        String graph = videoChain(video, burnFile, fontsDir) + "[vout];" + audioGraph(tracks, tail);
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
     * 새 꼴 조각 단계 하나 — 영상 프레임이 남는 조각 하나의 자를 틱과 결과 자리.
     *
     * @param startPts         남는 첫 프레임 PTS(그 조각 영상의 원래 틱)
     * @param endPts           남는 마지막 프레임 PTS + 1(끝 배타). 표시 소유 · 결과 끝 소유를 마친 값이다
     * @param resultStartTicks 중간 파일 첫 프레임의 결과 축 자리(틱 — 첫 조각은 0)
     */
    public record VideoPiece(String sourceFile, long startPts, long endPts, long resultStartTicks) {

        public VideoPiece {
            if (endPts <= startPts) {
                throw new IllegalArgumentException("piece keeps no frame: " + sourceFile + " " + startPts + ".."
                        + endPts);
            }
        }
    }

    /**
     * 새 꼴 소리 묶음 하나 — 같은 녹화로 이어진 조각들을 한 흐름으로 디코드하는 concat demuxer 입력과 그 흐름에서 자를 값.
     * 표본 값은 모두 {@code aresample=48000} 출력 단위(1/48000)다.
     *
     * @param primeFile         묶음 머리 앞 디코더 준비 조각. 없으면 null
     * @param segmentFiles      묶음 조각(차례대로 · 하나 이상)
     * @param captureFile       마지막 묶음 끝 확보 조각. 없으면 null
     * @param concatDurationsUs 목록에 적는 파일 사이 간격(µs) — 준비 · 조각 · 확보를 적는 차례대로, 파일 수 − 1 개
     * @param fromSample        흐름에서 자를 시작
     * @param toSample          흐름에서 자를 끝(배타)
     * @param lengthSamples     결과에서 이 묶음이 차지하는 길이(다음 묶음 결과 자리 − 이 묶음 결과 자리)
     * @param silentTrackIds    이 묶음 전체에 패킷이 없는 트랙 — 그 트랙은 정확한 길이의 무음으로 채운다
     */
    public record AudioRunInput(String primeFile, List<String> segmentFiles, String captureFile,
                                List<Long> concatDurationsUs, long fromSample, long toSample, long lengthSamples,
                                Set<Integer> silentTrackIds) {

        public AudioRunInput {
            segmentFiles = List.copyOf(segmentFiles);
            concatDurationsUs = List.copyOf(concatDurationsUs);
            silentTrackIds = Set.copyOf(silentTrackIds);
            if (segmentFiles.isEmpty()) {
                throw new IllegalArgumentException("audio run has no segment");
            }
            int files = segmentFiles.size() + (primeFile == null ? 0 : 1) + (captureFile == null ? 0 : 1);
            if (concatDurationsUs.size() != files - 1) {
                throw new IllegalArgumentException("audio run needs " + (files - 1) + " durations, got "
                        + concatDurationsUs.size());
            }
            if (concatDurationsUs.stream().anyMatch(d -> d <= 0)) {
                throw new IllegalArgumentException("audio run duration must be positive: " + concatDurationsUs);
            }
            if (toSample <= fromSample || lengthSamples <= 0) {
                throw new IllegalArgumentException("audio run is empty: " + fromSample + ".." + toSample + " length "
                        + lengthSamples);
            }
        }

        /** 목록에 적는 차례 — 준비 조각은 맨 앞에만, 확보 조각은 맨 끝에만. */
        List<String> files() {
            List<String> files = new ArrayList<>();
            if (primeFile != null) {
                files.add(primeFile);
            }
            files.addAll(segmentFiles);
            if (captureFile != null) {
                files.add(captureFile);
            }
            return files;
        }
    }

    /** 조각 단계 출력 이름(작업 폴더 안 · 차례 번호). */
    public static String midFile(int index) {
        return String.format(Locale.ROOT, "mid_%03d.nut", index);
    }

    /** 소리 묶음 목록 이름(작업 폴더 안 · 묶음 번호). */
    public static String runListFile(int index) {
        return "run_" + index + ".ffconcat";
    }

    /**
     * 조각 단계 — 원본 조각 하나에서 영상만 원래 틱으로 잘라 0 에서 시작하는 무손실 중간 파일로 쓴다.
     *
     * <p>{@code -copyts}로 입력 시각을 옮기지 않아 trim 값이 파일의 원래 틱 정수 그대로다(시간 창 번역 · 반올림 없음).
     * {@code -enc_time_base}로 중간 파일 시간 단위를 원래 틱에 두고 {@code -fps_mode passthrough}로 프레임을 복제 · 버림 없이
     * 넘긴다. 입력 {@code -threads 1}은 디코더 프레임 스레드 버퍼를 줄인다.
     *
     * @param timescale 그 조각 영상 time_base 분모(틱/초)
     */
    public static List<String> cutPiece(String ffmpeg, VideoPiece piece, int index, long timescale) {
        long start = piece.startPts();
        String shift = start >= 0 ? "-" + start : "+" + (-start);
        return List.of(ffmpeg, "-hide_banner", "-nostdin", "-y", "-copyts", "-threads", "1", "-i", piece.sourceFile(),
                "-map", "0:v:0", "-an", "-sn", "-dn",
                "-vf", "trim=start_pts=" + start + ":end_pts=" + piece.endPts() + ",setpts=PTS" + shift,
                "-fps_mode", "passthrough", "-enc_time_base", "1/" + timescale,
                "-c:v", "ffv1", "-level", "3", "-g", "1", "-f", "nut", midFile(index));
    }

    /**
     * 중간 파일 이음 목록({@link #JOIN_LIST}에 쓸 내용). 파일 j 다음 간격은 {@code rnd(R_{j+1} µs) − rnd(R_j µs)}다(R = 결과
     * 축 틱). 누적 정확값에서 반올림해 concat demuxer 가 파일 j 에 더하는 µs 오프셋이 {@code rnd(R_j µs)}가 되고, 그것을 원래
     * 틱으로 되돌리면 R_j 그대로다(반올림 0.5µs가 반 틱보다 작다). 간격은 {@link #concatList}가 초 문자열로 적는다.
     *
     * @throws IllegalArgumentException 조각이 없거나, 첫 조각이 결과 0 이 아니거나, 간격이 앞 중간 파일 폭보다 좁으면
     */
    public static String joinList(List<VideoPiece> pieces, long timescale) {
        if (pieces.isEmpty()) {
            throw new IllegalArgumentException("no video piece to join");
        }
        if (pieces.getFirst().resultStartTicks() != 0) {
            throw new IllegalArgumentException("first piece must start the result: "
                    + pieces.getFirst().resultStartTicks());
        }
        List<String> names = new ArrayList<>();
        List<Long> durationsUs = new ArrayList<>();
        for (int j = 0; j < pieces.size(); j++) {
            names.add(midFile(j));
            if (j + 1 < pieces.size()) {
                VideoPiece piece = pieces.get(j);
                long next = pieces.get(j + 1).resultStartTicks();
                if (next - piece.resultStartTicks() < piece.endPts() - piece.startPts()) {
                    throw new IllegalArgumentException("piece " + (j + 1) + " starts inside piece " + j);
                }
                durationsUs.add(roundedMicros(next, timescale) - roundedMicros(piece.resultStartTicks(), timescale));
            }
        }
        return concatList(names, durationsUs);
    }

    /** 소리 묶음 목록({@link #runListFile}에 쓸 내용). 스트림 복사라 묶음 조각의 소리 패킷이 한 디코더로 이어 들어간다. */
    public static String runList(AudioRunInput run) {
        return concatList(run.files(), run.concatDurationsUs());
    }

    /** 새 꼴 소리 크기 재기 — 소리 묶음 입력만 읽는다(입력 r = 묶음 r). */
    public static List<String> measureLoudnessJoined(String ffmpeg, List<AudioRunInput> runs, List<AudioTrack> tracks) {
        List<String> cmd = new ArrayList<>(List.of(ffmpeg, "-hide_banner", "-nostdin"));
        addRunInputs(cmd, runs);
        cmd.addAll(List.of("-filter_complex", runGraph(runs, tracks, 0, Loudness.measureFilter()),
                "-map", "[aout]", "-f", "null", "-"));
        return cmd;
    }

    /**
     * 새 꼴 최종 단계 — 출력 하나. 입력 0 = 중간 파일 이음 목록, 1 + r = 소리 묶음 r, 그 뒤 = 꾸밈 그림.
     *
     * <p>출력 쪽 세 옵션이 필수다. {@code -enc_time_base:v 1/<timescale>} — 이음 목록 입력은 프레임률(30)을 실어 인코더 시간
     * 단위 기본이 1/30이 되고, 그러면 모든 프레임 시각이 1/30초 격자로 반올림된다(실측 최대 1.000ms). 출력 {@code -threads 1}과
     * {@code -x264-params threads=1:lookahead_threads=1} — 인코더 한 스레드(렌더 일꾼 메모리 상한 안에 사진 ffmpeg 와 함께
     * 들어가려고 고정한 값). 셋 다 실측한 명령과 같은 자리 · 같은 차례다.
     *
     * @param timescale 영상 원래 틱(조각 단계와 같은 값)
     * @param video     {@link Composition#plan}의 결과 — 그림 입력 번호가 1 + 묶음 수부터여야 한다
     * @throws IllegalArgumentException 묶음이 없거나 그림 입력 번호가 묶음 뒤가 아니면
     */
    public static List<String> renderJoined(String ffmpeg, long timescale, Composition.Plan video,
                                            List<AudioRunInput> runs, List<AudioTrack> tracks, String loudnessFix,
                                            String burnFile, String fontsDir, String outputFile) {
        if (runs.isEmpty()) {
            throw new IllegalArgumentException("no audio run");
        }
        if (video.firstImageInput() != 1 + runs.size()) {
            throw new IllegalArgumentException("images must follow the " + runs.size() + " audio runs, not start at "
                    + video.firstImageInput());
        }
        List<String> cmd = new ArrayList<>(List.of(ffmpeg, "-hide_banner", "-nostdin", "-y",
                "-copyts", "-threads", "1", "-f", "concat", "-safe", "0", "-i", JOIN_LIST));
        addRunInputs(cmd, runs);
        for (Composition.Image image : video.images()) {
            cmd.addAll(List.of("-loop", "1", "-i", image.name()));
        }
        String graph = videoChain(video, burnFile, fontsDir) + "[vout];"
                + runGraph(runs, tracks, 1, finalAudioTail(loudnessFix, runs));
        cmd.addAll(List.of("-filter_complex", graph, "-map", "[vout]", "-map", "[aout]",
                "-fps_mode", "passthrough", "-threads", "1", "-enc_time_base:v", "1/" + timescale,
                "-c:v", "libx264", "-x264-params", "threads=1:lookahead_threads=1",
                "-preset", "veryfast", "-crf", "20", "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "160k",
                "-movie_timescale", "90000", "-movflags", "+faststart", outputFile));
        return cmd;
    }

    /**
     * 최종 단계 소리 꼬리 — 평준화(있으면) 뒤 {@link #LOUDNESS_SETTLE}로 시각을 표본 수로 매기고 48kHz 로 되돌린 다음, 결과 끝
     * 표본(묶음 길이 합)에서 자른다. 새 꼴에는 출력 {@code -t}가 없어 이 꼬리가 결과 소리의 끝이다. 평준화가 없을 때(무음)도 같은
     * 꼬리를 둬 {@code [aout]}의 끝을 한 곳에서 정한다.
     */
    static String finalAudioTail(String loudnessFix, List<AudioRunInput> runs) {
        long endSample = 0;
        for (AudioRunInput run : runs) {
            endSample = Math.addExact(endSample, run.lengthSamples());
        }
        String settle = LOUDNESS_SETTLE + ",atrim=end_sample=" + endSample;
        return loudnessFix == null ? settle : loudnessFix + "," + settle;
    }

    private static void addRunInputs(List<String> cmd, List<AudioRunInput> runs) {
        for (int r = 0; r < runs.size(); r++) {
            cmd.addAll(List.of("-copyts", "-f", "concat", "-safe", "0", "-i", runListFile(r)));
        }
    }

    /**
     * 고른 트랙마다 묶음 구간을 잇고 섞는다. 구간은 흐름에서 표본 정수로 자른 뒤 0 에서 시작시키고 정확한 길이로 끝낸다 —
     * 그래야 {@code concat}이 누적 길이로 다음 구간을 놓는 자리가 계획 자리와 같다.
     *
     * @param firstRunInput 묶음 0 의 ffmpeg 입력 번호
     */
    private static String runGraph(List<AudioRunInput> runs, List<AudioTrack> tracks, int firstRunInput,
                                   String tail) {
        StringBuilder graph = new StringBuilder();
        for (AudioTrack t : tracks) {
            int n = t.trackId();
            StringBuilder labels = new StringBuilder();
            for (int r = 0; r < runs.size(); r++) {
                AudioRunInput run = runs.get(r);
                String label = "[a" + r + "_" + n + "]";
                if (run.silentTrackIds().contains(n)) {
                    graph.append("anullsrc=r=48000:cl=stereo,atrim=end_sample=").append(run.lengthSamples());
                } else {
                    graph.append('[').append(firstRunInput + r).append(":a:").append(n)
                            .append("]aresample=48000:async=1,atrim=start_pts=").append(run.fromSample())
                            .append(":end_pts=").append(run.toSample())
                            .append(",asetpts=PTS-STARTPTS,aformat=sample_rates=48000:channel_layouts=stereo,apad,")
                            .append("atrim=end_sample=").append(run.lengthSamples());
                }
                graph.append(label).append(';');
                labels.append(label);
            }
            graph.append(labels).append("concat=n=").append(runs.size()).append(":v=0:a=1[acat").append(n)
                    .append("];");
        }
        return graph.append(mixGraph(tracks, n -> "[acat" + n + "]", tail)).toString();
    }

    /**
     * 고른 트랙만 섞는다(계약6 2절: 넣은 트랙만 믹스, BGM 제외 = 그 트랙을 빼기). trackId N = N번째 소리 스트림.
     * 섞은 뒤 48kHz 스테레오로 맞추고, {@code tail}(평준화 필터)이 있으면 붙인다. 끝 라벨은 {@code [aout]}.
     */
    static String audioGraph(List<AudioTrack> tracks, String tail) {
        return mixGraph(tracks, n -> "[0:a:" + n + "]", tail);
    }

    /** {@link #audioGraph}의 몸통. {@code trackInput}이 트랙 번호를 그 트랙 소리의 필터 이름표로 바꾼다. */
    private static String mixGraph(List<AudioTrack> tracks, IntFunction<String> trackInput, String tail) {
        StringBuilder graph = new StringBuilder();
        for (AudioTrack t : tracks) {
            graph.append(trackInput.apply(t.trackId())).append("volume=").append(decimal(t.gain()))
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

    /** 꾸밈 필터 뒤에 번인 자막을 잇는다. 출력 이름표는 부르는 쪽이 붙인다. */
    private static String videoChain(Composition.Plan video, String burnFile, String fontsDir) {
        String chain = video.graph();
        if (burnFile != null) {
            chain += ",subtitles=" + burnFile
                    + (fontsDir == null || fontsDir.isBlank() ? "" : ":fontsdir=" + fontsDir)
                    + (burnFile.equals(BURN_SRT) ? ":force_style='" + BURN_STYLE + "'" : "");
        }
        return chain;
    }

    /** 틱 → µs 반올림(반 올림). 결과 축 틱은 0 이상이다. */
    private static long roundedMicros(long ticks, long timescale) {
        return Math.floorDiv(2 * ticks * 1_000_000 + timescale, 2 * timescale);
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
