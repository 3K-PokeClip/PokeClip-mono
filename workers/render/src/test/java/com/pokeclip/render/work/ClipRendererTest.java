package com.pokeclip.render.work;

import com.pokeclip.render.job.EnvelopeParser;
import com.pokeclip.render.job.ErrorCode;
import com.pokeclip.render.job.JobEnvelope;
import com.pokeclip.render.job.RenderFailure;
import com.pokeclip.render.media.MediaProbe;
import com.pokeclip.render.media.ProcessRunner;
import com.pokeclip.render.storage.S3Store;
import com.pokeclip.render.support.Ffmpeg;
import com.pokeclip.render.support.Fixtures;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * 실물 조각 셋(로컬 MediaMTX 녹화)으로 진짜 ffmpeg를 돌린다. S3만 가짜다. 시험 자원에서 파일을 복사한다.
 * ffmpeg가 없는 기계에서는 건너뛴다. 번인 자막은 libass가 있을 때만 켠다(없으면 CC만).
 */
class ClipRendererTest {

    private static boolean burn;

    @TempDir
    Path dir;

    private final List<String> stages = new ArrayList<>();

    @BeforeAll
    static void ffmpeg() {
        assumeThat(Ffmpeg.available()).as("ffmpeg 필요").isTrue();
        burn = Ffmpeg.canBurnSubtitles();
    }

    @Test
    void 조각을_이어_자르고_세로로_만들고_자막을_낸다() throws IOException {
        ObjectNode recipe = Fixtures.recipe("s1");
        if (!burn) {
            ((ObjectNode) recipe.get("subtitles")).put("mode", "CC_ONLY");
        }
        JobEnvelope job = job(recipe);

        List<ClipRenderer.Produced> produced = renderer().render(job, dir, Instant.now().plus(Duration.ofMinutes(2)),
                (percent, stage) -> stages.add(stage));

        assertThat(produced).extracting(ClipRenderer.Produced::kind).containsExactly("video", "srt");
        JsonNode video = probe(produced.getFirst().file());
        JsonNode v = stream(video, "video");
        assertThat(v.path("width").asInt()).isEqualTo(1080);
        assertThat(v.path("height").asInt()).isEqualTo(1920);
        assertThat(audioCount(video)).as("섞은 소리는 한 줄").isEqualTo(1);
        assertThat(stream(video, "audio").path("channels").asInt()).isEqualTo(2);
        assertThat(video.path("format").path("duration").asDouble()).isCloseTo(10.0, org.assertj.core.data.Offset.offset(0.1));

        assertThat(Files.readString(produced.get(1).file()))
                .startsWith("1\n00:00:00,000 --> 00:00:01,500\n컷에 걸친 자막\n\n2\n00:00:03,000 --> 00:00:05,000\n");
        assertThat(stages).containsExactly("download", "concat", "loudness", "encode");
    }

    @Test
    void 이은_원본에_조각_경계_빈틈이_없다() {
        ObjectNode recipe = Fixtures.recipe("s1");
        recipe.putNull("subtitles");
        renderer().render(job(recipe), dir, Instant.now().plus(Duration.ofMinutes(2)), (p, s) -> { });

        String packets = Ffmpeg.probe("-v", "error", "-select_streams", "v:0", "-show_entries", "packet=pts_time",
                "-of", "csv=p=0", dir.resolve("source.mp4").toString());
        List<Double> pts = packets.lines().filter(l -> !l.isBlank()).map(Double::parseDouble).sorted().toList();
        double worst = 0;
        for (int i = 1; i < pts.size(); i++) {
            worst = Math.max(worst, pts.get(i) - pts.get(i - 1));
        }
        // 30fps = 33.3ms. 경계에서 demuxer가 파일 길이로 밀면 86~150ms 빈틈이 생긴다(실측).
        assertThat(worst).isLessThan(0.04);
        assertThat(pts).hasSize(360);
    }

    @Test
    void v2는_바탕_층_테두리_구분선을_그_자리에_그린다() throws IOException {
        // 초록 바탕 · 가운데 16:9 층 · 아래 작은 화면(흰 테두리 11px) · 위쪽 빨간 구분선. 자리마다 색을 재서 합성 순서와 좌표를 본다.
        ObjectNode recipe = Fixtures.recipeV2("s1");
        recipe.putNull("subtitles");
        ObjectNode output = (ObjectNode) recipe.get("outputs").get(0);
        output.putObject("background").put("kind", "COLOR").put("color", "#00FF00");
        tools.jackson.databind.node.ArrayNode layers = output.putArray("layers");
        ObjectNode center = layers.addObject();
        center.putObject("crop").put("x", 0.08).put("y", 0.08).put("w", 0.84).put("h", 0.84);
        center.putObject("box").put("x", 0.0).put("y", 0.341796875).put("w", 1.0).put("h", 0.31640625);
        ObjectNode pip = layers.addObject();
        // 0.64 x 0.2 = 691.2 x 384px = 1.8 → 원본 높이 0.5(180px)면 폭 324px = 0.50625
        pip.putObject("crop").put("x", 0.2).put("y", 0.25).put("w", 0.50625).put("h", 0.5);
        pip.putObject("box").put("x", 0.18).put("y", 0.75).put("w", 0.64).put("h", 0.2);
        pip.putObject("frame").put("width", 0.01).put("color", "#FFFFFF").put("radius", 0.02).put("shadow", true);
        output.putArray("dividers").addObject().put("y", 0.1).put("thickness", 0.02).put("color", "#FF0000");

        List<ClipRenderer.Produced> produced = renderer().render(job(recipe), dir,
                Instant.now().plus(Duration.ofMinutes(2)), (p, s) -> { });

        byte[] frame = frameAt(produced.getFirst().file(), 1080, 1920);
        assertThat(rgb(frame, 540, 60)).as("바탕").satisfies(c -> near(c, 0x00FF00));
        assertThat(rgb(frame, 540, 200)).as("구분선(y 192부터 22px)").satisfies(c -> near(c, 0xFF0000));
        assertThat(rgb(frame, 200, 1440 + 192)).as("작은 화면 왼쪽 테두리(x 194부터 11px)").satisfies(c -> near(c, 0xFFFFFF));
        assertThat(rgb(frame, 540, 1918)).as("작은 화면 아래 바탕").satisfies(c -> near(c, 0x00FF00));
        assertThat(rgb(frame, 540, 960)).as("가운데 층은 원본 그림이라 바탕색이 아니다")
                .satisfies(c -> assertThat(distance(c, 0x00FF00)).isGreaterThan(60));
    }

    @Test
    void 구간을_다_못_덮으면_거부한다() {
        ObjectNode recipe = Fixtures.recipe("s1");
        ((ObjectNode) recipe.get("cut")).put("outAtMs", Fixtures.BASE + 12_300);
        assertThatThrownBy(() -> renderer().render(job(recipe), dir, Instant.now().plusSeconds(60), (p, s) -> { }))
                .isInstanceOfSatisfying(RenderFailure.class, f -> assertThat(f.code()).isEqualTo(ErrorCode.SOURCE_RANGE));
    }

    @Test
    void 기록과_실물_길이가_다르면_거부한다() {
        ObjectNode envelope = Fixtures.envelope(UUID.randomUUID(), Fixtures.recipe("s1"));
        ((ObjectNode) envelope.get("sourceKeys").get(1)).put("durationMs", 3800);
        JobEnvelope job = new EnvelopeParser(Fixtures.MAPPER).parse(Fixtures.MAPPER.writeValueAsString(envelope));
        assertThatThrownBy(() -> renderer().render(job, dir, Instant.now().plusSeconds(60), (p, s) -> { }))
                .isInstanceOfSatisfying(RenderFailure.class,
                        f -> assertThat(f.code()).isEqualTo(ErrorCode.SOURCE_MISMATCH));
    }

    @Test
    void 뒤_조각에_고른_트랙이_없어도_SOURCE_MISSING() {
        // 방송 중 재접속으로 트랙 구성이 바뀐 경우: 둘째 조각만 소리 두 줄로 다시 싼다.
        ObjectNode recipe = Fixtures.recipe("s1");
        recipe.putNull("subtitles");
        ClipRenderer renderer = renderer(i -> i == 1 ? twoTrackCopy() : Path.of("src/test/resources/segments/seg_" + i + ".m4s"));
        assertThatThrownBy(() -> renderer.render(job(recipe), dir, Instant.now().plusSeconds(60), (p, s) -> { }))
                .isInstanceOfSatisfying(RenderFailure.class, f -> {
                    assertThat(f.code()).isEqualTo(ErrorCode.SOURCE_MISSING);
                    assertThat(f.retryable()).isFalse();
                });
    }

    @Test
    void 조각이_없으면_SOURCE_MISSING() {
        ObjectNode envelope = Fixtures.envelope(UUID.randomUUID(), Fixtures.recipe("s1"));
        envelope.putArray("sourceKeys");
        JobEnvelope job = new EnvelopeParser(Fixtures.MAPPER).parse(Fixtures.MAPPER.writeValueAsString(envelope));
        assertThatThrownBy(() -> renderer().render(job, dir, Instant.now().plusSeconds(60), (p, s) -> { }))
                .isInstanceOfSatisfying(RenderFailure.class,
                        f -> assertThat(f.code()).isEqualTo(ErrorCode.SOURCE_MISSING));
    }

    @Test
    void 시한을_넘기면_ffmpeg를_끊는다() {
        ObjectNode recipe = Fixtures.recipe("s1");
        recipe.putNull("subtitles");
        assertThatThrownBy(() -> renderer().render(job(recipe), dir, Instant.now().plusMillis(300), (p, s) -> { }))
                .isInstanceOf(ProcessRunner.Timeout.class);
    }

    static JobEnvelope job(ObjectNode recipe) {
        ObjectNode envelope = Fixtures.envelope(UUID.randomUUID(), recipe);
        return new EnvelopeParser(Fixtures.MAPPER).parse(Fixtures.MAPPER.writeValueAsString(envelope));
    }

    static ClipRenderer renderer() {
        return renderer(i -> Path.of("src/test/resources/segments/seg_" + i + ".m4s"));
    }

    static ClipRenderer renderer(java.util.function.IntFunction<Path> source) {
        S3Store store = mock(S3Store.class);
        doAnswer(inv -> {
            String key = inv.getArgument(1);
            int i = Integer.parseInt(key.substring(key.length() - 5, key.length() - 4));
            try {
                Files.copy(source.apply(i), (Path) inv.getArgument(2));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return null;
        }).when(store).download(anyString(), anyString(), any(Path.class), any(Instant.class));
        ProcessRunner runner = new ProcessRunner();
        return new ClipRenderer(store, new MediaProbe(runner, Fixtures.MAPPER, "ffprobe"), runner, Fixtures.MAPPER,
                "ffmpeg", null);
    }

    /** 둘째 조각을 소리 두 줄(트랙 0·1)만 남겨 다시 싼 사본. */
    private Path twoTrackCopy() {
        Path out = dir.resolveSibling(dir.getFileName() + "-two-track.mp4");
        try {
            Process p = new ProcessBuilder("ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i",
                    "src/test/resources/segments/seg_1.m4s", "-map", "0:v", "-map", "0:a:0", "-map", "0:a:1",
                    "-c", "copy", out.toString()).inheritIO().start();
            assertThat(p.waitFor()).isZero();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
        return out;
    }

    /** 1초 자리의 한 프레임을 RGB 바이트로. */
    private byte[] frameAt(Path video, int width, int height) throws IOException {
        Path raw = dir.resolve("frame.rgb");
        try {
            Process p = new ProcessBuilder("ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-ss", "1", "-i",
                    video.toString(), "-frames:v", "1", "-f", "rawvideo", "-pix_fmt", "rgb24", raw.toString())
                    .inheritIO().start();
            assertThat(p.waitFor()).isZero();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
        byte[] bytes = Files.readAllBytes(raw);
        assertThat(bytes).hasSize(width * height * 3);
        return bytes;
    }

    private static int rgb(byte[] frame, int x, int y) {
        int i = (y * 1080 + x) * 3;
        return ((frame[i] & 0xFF) << 16) | ((frame[i + 1] & 0xFF) << 8) | (frame[i + 2] & 0xFF);
    }

    /** 채널별 차이의 최댓값. yuv420 압축이라 정확히 같지 않다. */
    private static int distance(int a, int b) {
        int d = 0;
        for (int shift = 0; shift <= 16; shift += 8) {
            d = Math.max(d, Math.abs(((a >> shift) & 0xFF) - ((b >> shift) & 0xFF)));
        }
        return d;
    }

    private static void near(int actual, int expected) {
        assertThat(distance(actual, expected)).as("색 %06X ≈ %06X", actual, expected).isLessThan(40);
    }

    private static JsonNode probe(Path file) {
        return Fixtures.MAPPER.readTree(Ffmpeg.probe("-v", "error", "-show_entries",
                "format=duration:stream=codec_type,width,height,channels", "-of", "json", file.toString()));
    }

    private static JsonNode stream(JsonNode probe, String type) {
        for (JsonNode s : probe.path("streams")) {
            if (type.equals(s.path("codec_type").asString())) {
                return s;
            }
        }
        throw new AssertionError(type + " 스트림이 없다");
    }

    private static int audioCount(JsonNode probe) {
        int n = 0;
        for (JsonNode s : probe.path("streams")) {
            if ("audio".equals(s.path("codec_type").asString())) {
                n++;
            }
        }
        return n;
    }
}
