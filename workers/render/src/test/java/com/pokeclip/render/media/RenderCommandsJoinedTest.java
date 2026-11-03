package com.pokeclip.render.media;

import com.pokeclip.render.media.RenderCommands.AudioRunInput;
import com.pokeclip.render.media.RenderCommands.VideoPiece;
import com.pokeclip.render.recipe.Recipe.AudioTrack;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 새 꼴 주문의 두 단계 명령(조각 단계 → 이음 목록 → 최종 단계). 회귀 막기 문자열 시험이다 — 옵션 하나가 빠지거나 자리가 바뀌면
 * 실제 결과의 프레임 시각 · 메모리가 틀어지는 것들이라, 문자열로 먼저 못 박는다.
 */
class RenderCommandsJoinedTest {

    private static final long TS = 90_000;
    private static final List<AudioTrack> TWO_TRACKS = List.of(new AudioTrack(1, 1.0), new AudioTrack(3, 0.6));

    /** 첫 묶음: 같은 녹화 두 조각 · 준비 · 확보 없음. */
    private static final AudioRunInput RUN_0 = new AudioRunInput(null, List.of("seg_1.mp4", "seg_2.mp4"), null,
            List.of(4_000_000L), 96, 384_096, 384_000, Set.of());
    /** 둘째(마지막) 묶음: 머리 앞 준비 조각 · 끝 확보 조각 · 트랙 3 은 이 묶음 전체에 패킷이 없다. */
    private static final AudioRunInput RUN_1 = new AudioRunInput("seg_4.mp4", List.of("seg_5.mp4"), "seg_6.mp4",
            List.of(3_990_000L, 4_000_000L), 192_000, 288_000, 96_000, Set.of(3));

    private static final String CHAIN_TAIL = "asetpts=PTS-STARTPTS,aformat=sample_rates=48000:channel_layouts=stereo,"
            + "apad,atrim=end_sample=";

    @Test
    void 조각_단계는_원래_틱으로_잘라_FFV1_NUT_중간_파일에_쓴다() {
        assertThat(RenderCommands.cutPiece("ffmpeg", new VideoPiece("seg_12.mp4", 183_003, 540_004, 0), 0, TS))
                .containsExactly("ffmpeg", "-hide_banner", "-nostdin", "-y", "-copyts", "-threads", "1", "-i",
                        "seg_12.mp4", "-map", "0:v:0", "-an", "-sn", "-dn", "-vf",
                        "trim=start_pts=183003:end_pts=540004,setpts=PTS-183003", "-fps_mode", "passthrough",
                        "-enc_time_base", "1/90000", "-c:v", "ffv1", "-level", "3", "-g", "1", "-f", "nut",
                        "mid_000.nut");
    }

    @Test
    void 조각_단계_중간_파일_이름은_차례_번호다() {
        assertThat(RenderCommands.cutPiece("ffmpeg", new VideoPiece("a.mp4", 0, 3_001, 0), 12, TS))
                .last().isEqualTo("mid_012.nut");
        assertThat(RenderCommands.midFile(12)).isEqualTo("mid_012.nut");
    }

    @Test
    void 조각_단계는_음수_시작_틱도_0_으로_옮긴다() {
        assertThat(RenderCommands.cutPiece("ffmpeg", new VideoPiece("a.mp4", -3_003, 3_001, 0), 0, TS))
                .contains("trim=start_pts=-3003:end_pts=3001,setpts=PTS+3003");
    }

    @Test
    void 조각_단계에는_시간_창_옵션이_없다() {
        List<String> cmd = RenderCommands.cutPiece("ffmpeg", new VideoPiece("a.mp4", 0, 3_001, 0), 0, TS);
        assertThat(cmd).doesNotContain("-ss", "-t", "-to");
        assertThat(String.join(" ", cmd)).doesNotContain("tpad");
    }

    @Test
    void 영상_프레임이_없는_조각은_조각_단계에_넣지_않는다() {
        assertThatThrownBy(() -> new VideoPiece("a.mp4", 3_000, 3_000, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 이음_목록_간격은_초_문자열이다() {
        // 결과 축 4초(360000틱) = 4,000,000µs → 파일에는 초 문자열 4.000000. µs 정수를 그대로 쓰면 10⁶ 배 간격이 된다
        assertThat(RenderCommands.joinList(List.of(new VideoPiece("a.mp4", 0, 357_001, 0),
                new VideoPiece("b.mp4", 0, 357_001, 360_000)), TS))
                .isEqualTo("ffconcat version 1.0\nfile 'mid_000.nut'\nduration 4.000000\nfile 'mid_001.nut'\n");
    }

    @Test
    void 이음_목록_간격은_누적_정확값의_마이크로초_반올림_차다() {
        // R_k = 360001·k 틱 = 4000011.1·k µs. 조각마다 반올림하면 늘 4.000011 이지만, 누적값에서 내면 다섯째에 반올림이
        // 올라가 4.000012 가 된다(20000055.6 → 20000056) — 그래야 파일 k 의 오프셋이 rnd(R_k µs) 그대로다
        List<VideoPiece> pieces = new ArrayList<>();
        for (int k = 0; k < 6; k++) {
            pieces.add(new VideoPiece("s" + k + ".mp4", 0, 360_000, 360_001L * k));
        }
        assertThat(RenderCommands.joinList(pieces, TS)).isEqualTo("ffconcat version 1.0\n"
                + "file 'mid_000.nut'\nduration 4.000011\nfile 'mid_001.nut'\nduration 4.000011\n"
                + "file 'mid_002.nut'\nduration 4.000011\nfile 'mid_003.nut'\nduration 4.000011\n"
                + "file 'mid_004.nut'\nduration 4.000012\nfile 'mid_005.nut'\n");
    }

    @Test
    void 이음_목록은_결과_0_에서_시작하지_않으면_거부한다() {
        assertThatThrownBy(() -> RenderCommands.joinList(List.of(new VideoPiece("a.mp4", 0, 3_001, 3_000)), TS))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 이음_목록은_간격이_앞_중간_파일_폭보다_좁으면_거부한다() {
        // R_1 − R_0 ≥ e_0 − a_0 > 0 — 어기면 다음 파일이 앞 파일 마지막 프레임 앞에 놓인다
        assertThatThrownBy(() -> RenderCommands.joinList(List.of(new VideoPiece("a.mp4", 0, 360_000, 0),
                new VideoPiece("b.mp4", 0, 3_001, 359_999)), TS)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RenderCommands.joinList(List.of(), TS)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 묶음_목록은_준비_조각을_맨_앞에_확보_조각을_맨_끝에만_둔다() {
        assertThat(RenderCommands.runList(RUN_1)).isEqualTo("ffconcat version 1.0\nfile 'seg_4.mp4'\n"
                + "duration 3.990000\nfile 'seg_5.mp4'\nduration 4.000000\nfile 'seg_6.mp4'\n");
        assertThat(RenderCommands.runList(RUN_0)).isEqualTo("ffconcat version 1.0\nfile 'seg_1.mp4'\n"
                + "duration 4.000000\nfile 'seg_2.mp4'\n");
        assertThat(RenderCommands.runListFile(1)).isEqualTo("run_1.ffconcat");
    }

    @Test
    void 묶음_간격_수가_파일_수와_맞지_않거나_0_이하면_거부한다() {
        assertThatThrownBy(() -> new AudioRunInput("p.mp4", List.of("a.mp4"), null, List.of(), 0, 10, 10, Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AudioRunInput(null, List.of("a.mp4", "b.mp4"), null, List.of(0L), 0, 10, 10,
                Set.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AudioRunInput(null, List.of(), null, List.of(), 0, 10, 10, Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AudioRunInput(null, List.of("a.mp4"), null, List.of(), 0, 10, 0, Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 최종_단계_전체_명령() {
        assertThat(finalCommand()).containsExactly("ffmpeg", "-hide_banner", "-nostdin", "-y",
                "-copyts", "-threads", "1", "-f", "concat", "-safe", "0", "-i", "mid_list.ffconcat",
                "-copyts", "-f", "concat", "-safe", "0", "-i", "run_0.ffconcat",
                "-copyts", "-f", "concat", "-safe", "0", "-i", "run_1.ffconcat",
                "-loop", "1", "-i", "o1_a.png",
                "-filter_complex", "[0:v:0]setsar=1,[3:v]overlay,subtitles=burn.srt:force_style='"
                        + RenderCommands.BURN_STYLE + "'[vout];"
                        + "[1:a:1]aresample=48000:async=1,atrim=start_pts=96:end_pts=384096," + CHAIN_TAIL
                        + "384000[a0_1];"
                        + "[2:a:1]aresample=48000:async=1,atrim=start_pts=192000:end_pts=288000," + CHAIN_TAIL
                        + "96000[a1_1];"
                        + "[a0_1][a1_1]concat=n=2:v=0:a=1[acat1];"
                        + "[1:a:3]aresample=48000:async=1,atrim=start_pts=96:end_pts=384096," + CHAIN_TAIL
                        + "384000[a0_3];"
                        + "anullsrc=r=48000:cl=stereo,atrim=end_sample=96000[a1_3];"
                        + "[a0_3][a1_3]concat=n=2:v=0:a=1[acat3];"
                        + "[acat1]volume=1.000[a1];[acat3]volume=0.600[a3];[a1][a3]"
                        + "amix=inputs=2:duration=longest:normalize=0,"
                        + "aformat=sample_rates=48000:channel_layouts=stereo,loudnorm=FIX,"
                        + "asetpts=N/SR/TB,aresample=48000,atrim=end_sample=480000[aout]",
                "-map", "[vout]", "-map", "[aout]", "-fps_mode", "passthrough",
                "-threads", "1", "-enc_time_base:v", "1/90000",
                "-c:v", "libx264", "-x264-params", "threads=1:lookahead_threads=1",
                "-preset", "veryfast", "-crf", "20", "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "160k",
                "-movie_timescale", "90000", "-movflags", "+faststart", "o1.mp4");
    }

    @Test
    void 최종_단계_인코더는_한_스레드다_출력_threads_와_x264_params_둘_다() {
        List<String> out = outputSide(finalCommand());
        assertThat(out).containsSequence("-threads", "1");
        assertThat(out).containsSequence("-x264-params", "threads=1:lookahead_threads=1");
        assertThat(out.indexOf("-threads")).isLessThan(out.indexOf("-c:v"));
        assertThat(out.indexOf("-x264-params")).isEqualTo(out.indexOf("libx264") + 1);
    }

    @Test
    void 최종_단계_인코더_시간_단위는_원래_틱이다() {
        // 없으면 이음 목록 입력의 프레임률(30)로 1/30 격자가 되어 프레임이 최대 1.000ms 어긋난다
        List<String> out = outputSide(finalCommand());
        assertThat(out).containsSequence("-enc_time_base:v", "1/90000");
        assertThat(out.indexOf("-enc_time_base:v")).isLessThan(out.indexOf("-c:v"));
        assertThat(out).containsSequence("-fps_mode", "passthrough");
        assertThat(out).containsSequence("-movie_timescale", "90000");
    }

    @Test
    void 최종_단계_영상_입력은_이음_목록_하나뿐이고_시간_창이_없다() {
        List<String> cmd = finalCommand();
        List<String> inputs = inputs(cmd);
        assertThat(inputs).containsExactly("mid_list.ffconcat", "run_0.ffconcat", "run_1.ffconcat", "o1_a.png");
        assertThat(cmd).doesNotContain("-ss", "-t", "-to", "seg_1.mp4", "seg_5.mp4");
        String graph = cmd.get(cmd.indexOf("-filter_complex") + 1);
        assertThat(graph).doesNotContain("tpad", ":v=1", "interleave").contains("[0:v:0]");
        assertThat(graph.split("concat=", -1)).hasSize(1 + TWO_TRACKS.size());
    }

    @Test
    void 소리는_묶음마다_입력_하나이고_구간마다_0_에서_시작한다() {
        List<String> cmd = finalCommand();
        assertThat(inputs(cmd).stream().filter(i -> i.startsWith("run_"))).hasSize(2);
        String graph = cmd.get(cmd.indexOf("-filter_complex") + 1);
        // 패킷 있는 구간 셋(묶음 0 두 트랙 · 묶음 1 트랙 1)마다 concat 앞에서 0 시작으로 옮긴다
        assertThat(graph.split("asetpts=PTS-STARTPTS", -1)).hasSize(4);
        assertThat(graph).contains("[1:a:1]", "[2:a:1]", "[1:a:3]").doesNotContain("[0:a:", "[2:a:3]", "[3:a:");
    }

    /**
     * 평준화가 동적 모드로 떨어지면(최고점 대 크기 비 12.5dB 초과 · 잰 LRA 11 초과 · 잰 LRA 0 — linear=true 여도) loudnorm 이
     * 192kHz 로 내보내고 입력 끝 2.9초 앞 자리에서 시각이 최대 100ms 뛴다 — 표본은 빠짐없이 이어진다. 그래서 평준화 뒤에 시각을
     * 표본 수로 다시 매기고 48kHz 로 되돌리고 결과 끝 표본(묶음 길이 합)에서 자른다.
     */
    @Test
    void 최종_단계_소리_끝은_표본_수_시각_48kHz_결과_끝_표본이다() {
        String graph = finalCommand().get(finalCommand().indexOf("-filter_complex") + 1);
        assertThat(graph).endsWith(",loudnorm=FIX,asetpts=N/SR/TB,aresample=48000,atrim=end_sample="
                + (RUN_0.lengthSamples() + RUN_1.lengthSamples()) + "[aout]");
    }

    @Test
    void 평준화가_없어도_소리_끝_정리는_같다() {
        List<String> cmd = RenderCommands.renderJoined("ffmpeg", TS, new Composition.Plan("[0:v:0]null", List.of(), 2),
                List.of(RUN_0), TWO_TRACKS, null, null, null, "o1.mp4");
        assertThat(cmd.get(cmd.indexOf("-filter_complex") + 1)).endsWith(
                "aformat=sample_rates=48000:channel_layouts=stereo,asetpts=N/SR/TB,aresample=48000,"
                        + "atrim=end_sample=384000[aout]");
    }

    @Test
    void 소리_크기_재기에는_소리_끝_정리가_없다() {
        List<String> cmd = RenderCommands.measureLoudnessJoined("ffmpeg", List.of(RUN_0), TWO_TRACKS);
        assertThat(cmd.get(cmd.indexOf("-filter_complex") + 1)).doesNotContain("asetpts=N/SR/TB");
    }

    @Test
    void 패킷_없는_묶음_트랙은_정확한_길이의_무음이다() {
        String graph = finalCommand().get(finalCommand().indexOf("-filter_complex") + 1);
        assertThat(graph).contains("anullsrc=r=48000:cl=stereo,atrim=end_sample=96000[a1_3]");
    }

    @Test
    void 꾸밈_그림_번호가_묶음_뒤가_아니면_거부한다() {
        // 그림이 [1:v] 를 읽으면 첫 소리 묶음 입력을 그림으로 읽는다
        Composition.Plan wrong = new Composition.Plan("[0:v:0]setsar=1,[1:v]overlay", List.of(image("o1_a.png")), 1);
        assertThatThrownBy(() -> RenderCommands.renderJoined("ffmpeg", TS, wrong, List.of(RUN_0, RUN_1), TWO_TRACKS,
                null, null, null, "o1.mp4")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 최종_단계_자막_파일이_ASS_면_모양을_덧입히지_않고_글꼴_폴더를_붙인다() {
        Composition.Plan plain = new Composition.Plan("[0:v:0]null", List.of(), 2);
        List<String> cmd = RenderCommands.renderJoined("ffmpeg", TS, plain, List.of(RUN_0), TWO_TRACKS, null,
                "o1.ass", "/fonts", "o1.mp4");
        assertThat(cmd.get(cmd.indexOf("-filter_complex") + 1))
                .startsWith("[0:v:0]null,subtitles=o1.ass:fontsdir=/fonts[vout];[1:a:1]");
    }

    @Test
    void 소리_크기_재기는_묶음_입력만_읽는다() {
        assertThat(RenderCommands.measureLoudnessJoined("ffmpeg", List.of(RUN_0, RUN_1), List.of(new AudioTrack(1,
                1.0)))).containsExactly("ffmpeg", "-hide_banner", "-nostdin",
                "-copyts", "-f", "concat", "-safe", "0", "-i", "run_0.ffconcat",
                "-copyts", "-f", "concat", "-safe", "0", "-i", "run_1.ffconcat",
                "-filter_complex",
                "[0:a:1]aresample=48000:async=1,atrim=start_pts=96:end_pts=384096," + CHAIN_TAIL + "384000[a0_1];"
                        + "[1:a:1]aresample=48000:async=1,atrim=start_pts=192000:end_pts=288000," + CHAIN_TAIL
                        + "96000[a1_1];"
                        + "[a0_1][a1_1]concat=n=2:v=0:a=1[acat1];"
                        + "[acat1]volume=1.000[a1];[a1]aformat=sample_rates=48000:channel_layouts=stereo,"
                        + Loudness.measureFilter() + "[aout]",
                "-map", "[aout]", "-f", "null", "-");
    }

    private static List<String> finalCommand() {
        Composition.Plan plan = new Composition.Plan("[0:v:0]setsar=1,[3:v]overlay", List.of(image("o1_a.png")), 3);
        return RenderCommands.renderJoined("ffmpeg", TS, plan, List.of(RUN_0, RUN_1), TWO_TRACKS, "loudnorm=FIX",
                RenderCommands.BURN_SRT, null, "o1.mp4");
    }

    private static Composition.Image image(String name) {
        return new Composition.Image(name, new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB));
    }

    /** {@code -filter_complex} 값 뒤 — 출력 쪽 옵션. 입력 쪽 {@code -threads 1} 과 가르려고 쓴다. */
    private static List<String> outputSide(List<String> cmd) {
        return cmd.subList(cmd.indexOf("-filter_complex") + 2, cmd.size());
    }

    private static List<String> inputs(List<String> cmd) {
        List<String> inputs = new ArrayList<>();
        for (int i = 0; i < cmd.size() - 1; i++) {
            if (cmd.get(i).equals("-i")) {
                inputs.add(cmd.get(i + 1));
            }
        }
        return inputs;
    }
}
