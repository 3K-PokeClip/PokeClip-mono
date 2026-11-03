package com.pokeclip.render.media;

import com.pokeclip.render.recipe.Recipe.Aspect;
import com.pokeclip.render.recipe.Recipe.AudioTrack;
import com.pokeclip.render.recipe.Recipe.Crop;
import com.pokeclip.render.recipe.Recipe.Output;
import com.pokeclip.render.recipe.RecipeParser;
import com.pokeclip.render.support.Fixtures;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RenderCommandsTest {

    /**
     * 옛 꼴 주문(계획 없음)의 세 명령. 기대값은 develop 코드의 출력이다. 바뀐 곳은 출력 명령의 평준화 꼬리 한 구절
     * ({@link #LOUDNESS_SETTLE})뿐이고 나머지는 한 글자도 바뀌지 않아야 한다.
     */
    private static final List<AudioTrack> TWO_TRACKS = List.of(new AudioTrack(1, 1.0), new AudioTrack(3, 0.6));

    /** develop 대비 옛 꼴 출력 명령에 더한 유일한 구절 — 평준화가 있을 때 그 바로 뒤, {@code [aout]} 앞. */
    private static final String LOUDNESS_SETTLE = ",asetpts=N/SR/TB,aresample=48000";

    private static final String OLD_DECORATED_GRAPH = "[0:v:0]setsar=1,split=3[s0][s1][s2];"
            + "[s2]scale=270:480:force_original_aspect_ratio=increase,crop=270:480,gblur=sigma=6.480,scale=1080:1920,"
            + "setsar=1[bg];[s0]crop=202:360:219:0,scale=1080:1920:flags=lanczos,setsar=1[l0];"
            + "[bg][l0]overlay=x=0:y=0[l0o];[s1]crop=238:180:385:36,scale=690:518:flags=lanczos,setsar=1,"
            + "format=yuva420p[l1r];[1:v]format=gray[l1m];[l1r][l1m]alphamerge[l1];[l0o][2:v]overlay=x=95:y=861[l1s];"
            + "[l1s][l1]overlay=x=194:y=960[l1o];[l1o][3:v]overlay=x=194:y=960,"
            + "drawbox=x=0:y=480:w=iw:h=9:color=0x586FC4:t=fill";

    @Test
    void 트랙_하나면_섞지_않고_그_트랙만_쓴다() {
        assertThat(RenderCommands.audioGraph(List.of(new AudioTrack(0, 1.0)), null))
                .isEqualTo("[0:a:0]volume=1.000[a0];[a0]aformat=sample_rates=48000:channel_layouts=stereo[aout]");
    }

    @Test
    void 여러_트랙은_나누지_않고_섞은_뒤_평준화한다() {
        String graph = RenderCommands.audioGraph(List.of(new AudioTrack(1, 1.0), new AudioTrack(3, 0.6)), "loudnorm=X");
        assertThat(graph).isEqualTo("[0:a:1]volume=1.000[a1];[0:a:3]volume=0.600[a3];[a1][a3]"
                + "amix=inputs=2:duration=longest:normalize=0,aformat=sample_rates=48000:channel_layouts=stereo,"
                + "loudnorm=X[aout]");
    }

    @Test
    void 잇기_목록은_마지막_조각만_간격이_없다() {
        assertThat(RenderCommands.concatList(List.of("a.m4s", "b.m4s", "c.m4s"), List.of(3_946_667L, 3_989_333L)))
                .isEqualTo("ffconcat version 1.0\nfile 'a.m4s'\nduration 3.946667\nfile 'b.m4s'\n"
                        + "duration 3.989333\nfile 'c.m4s'\n");
    }

    @Test
    void 자르기는_입력_쪽_seek이다() {
        List<String> cmd = RenderCommands.render("ffmpeg", 1_234, 10_000,
                Composition.plan(Output.single("o1", Aspect.VERT_9_16, new Crop(0, 0, 0.31640625, 1)), 1920, 1080, "o1_"),
                List.of(new AudioTrack(0, 1.0)), null, RenderCommands.BURN_SRT, null, "o1.mp4");
        assertThat(cmd.subList(0, 9)).containsExactly("ffmpeg", "-hide_banner", "-nostdin", "-ss", "1.234", "-i",
                "source.mp4", "-t", "10.000");
        assertThat(cmd.get(cmd.indexOf("-filter_complex") + 1))
                .startsWith("[0:v:0]setsar=1,crop=606:1080:1:0,scale=1080:1920:flags=lanczos,setsar=1,subtitles=burn.srt:")
                .contains("[vout];");
    }

    @Test
    void 꾸밈_그림은_원본_뒤_입력이고_길이는_그_뒤에_둔다() {
        Composition.Plan plan = new Composition.Plan("[0:v:0]null", List.of(
                new Composition.Image("a.png", new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB)),
                new Composition.Image("b.png", new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB))));
        List<String> cmd = RenderCommands.render("ffmpeg", 0, 5_000, plan, List.of(new AudioTrack(0, 1.0)), null, null,
                null, "o1.mp4");
        assertThat(cmd.subList(0, 17)).containsExactly("ffmpeg", "-hide_banner", "-nostdin", "-ss", "0.000", "-i",
                "source.mp4", "-loop", "1", "-i", "a.png", "-loop", "1", "-i", "b.png", "-t", "5.000");
        assertThat(cmd.get(cmd.indexOf("-filter_complex") + 1)).startsWith("[0:v:0]null[vout];");
    }

    @Test
    void 옛_꼴_잇기_명령은_develop과_바이트_같다() {
        assertThat(RenderCommands.concat("ffmpeg")).containsExactly("ffmpeg", "-hide_banner", "-nostdin", "-y", "-f",
                "concat", "-safe", "0", "-i", "concat.txt", "-map", "0", "-c", "copy", "-f", "mp4", "source.mp4");
    }

    @Test
    void 옛_꼴_소리_크기_재기_명령은_develop과_바이트_같다() {
        assertThat(RenderCommands.measureLoudness("ffmpeg", 1_234, 10_000, TWO_TRACKS)).containsExactly("ffmpeg",
                "-hide_banner", "-nostdin", "-ss", "1.234", "-i", "source.mp4", "-t", "10.000", "-filter_complex",
                "[0:a:1]volume=1.000[a1];[0:a:3]volume=0.600[a3];[a1][a3]amix=inputs=2:duration=longest:normalize=0,"
                        + "aformat=sample_rates=48000:channel_layouts=stereo,"
                        + "loudnorm=I=-14:TP=-1.5:LRA=11:print_format=json[aout]",
                "-map", "[aout]", "-f", "null", "-");
    }

    @Test
    void 옛_꼴_출력_명령은_꾸밈_그림_번인까지_develop과_평준화_꼬리_한_구절만_다르다() {
        Output output = RecipeParser.parse(Fixtures.recipeV2("s1")).outputs().getFirst();
        Composition.Plan plan = Composition.plan(output, 640, 360, "o1_");
        assertThat(plan.graph()).isEqualTo(OLD_DECORATED_GRAPH);
        List<String> developCmd = List.of("ffmpeg", "-hide_banner", "-nostdin",
                "-ss", "61.005", "-i", "source.mp4", "-loop", "1", "-i", "o1_l1_mask.png", "-loop", "1", "-i",
                "o1_l1_shadow.png", "-loop", "1", "-i", "o1_l1_ring.png", "-t", "30.250", "-filter_complex",
                OLD_DECORATED_GRAPH + ",subtitles=burn.srt:fontsdir=/fonts:force_style='FontName=Noto Sans CJK KR,"
                        + "FontSize=13,Outline=1,Shadow=0,MarginV=60'[vout];[0:a:1]volume=1.000[a1];"
                        + "[0:a:3]volume=0.600[a3];[a1][a3]amix=inputs=2:duration=longest:normalize=0,"
                        + "aformat=sample_rates=48000:channel_layouts=stereo,loudnorm=FIX[aout]",
                "-map", "[vout]", "-map", "[aout]", "-c:v", "libx264", "-preset", "veryfast", "-crf", "20",
                "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "160k", "-movflags", "+faststart", "-y", "o1.mp4");
        int graphAt = developCmd.indexOf("-filter_complex") + 1;

        List<String> cmd = RenderCommands.render("ffmpeg", 61_005, 30_250, plan, TWO_TRACKS, "loudnorm=FIX",
                RenderCommands.BURN_SRT, "/fonts", "o1.mp4");

        assertThat(cmd).hasSameSizeAs(developCmd);
        for (int i = 0; i < cmd.size(); i++) {
            if (i != graphAt) {
                assertThat(cmd.get(i)).as("인자 %d", i).isEqualTo(developCmd.get(i));
            }
        }
        String developGraph = developCmd.get(graphAt);
        String at = "loudnorm=FIX";
        int cut = developGraph.indexOf(at) + at.length();
        assertThat(cmd.get(graphAt)).as("필터 = develop 에 평준화 꼬리 한 구절만 끼움")
                .isEqualTo(developGraph.substring(0, cut) + LOUDNESS_SETTLE + developGraph.substring(cut));
    }

    @Test
    void 옛_꼴_무음_출력은_평준화가_없어_꼬리도_develop_그대로다() {
        List<String> cmd = RenderCommands.render("ffmpeg", 0, 5_000, new Composition.Plan("[0:v:0]null", List.of()),
                TWO_TRACKS, null, null, null, "o1.mp4");
        assertThat(cmd.get(cmd.indexOf("-filter_complex") + 1)).isEqualTo("[0:v:0]null[vout];[0:a:1]volume=1.000[a1];"
                + "[0:a:3]volume=0.600[a3];[a1][a3]amix=inputs=2:duration=longest:normalize=0,"
                + "aformat=sample_rates=48000:channel_layouts=stereo[aout]");
    }

    @Test
    void ASS_자막은_모양을_덧입히지_않는다() {
        List<String> cmd = RenderCommands.render("ffmpeg", 0, 5_000, new Composition.Plan("[0:v:0]null", List.of()),
                List.of(new AudioTrack(0, 1.0)), null, "o1.ass", null, "o1.mp4");
        assertThat(cmd.get(cmd.indexOf("-filter_complex") + 1)).startsWith("[0:v:0]null,subtitles=o1.ass[vout];");
    }
}
