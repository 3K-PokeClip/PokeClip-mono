package com.pokeclip.render.media;

import com.pokeclip.render.recipe.Recipe.AudioTrack;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RenderCommandsTest {

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
                new CropGeometry.Pixels(0, 0, 606, 1080), com.pokeclip.render.recipe.Recipe.Aspect.VERT_9_16,
                List.of(new AudioTrack(0, 1.0)), null, true, null, "o1.mp4");
        assertThat(cmd.subList(0, 9)).containsExactly("ffmpeg", "-hide_banner", "-nostdin", "-ss", "1.234", "-i",
                "source.mp4", "-t", "10.000");
        assertThat(cmd.get(cmd.indexOf("-filter_complex") + 1))
                .startsWith("[0:v:0]setsar=1,crop=606:1080:0:0,scale=1080:1920:flags=lanczos,setsar=1,subtitles=burn.srt:")
                .contains("[vout];");
    }
}
