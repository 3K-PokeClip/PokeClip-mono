package com.pokeclip.upload.media;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 완성 영상에서 썸네일 한 장면을 뽑는다(POK-291 명세 §7).
 *
 * <p>🔴 ffmpeg는 마지막 프레임 시각을 조금만 넘겨도 파일 없이 실패한다(렌더 일꾼 실측: 1ms만 넘어도 종료 코드 234). 그래서 ffprobe로
 * 길이를 재 끝에서 100ms 앞으로 <b>당겨 맞추고</b>, 그래도 파일이 없으면 0초로 한 번 더 뽑는다. 진짜 ffmpeg 시험은 앞 1초가 빨강,
 * 뒤 1초가 파랑인 영상으로 「끝을 넘는 자리를 고르면 첫 장면(빨강)이 아니라 끝 장면(파랑)이 나온다」를 잰다: 당겨 맞추기를 빼면
 * 0초 재시도가 빨강을 뽑아 빨간불이 된다.
 */
class SceneExtractorTest {

    @TempDir
    Path dir;

    // ── 진짜 ffmpeg ─────────────────────────────────────────────

    @Test
    void 끝을_넘는_자리도_끝_장면으로_당겨_뽑는다() throws Exception {
        Path video = 빨강_파랑_영상();
        Path picture = dir.resolve("thumb.jpg");

        boolean ok = real().extract(video, 999_999, picture);

        assertThat(ok).isTrue();
        assertThat(색(picture)).as("끝 장면(파랑)이어야 한다. 빨강이면 0초로 다시 뽑은 것이다").isEqualTo("파랑");
    }

    @Test
    void 끝_바로_뒤_자리도_끝_장면이다() throws Exception {
        Path video = 빨강_파랑_영상();
        Path picture = dir.resolve("thumb.jpg");

        assertThat(real().extract(video, 2_000, picture)).isTrue();
        assertThat(색(picture)).isEqualTo("파랑");
    }

    @Test
    void 구간_안의_자리는_그_장면이고_해상도는_출력_그대로다() throws Exception {
        Path video = 빨강_파랑_영상();
        Path red = dir.resolve("red.jpg");
        Path blue = dir.resolve("blue.jpg");

        assertThat(real().extract(video, 300, red)).isTrue();
        assertThat(real().extract(video, 1_500, blue)).isTrue();

        assertThat(색(red)).isEqualTo("빨강");
        assertThat(색(blue)).isEqualTo("파랑");
        BufferedImage image = ImageIO.read(blue.toFile());
        assertThat(image.getWidth()).isEqualTo(640);
        assertThat(image.getHeight()).isEqualTo(360);
    }

    @Test
    void 영상이_아니면_실패를_돌려준다() throws Exception {
        Assumptions.ffmpeg();
        Path junk = dir.resolve("junk.mp4");
        Files.write(junk, new byte[]{1, 2, 3});

        assertThat(real().extract(junk, 500, dir.resolve("thumb.jpg"))).isFalse();
        assertThat(dir.resolve("thumb.jpg")).doesNotExist();
    }

    // ── 가짜 실행기 ────────────────────────────────────────────

    /** 길이 10초면 12초 자리는 9.900초로 당긴다. */
    @Test
    void ffprobe_길이로_끝에서_100ms_앞으로_당긴다() {
        Recorder runner = new Recorder("10.000000\n", true);

        assertThat(fake(runner).extract(dir.resolve("v.mp4"), 12_000, dir.resolve("t.jpg"))).isTrue();

        assertThat(runner.seeks).containsExactly("9.900");
    }

    @Test
    void 음수_자리는_0초다() {
        Recorder runner = new Recorder("10.0\n", true);

        fake(runner).extract(dir.resolve("v.mp4"), -5, dir.resolve("t.jpg"));

        assertThat(runner.seeks).containsExactly("0.000");
    }

    /** 첫 시도가 실패하며 덜 쓴 파일을 남겼다. 그 파일을 지우고 0초로 한 번 더 뽑는다. */
    @Test
    void 실패하면_덜_쓴_파일을_지우고_0초로_한_번_더_뽑는다() {
        Recorder runner = new Recorder("10.0\n", true);
        runner.failFirstLeavingJunk = true;

        boolean ok = fake(runner).extract(dir.resolve("v.mp4"), 3_000, dir.resolve("t.jpg"));

        assertThat(ok).isTrue();
        assertThat(runner.seeks).containsExactly("3.000", "0.000");
    }

    /** 종료 코드 0인데 파일이 없다(판에 따라 그렇다). 0초로 한 번 더. */
    @Test
    void 성공했는데_파일이_없어도_0초로_한_번_더_뽑는다() {
        Recorder runner = new Recorder("10.0\n", true);
        runner.firstWritesNothing = true;

        assertThat(fake(runner).extract(dir.resolve("v.mp4"), 3_000, dir.resolve("t.jpg"))).isTrue();
        assertThat(runner.seeks).containsExactly("3.000", "0.000");
    }

    @Test
    void 영초_재시도도_실패하면_실패를_돌려준다() {
        Recorder runner = new Recorder("10.0\n", false);

        assertThat(fake(runner).extract(dir.resolve("v.mp4"), 3_000, dir.resolve("t.jpg"))).isFalse();
        assertThat(runner.seeks).containsExactly("3.000", "0.000");
        assertThat(dir.resolve("t.jpg")).doesNotExist();
    }

    /** ffprobe가 실패하면 고른 자리 그대로 해 보고, 안 되면 0초다. */
    @Test
    void 길이를_못_재면_고른_자리_그대로_해_본다() {
        Recorder runner = new Recorder(null, true);

        assertThat(fake(runner).extract(dir.resolve("v.mp4"), 4_200, dir.resolve("t.jpg"))).isTrue();
        assertThat(runner.seeks).containsExactly("4.200");
    }

    /** ffmpeg가 시한을 넘기면 썸네일 실패다(예외가 새어 영상 보고를 막지 않는다). */
    @Test
    void 시한을_넘기면_실패를_돌려준다() {
        Recorder runner = new Recorder("10.0\n", true);
        runner.timeout = true;

        assertThat(fake(runner).extract(dir.resolve("v.mp4"), 3_000, dir.resolve("t.jpg"))).isFalse();
    }

    @Test
    void 명령은_명세_모양이다() {
        Recorder runner = new Recorder("10.0\n", true);

        fake(runner).extract(dir.resolve("v.mp4"), 1_234, dir.resolve("t.jpg"));

        assertThat(runner.commands.get(1)).containsExactly("/x/ffmpeg", "-hide_banner", "-nostdin", "-y", "-ss",
                "1.234", "-i", dir.resolve("v.mp4").toString(), "-frames:v", "1", "-q:v", "2",
                dir.resolve("t.jpg").toString());
        assertThat(runner.commands.getFirst().getFirst()).isEqualTo("/x/ffprobe");
    }

    // ── 도우미 ──────────────────────────────────────────────────

    private SceneExtractor real() {
        return new SceneExtractor(new ProcessRunner(), "ffmpeg", "ffprobe", Duration.ofSeconds(30));
    }

    private SceneExtractor fake(Recorder runner) {
        return new SceneExtractor(runner, "/x/ffmpeg", "/x/ffprobe", Duration.ofSeconds(30));
    }

    /** 앞 1초 빨강, 뒤 1초 파랑(640x360·30fps). */
    private Path 빨강_파랑_영상() throws Exception {
        Assumptions.ffmpeg();
        Path video = dir.resolve("rb.mp4");
        Process p = new ProcessBuilder("ffmpeg", "-hide_banner", "-nostdin", "-y",
                "-f", "lavfi", "-i", "color=c=red:s=640x360:r=30:d=1",
                "-f", "lavfi", "-i", "color=c=blue:s=640x360:r=30:d=1",
                "-filter_complex", "[0:v][1:v]concat=n=2:v=1:a=0[v]", "-map", "[v]",
                "-c:v", "libx264", "-pix_fmt", "yuv420p", video.toString())
                .redirectErrorStream(true).redirectOutput(dir.resolve("gen.log").toFile()).start();
        assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(p.exitValue()).isZero();
        return video;
    }

    private static String 색(Path jpg) throws IOException {
        BufferedImage image = ImageIO.read(jpg.toFile());
        int rgb = image.getRGB(image.getWidth() / 2, image.getHeight() / 2);
        int r = (rgb >> 16) & 0xFF;
        int b = rgb & 0xFF;
        return r > b ? "빨강" : "파랑";
    }

    private static final class Assumptions {
        static void ffmpeg() {
            boolean ok;
            try {
                Process p = new ProcessBuilder("ffmpeg", "-hide_banner", "-version").redirectErrorStream(true).start();
                p.getInputStream().readAllBytes();
                ok = p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0;
            } catch (Exception e) {
                ok = false;
            }
            assumeTrue(ok, "ffmpeg가 없다");
        }
    }

    /** ffprobe·ffmpeg 대신 명령을 기록하고 정해 둔 대로 답한다. */
    private static final class Recorder extends ProcessRunner {
        final List<List<String>> commands = new ArrayList<>();
        final List<String> seeks = new ArrayList<>();
        private final String probeOut;
        private final boolean zeroWorks;
        boolean failFirstLeavingJunk;
        boolean firstWritesNothing;
        boolean timeout;

        Recorder(String probeOut, boolean zeroWorks) {
            this.probeOut = probeOut;
            this.zeroWorks = zeroWorks;
        }

        @Override
        public Result run(List<String> command, Path workDir, Instant deadline) {
            commands.add(command);
            if (command.getFirst().endsWith("ffprobe")) {
                if (probeOut == null) {
                    throw new Failed("ffprobe", 1, "없다");
                }
                return new Result(probeOut, "");
            }
            if (timeout) {
                throw new Timeout("ffmpeg");
            }
            String seek = command.get(command.indexOf("-ss") + 1);
            seeks.add(seek);
            Path out = Path.of(command.getLast());
            boolean first = seeks.size() == 1;
            try {
                if (first && failFirstLeavingJunk) {
                    Files.write(out, new byte[0]);
                    throw new Failed("ffmpeg", 234, "Non full-range YUV");
                }
                if (first && firstWritesNothing) {
                    return new Result("", "");
                }
                if (seek.equals("0.000") && !zeroWorks) {
                    throw new Failed("ffmpeg", 1, "못 뽑았다");
                }
                if (!seek.equals("0.000") && !zeroWorks) {
                    throw new Failed("ffmpeg", 234, "끝을 넘었다");
                }
                Files.write(out, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            return new Result("", "");
        }
    }
}
