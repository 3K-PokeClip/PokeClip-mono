package com.pokeclip.render.media;

import com.pokeclip.render.media.RenderCommands.AudioRunInput;
import com.pokeclip.render.media.RenderCommands.TrackHead;
import com.pokeclip.render.media.RenderCommands.VideoPiece;
import com.pokeclip.render.media.SegmentTiming.ColorTags;
import com.pokeclip.render.recipe.Recipe.AudioTrack;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 새 꼴 주문의 두 단계 명령(조각 단계 → 이음 목록 → 최종 단계). 회귀 막기 문자열 시험이다 — 옵션 하나가 빠지거나 자리가 바뀌면
 * 실제 결과의 프레임 시각 · 메모리가 틀어지는 것들이라, 문자열로 먼저 못 박는다.
 */
class RenderCommandsJoinedTest {

    private static final long TS = 90_000;
    /** 조각 단계에 넘기는 중간 파일 바이트 상한(명령 꼴만 보는 임의 값 · 산식은 {@code JoinedDiskGuardTest}). */
    private static final long CAP = 864_000;
    private static final List<AudioTrack> TWO_TRACKS = List.of(new AudioTrack(1, 1.0), new AudioTrack(3, 0.6));

    /** 첫 묶음: 같은 녹화 두 조각 · 준비 · 확보 없음. 트랙 3 은 자를 구간 시작보다 64ms(3072표본) 늦게 시작한다. */
    private static final AudioRunInput RUN_0 = new AudioRunInput(null, List.of("seg_1.mp4", "seg_2.mp4"), null,
            List.of(4_000_000L), 96, 384_096, 384_000, Set.of(),
            Map.of(1, new TrackHead(48_000, 0), 3, new TrackHead(48_000, 3_072)));
    /** 둘째(마지막) 묶음: 머리 앞 준비 조각 · 끝 확보 조각 · 트랙 3 은 이 묶음 전체에 패킷이 없다. 입력은 44.1kHz. */
    private static final AudioRunInput RUN_1 = new AudioRunInput("seg_4.mp4", List.of("seg_5.mp4"), "seg_6.mp4",
            List.of(3_990_000L, 4_000_000L), 192_000, 288_000, 96_000, Set.of(3),
            Map.of(1, new TrackHead(44_100, 512)));

    /** 48kHz 입력의 흐름 안 채움 문턱 = AAC 반 프레임(512 ÷ 48000초). */
    private static final String RESAMPLE_48K = "aresample=48000:async=1:min_hard_comp=0.010667";
    private static final String RESAMPLE_44K = "aresample=48000:async=1:min_hard_comp=0.011610";
    private static final String FORMAT_TAIL =
            "aformat=sample_rates=48000:channel_layouts=stereo,apad,atrim=end_sample=";

    @Test
    void 조각_단계는_원래_틱으로_잘라_FFV1_NUT_중간_파일에_쓴다() {
        assertThat(RenderCommands.cutPiece("ffmpeg", new VideoPiece("seg_12.mp4", 183_003, 540_004, 0), 0, TS,
                CAP)).containsExactly("ffmpeg", "-hide_banner", "-nostdin", "-benchmark", "-y", "-copyts", "-threads",
                "1", "-i", "seg_12.mp4", "-map", "0:v:0", "-an", "-sn", "-dn", "-vf",
                "trim=start_pts=183003:end_pts=540004,setpts=PTS-183003", "-fps_mode", "passthrough",
                "-enc_time_base", "1/90000", "-c:v", "ffv1", "-level", "3", "-g", "1", "-fs", "864000", "-f", "nut",
                "mid_000.nut");
    }

    /** 중간 파일 바이트 상한(D35)은 출력 옵션이다 — 출력 이름 바로 앞 꼴 옵션 앞에 그대로 싣는다. */
    @Test
    void 조각_단계_출력에는_준_바이트_상한이_실린다() {
        assertThat(RenderCommands.cutPiece("ffmpeg", new VideoPiece("a.mp4", 0, 3_001, 0), 0, TS, 12_345_678_901L))
                .containsSequence("-fs", "12345678901", "-f", "nut", "mid_000.nut");
    }

    @Test
    void 조각_단계_중간_파일_이름은_차례_번호다() {
        assertThat(RenderCommands.cutPiece("ffmpeg", new VideoPiece("a.mp4", 0, 3_001, 0), 12, TS, CAP))
                .last().isEqualTo("mid_012.nut");
        assertThat(RenderCommands.midFile(12)).isEqualTo("mid_012.nut");
    }

    @Test
    void 조각_단계는_음수_시작_틱도_0_으로_옮긴다() {
        assertThat(RenderCommands.cutPiece("ffmpeg", new VideoPiece("a.mp4", -3_003, 3_001, 0), 0, TS, CAP))
                .contains("trim=start_pts=-3003:end_pts=3001,setpts=PTS+3003");
    }

    @Test
    void 조각_단계에는_시간_창_옵션이_없다() {
        List<String> cmd = RenderCommands.cutPiece("ffmpeg", new VideoPiece("a.mp4", 0, 3_001, 0), 0, TS, CAP);
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
        assertThatThrownBy(() -> new AudioRunInput("p.mp4", List.of("a.mp4"), null, List.of(), 0, 10, 10, Set.of(),
                Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AudioRunInput(null, List.of("a.mp4", "b.mp4"), null, List.of(0L), 0, 10, 10,
                Set.of(), Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AudioRunInput(null, List.of(), null, List.of(), 0, 10, 10, Set.of(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AudioRunInput(null, List.of("a.mp4"), null, List.of(), 0, 10, 0, Set.of(),
                Map.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 최종_단계_전체_명령() {
        assertThat(finalCommand()).containsExactly("ffmpeg", "-hide_banner", "-nostdin", "-benchmark", "-y",
                "-copyts", "-threads", "1", "-f", "concat", "-i", "mid_list.ffconcat",
                "-copyts", "-f", "concat", "-i", "run_0.ffconcat",
                "-copyts", "-f", "concat", "-i", "run_1.ffconcat",
                "-loop", "1", "-i", "o1_a.png",
                "-filter_complex", "[0:v:0]setsar=1,[3:v]overlay,subtitles=burn.srt:force_style='"
                        + RenderCommands.BURN_STYLE + "'[vout];"
                        + "[1:a:1]" + RESAMPLE_48K + ",atrim=start_pts=96:end_pts=384096,asetpts=PTS-STARTPTS,"
                        + "adelay=delays=0S:all=1," + FORMAT_TAIL + "384000[a0_1];"
                        + "[2:a:1]" + RESAMPLE_44K + ",atrim=start_pts=192000:end_pts=288000,asetpts=PTS-STARTPTS,"
                        + "adelay=delays=512S:all=1," + FORMAT_TAIL + "96000[a1_1];"
                        + "[a0_1][a1_1]concat=n=2:v=0:a=1[acat1];"
                        + "[1:a:3]" + RESAMPLE_48K + ",atrim=start_pts=96:end_pts=384096,asetpts=PTS-STARTPTS,"
                        + "adelay=delays=3072S:all=1," + FORMAT_TAIL + "384000[a0_3];"
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

    /**
     * 색 표지 되살림(설계 13.5 「색 표지」 · 16.2 P34). FFV1 · NUT 중간 파일에는 색 칸이 없어 최종 단계 입력 프레임이 「미지정」 이다.
     * 원본 판독의 색 칸을 영상 사슬 머리 {@code setparams} 로 다시 붙인다 — 꾸밈 · 번인 필터가 옛 꼴(원본 디코드)과 같은 표지의
     * 프레임을 받고, libx264 가 SPS VUI 와 mp4 {@code colr} 에 그 값을 쓴다. 출력 {@code -color_*} 은 쓰지 않는다 — 8.0.1 에서
     * 전달 · 원색을 VUI 에 쓰지 않고 자동 scale 이 화소를 바꿨다(실측 · 보고 참고).
     */
    @Test
    void 색_표지가_있으면_영상_사슬_머리에_setparams_로_되살린다() {
        List<String> cmd = finalCommand(new ColorTags("tv", "bt709", "bt709", "bt709"));
        assertThat(cmd.get(cmd.indexOf("-filter_complex") + 1)).startsWith("[0:v:0]setparams=range=tv"
                + ":color_primaries=bt709:color_trc=bt709:colorspace=bt709,setsar=1,[3:v]overlay,subtitles=burn.srt:");
        assertThat(cmd).noneMatch(a -> a.startsWith("-color") || a.equals("-colorspace"));
        // 그래프 밖 명령은 색 표지가 없을 때와 바이트 같다
        List<String> plain = finalCommand();
        int graph = plain.indexOf("-filter_complex") + 1;
        assertThat(cmd.subList(0, graph)).isEqualTo(plain.subList(0, graph));
        assertThat(cmd.subList(graph + 1, cmd.size())).isEqualTo(plain.subList(graph + 1, plain.size()));
    }

    @Test
    void 범위_전체_원본도_그_값_그대로_되살린다() {
        List<String> cmd = finalCommand(new ColorTags("pc", "bt2020nc", "arib-std-b67", "bt2020"));
        assertThat(cmd.get(cmd.indexOf("-filter_complex") + 1)).startsWith("[0:v:0]setparams=range=pc"
                + ":color_primaries=bt2020:color_trc=arib-std-b67:colorspace=bt2020nc,setsar=1,");
    }

    @Test
    void 색_표지가_없으면_영상_사슬은_그대로다() {
        String graph = finalCommand().get(finalCommand().indexOf("-filter_complex") + 1);
        assertThat(graph).startsWith("[0:v:0]setsar=1,[3:v]overlay,").doesNotContain("setparams");
    }

    @Test
    void 판독에_있는_칸만_싣는다() {
        List<String> cmd = finalCommand(new ColorTags("tv", "bt709", null, null));
        assertThat(cmd.get(cmd.indexOf("-filter_complex") + 1))
                .startsWith("[0:v:0]setparams=range=tv:colorspace=bt709,setsar=1,");
    }

    /**
     * 모름 · 예약 값과 setparams 가 받지 않는 값은 싣지 않는다(그 칸은 지금처럼 미지정). 원본 파일 머리에서 온 글자라 그래프 문법
     * 글자({@code , : ; [ ]})가 끼면 필터를 덧붙일 수 있다 — 받는 값 목록 밖이면 모두 버린다.
     */
    @Test
    void 모르는_값이나_그래프_문법_글자가_든_값은_싣지_않는다() {
        List<String> none = finalCommand(new ColorTags("unknown", "reserved", "bt709,drawbox", "bt709[x];[x]null"));
        assertThat(none.get(none.indexOf("-filter_complex") + 1)).startsWith("[0:v:0]setsar=1,")
                .doesNotContain("setparams", "drawbox");
        List<String> some = finalCommand(new ColorTags("pc:range=tv", "bt709", "", "BT709"));
        assertThat(some.get(some.indexOf("-filter_complex") + 1)).startsWith("[0:v:0]setparams=colorspace=bt709,");
    }

    /** 신호 글자는 받는 값만 그대로 · 미지정은 unknown · 목록 밖(문법 글자 · 줄바꿈 · 대문자 포함)은 other 다. */
    @Test
    void 색_신호_글자는_받는_값_목록_밖이면_other_다() {
        assertThat(RenderCommands.colorLabel(new ColorTags("pc", "bt2020nc", "arib-std-b67", "bt2020")))
                .isEqualTo("pc/bt2020nc/arib-std-b67/bt2020");
        assertThat(RenderCommands.colorLabel(new ColorTags("pc\nx=1", null, "BT709", "bt709[x];[x]null")))
                .isEqualTo("other/unknown/other/other");
    }

    /** 버리는 칸은 값이 있는데 받는 값 목록 밖인 칸뿐이다(setparams 옵션 이름 · 그래프에 싣는 차례). */
    @Test
    void 버리는_색_칸은_값이_있는데_받는_값_목록_밖인_칸이다() {
        assertThat(RenderCommands.droppedColorOptions(new ColorTags("unknown", "vgamut", "bt709", "reserved")))
                .containsExactly("range", "color_primaries", "colorspace");
        assertThat(RenderCommands.droppedColorOptions(new ColorTags("tv", null, null, "bt709"))).isEmpty();
    }

    @Test
    void 영상_사슬이_원본_영상_입력에서_시작하지_않으면_색_표지를_붙이지_못해_거부한다() {
        Composition.Plan odd = new Composition.Plan("[0:v]null", List.of(), 2);
        ColorTags tv = new ColorTags("tv", "bt709", "bt709", "bt709");
        assertThatThrownBy(() -> RenderCommands.renderJoined("ffmpeg", TS, odd, tv, List.of(RUN_0), TWO_TRACKS, null,
                null, null, "o1.mp4")).isInstanceOf(IllegalArgumentException.class);
        // 표지가 없으면 붙일 것이 없어 그대로 둔다
        List<String> plain = RenderCommands.renderJoined("ffmpeg", TS, odd, ColorTags.UNKNOWN, List.of(RUN_0),
                TWO_TRACKS, null, null, null, "o1.mp4");
        assertThat(plain.get(plain.indexOf("-filter_complex") + 1)).startsWith("[0:v]null[vout];");
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
                ColorTags.UNKNOWN, List.of(RUN_0), TWO_TRACKS, null, null, null, "o1.mp4");
        assertThat(cmd.get(cmd.indexOf("-filter_complex") + 1)).endsWith(
                "aformat=sample_rates=48000:channel_layouts=stereo,asetpts=N/SR/TB,aresample=48000,"
                        + "atrim=end_sample=384000[aout]");
    }

    @Test
    void 소리_크기_재기에는_소리_끝_정리가_없다() {
        List<String> cmd = RenderCommands.measureLoudnessJoined("ffmpeg", List.of(RUN_0), TWO_TRACKS);
        assertThat(cmd.get(cmd.indexOf("-filter_complex") + 1)).doesNotContain("asetpts=N/SR/TB");
    }

    /**
     * 묶음 머리 앞 무음(설계 7.4 소리 묶음 5 · 7.5). {@code asetpts=PTS-STARTPTS} 는 구간 첫 표본을 0 에 놓으므로, 트랙이 자를 구간
     * 시작보다 늦게 시작하면 그만큼 앞당겨진다 — 그 차를 바로 뒤 {@code adelay} 가 표본 단위로 되돌린다. {@code all=1} 이 없으면
     * 첫 채널만 밀리고 나머지 채널은 앞당겨진 채 남는다(af_adelay {@code all} 기본 0 · 설계 16.2 P23).
     */
    @Test
    void 앞_무음은_0_시작_바로_뒤에서_모든_채널에_표본_단위로_넣는다() {
        String graph = finalCommand().get(finalCommand().indexOf("-filter_complex") + 1);
        assertThat(graph).contains("asetpts=PTS-STARTPTS,adelay=delays=3072S:all=1,aformat=")
                .contains("asetpts=PTS-STARTPTS,adelay=delays=0S:all=1,aformat=")
                .contains("asetpts=PTS-STARTPTS,adelay=delays=512S:all=1,aformat=");
        // 패킷 있는 구간 셋마다 하나 · 무음 대체 구간에는 없다
        assertThat(graph.split("adelay=", -1)).hasSize(4);
        assertThat(graph.split(":all=1,", -1)).hasSize(4);
        assertThat(graph).doesNotContain("anullsrc=r=48000:cl=stereo,adelay");
    }

    /**
     * 흐름 안 채움 문턱(설계 7.4 소리 묶음 5). 같은 녹화로 이어진 조각 사이에서 녹화기가 버린 표본(E9 — 64ms 실측)은 흐름 시각 틈으로
     * 남는다. {@code aresample} 의 기본 문턱(0.1초)은 그 틈을 채우지 않아 뒤 소리가 앞당겨지므로, 문턱을 입력 표본율의 AAC 반
     * 프레임(512표본)으로 명시한다.
     */
    @Test
    void 흐름_안_채움_문턱은_입력_표본율의_AAC_반_프레임이다() {
        String graph = finalCommand().get(finalCommand().indexOf("-filter_complex") + 1);
        assertThat(graph).contains("[1:a:1]aresample=48000:async=1:min_hard_comp=0.010667,atrim=")
                .contains("[1:a:3]aresample=48000:async=1:min_hard_comp=0.010667,atrim=")
                .contains("[2:a:1]aresample=48000:async=1:min_hard_comp=0.011610,atrim=");
        assertThat(graph.split("min_hard_comp=", -1)).hasSize(4);
    }

    @Test
    void 트랙_머리_값이_없거나_틀리면_거부한다() {
        assertThatThrownBy(() -> new TrackHead(0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TrackHead(48_000, -1)).isInstanceOf(IllegalArgumentException.class);
        // 무음 대체 트랙은 흐름에서 읽지 않으므로 머리 값이 없다
        assertThatThrownBy(() -> new AudioRunInput(null, List.of("a.mp4"), null, List.of(), 0, 10, 10, Set.of(1),
                Map.of(1, new TrackHead(48_000, 0)))).isInstanceOf(IllegalArgumentException.class);
        // 흐름에서 읽는 트랙에 머리 값이 없으면 앞 무음 · 문턱을 정할 수 없다
        AudioRunInput headless = new AudioRunInput(null, List.of("a.mp4"), null, List.of(), 0, 10, 10, Set.of(),
                Map.of(3, new TrackHead(48_000, 0)));
        assertThatThrownBy(() -> RenderCommands.measureLoudnessJoined("ffmpeg", List.of(headless), TWO_TRACKS))
                .isInstanceOf(IllegalArgumentException.class);
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
        assertThatThrownBy(() -> RenderCommands.renderJoined("ffmpeg", TS, wrong, ColorTags.UNKNOWN,
                List.of(RUN_0, RUN_1), TWO_TRACKS, null, null, null, "o1.mp4"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 최종_단계_자막_파일이_ASS_면_모양을_덧입히지_않고_글꼴_폴더를_붙인다() {
        Composition.Plan plain = new Composition.Plan("[0:v:0]null", List.of(), 2);
        List<String> cmd = RenderCommands.renderJoined("ffmpeg", TS, plain, ColorTags.UNKNOWN, List.of(RUN_0),
                TWO_TRACKS, null, "o1.ass", "/fonts", "o1.mp4");
        assertThat(cmd.get(cmd.indexOf("-filter_complex") + 1))
                .startsWith("[0:v:0]null,subtitles=o1.ass:fontsdir=/fonts[vout];[1:a:1]");
    }

    @Test
    void 소리_크기_재기는_묶음_입력만_읽는다() {
        assertThat(RenderCommands.measureLoudnessJoined("ffmpeg", List.of(RUN_0, RUN_1), List.of(new AudioTrack(1,
                1.0)))).containsExactly("ffmpeg", "-hide_banner", "-nostdin", "-benchmark",
                "-copyts", "-f", "concat", "-i", "run_0.ffconcat",
                "-copyts", "-f", "concat", "-i", "run_1.ffconcat",
                "-filter_complex",
                "[0:a:1]" + RESAMPLE_48K + ",atrim=start_pts=96:end_pts=384096,asetpts=PTS-STARTPTS,"
                        + "adelay=delays=0S:all=1," + FORMAT_TAIL + "384000[a0_1];"
                        + "[1:a:1]" + RESAMPLE_44K + ",atrim=start_pts=192000:end_pts=288000,asetpts=PTS-STARTPTS,"
                        + "adelay=delays=512S:all=1," + FORMAT_TAIL + "96000[a1_1];"
                        + "[a0_1][a1_1]concat=n=2:v=0:a=1[acat1];"
                        + "[acat1]volume=1.000[a1];[a1]aformat=sample_rates=48000:channel_layouts=stereo,"
                        + Loudness.measureFilter() + "[aout]",
                "-map", "[aout]", "-f", "null", "-");
    }

    /**
     * 새 꼴 concat 입력은 기본 안전 검사({@code -safe 1})로 연다. 목록 안 이름이 모두 작업 폴더 상대 이름이라 풀 까닭이 없고, 풀면
     * 목록이 바뀌었을 때 폴더 밖 읽기까지 열린다(옛 꼴 {@link RenderCommands#concat}은 P9 바이트 보존이라 그대로 둔다).
     */
    @Test
    void 새_꼴_concat_입력은_안전_검사를_풀지_않는다() {
        assertThat(finalCommand()).doesNotContain("-safe");
        assertThat(RenderCommands.measureLoudnessJoined("ffmpeg", List.of(RUN_0, RUN_1), TWO_TRACKS))
                .doesNotContain("-safe");
        assertThat(RenderCommands.concat("ffmpeg")).containsSequence("-safe", "0");
    }

    /** 새 꼴 명령은 셋 다 끝에 최대 RSS 를 남긴다({@code render.graph_size maxRssMb} 의 출처 · 설계 15절). */
    @Test
    void 새_꼴_명령은_셋_다_최대_RSS_를_남긴다() {
        List<List<String>> joined = List.of(finalCommand(),
                RenderCommands.measureLoudnessJoined("ffmpeg", List.of(RUN_0), TWO_TRACKS),
                RenderCommands.cutPiece("ffmpeg", new VideoPiece("a.mp4", 0, 3_001, 0), 0, TS, CAP));
        assertThat(joined).allSatisfy(c -> assertThat(c.subList(0, 4))
                .containsExactly("ffmpeg", "-hide_banner", "-nostdin", "-benchmark"));
    }

    @Test
    void 최대_RSS_는_benchmark_줄에서_KiB_로_읽는다() {
        String stderr = "frame=  120 fps=0.0 q=-0.0 Lsize=N/A\nbench: utime=1.234s stime=0.100s rtime=1.500s\n"
                + "bench: maxrss=571392KiB\n";
        assertThat(RenderCommands.maxRssKib(stderr)).hasValue(571_392);
        assertThat(RenderCommands.maxRssKib("frame=  120 fps=0.0\n")).isEmpty();
        assertThat(RenderCommands.maxRssKib("")).isEmpty();
    }

    /** 옛 꼴 출력도 그림 입력 번호를 확인한다 — 옛 꼴은 원본(입력 0) 하나 뒤라 1 이어야 한다(새 꼴 가드와 대칭). */
    @Test
    void 옛_꼴_출력은_그림_번호가_1_이_아니면_거부한다() {
        Composition.Plan joined = new Composition.Plan("[0:v:0]setsar=1,[3:v]overlay", List.of(image("o1_a.png")), 3);
        assertThatThrownBy(() -> RenderCommands.render("ffmpeg", 0, 1_000, joined, TWO_TRACKS, null, null, null,
                "o1.mp4")).isInstanceOf(IllegalArgumentException.class);
        Composition.Plan legacy = new Composition.Plan("[0:v:0]setsar=1,[1:v]overlay", List.of(image("o1_a.png")), 1);
        assertThat(RenderCommands.render("ffmpeg", 0, 1_000, legacy, TWO_TRACKS, null, null, null, "o1.mp4"))
                .containsSequence("-loop", "1", "-i", "o1_a.png");
    }

    private static List<String> finalCommand() {
        return finalCommand(ColorTags.UNKNOWN);
    }

    private static List<String> finalCommand(ColorTags color) {
        Composition.Plan plan = new Composition.Plan("[0:v:0]setsar=1,[3:v]overlay", List.of(image("o1_a.png")), 3);
        return RenderCommands.renderJoined("ffmpeg", TS, plan, color, List.of(RUN_0, RUN_1), TWO_TRACKS,
                "loudnorm=FIX", RenderCommands.BURN_SRT, null, "o1.mp4");
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
