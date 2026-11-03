package com.pokeclip.upload.media;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 완성 영상에서 썸네일 한 장면을 뽑는다(POK-291 명세 §7).
 *
 * <p>🔴 ffmpeg는 마지막 프레임 시각을 조금만 넘겨도 파일 없이 실패한다(1ms만 넘어도 종료 코드 234). 그래서 ffprobe로 영상 패킷
 * PTS 최댓값을 읽어 고른 자리를 <b>마지막 표시 프레임 시작</b>(P_last)까지로 자르고, 그래도 파일이 없으면 0초로 한 번 더 뽑는다.
 * 진짜 ffmpeg 시험은 앞 1초가 빨강, 뒤 1초가 파랑인 영상으로 「끝을 넘는 자리를 고르면 첫 장면(빨강)이 아니라 끝 장면(파랑)이
 * 나온다」를 잰다: 자르기를 빼면 0초 재시도가 빨강을 뽑아 빨간불이 된다. B 프레임 영상에서는 찍힌 그림이 표시 순 마지막 프레임과
 * 바이트까지 같은지 잰다: DTS 최댓값이나 format 길이로 자르면 마지막 프레임 앞 그림이 찍혀 빨간불이 된다. 가변 프레임 · 회전 표지 ·
 * format 시작보다 늦게 시작하는 영상 · format 시작이 0이 아닌 파일도 실제 파일로 같은 오라클을 쓴다.
 */
@ExtendWith(OutputCaptureExtension.class)
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

    /**
     * UPLOAD_WORK_DIR가 상대 경로면 영상·그림 경로도 상대 경로로 온다. 실행 폴더를 그림 폴더로 바꾸므로 상대 경로를 그대로 넘기면
     * ffprobe·ffmpeg가 「그림 폴더/상대 경로」를 찾다 실패한다(로컬 리뷰 1라운드). 절대 경로로 바꿔 넘겨야 뽑힌다.
     *
     * <p>폴더는 JVM 현재 폴더 <b>아래</b>에 둔다. 임시 폴더(/var/…)를 현재 폴더 기준 상대 경로로 바꾸면 앞의 {@code ..}가 루트에서
     * 멈춰 「그림 폴더/상대 경로」도 우연히 같은 파일을 가리키고, 결함이 있어도 초록이 된다.
     */
    @Test
    void 상대_경로로_받아도_뽑는다() throws Exception {
        Path video = 빨강_파랑_영상();
        Path relative = Path.of("build", "tmp", "scene-relative-" + System.nanoTime());
        Files.createDirectories(relative);
        try {
            Path relativeVideo = Files.copy(video, relative.resolve("rb.mp4"));
            Path relativePicture = relative.resolve("thumb.jpg");
            assertThat(relativeVideo.isAbsolute()).isFalse();

            assertThat(real().extract(relativeVideo, 1_500, relativePicture)).isTrue();
            assertThat(색(relativePicture)).isEqualTo("파랑");
        } finally {
            try (var files = Files.list(relative)) {
                for (Path f : files.toList()) {
                    Files.delete(f);
                }
            }
            Files.delete(relative);
        }
    }

    @Test
    void 영상이_아니면_실패를_돌려준다() throws Exception {
        Assumptions.ffmpeg();
        Path junk = dir.resolve("junk.mp4");
        Files.write(junk, new byte[]{1, 2, 3});

        assertThat(real().extract(junk, 500, dir.resolve("thumb.jpg"))).isFalse();
        assertThat(dir.resolve("thumb.jpg")).doesNotExist();
    }

    /**
     * B 프레임 영상은 DTS 순 마지막 패킷이 표시 순 마지막 프레임이 아니다. 끝을 넘는 자리는 PTS 최댓값(표시 순 마지막 프레임 시작)으로
     * 잘라야 그 프레임이 찍힌다. 마지막 프레임만 초록이라 DTS 최댓값 · format 길이 − 100ms 로 자르면 빨강이 찍힌다.
     */
    @Test
    void B_프레임_영상의_끝을_넘는_자리는_표시_순_마지막_프레임을_찍는다() throws Exception {
        Path video = 끝만_초록인_B_프레임_영상();
        Path picture = dir.resolve("thumb.jpg");

        assertThat(real().extract(video, 999_999, picture)).isTrue();

        assertThat(sha256(picture)).as("표시 순 마지막 프레임과 같은 그림이어야 한다").isEqualTo(sha256(마지막_프레임(video)));
        assertThat(초록이다(picture)).isTrue();
    }

    /**
     * 길이 3초 · 30fps · B 프레임 없음: 마지막 프레임 시작은 2.966667초라 ms 로 내린 2966에서 자른다. 그 자리에서는 ffmpeg가 파일을
     * 내고, 1ms 뒤(2967 — 반올림하면 이 값이다)에서는 파일 없이 실패한다(종료 코드 234).
     */
    @Test
    void 마지막_프레임_시작에서_자르면_뽑히고_1ms_뒤는_ffmpeg가_실패한다(CapturedOutput output) throws Exception {
        Path video = 빨강_3초_영상();
        Recording runner = new Recording();
        SceneExtractor extractor = new SceneExtractor(runner, "ffmpeg", "ffprobe", Duration.ofSeconds(30));

        assertThat(extractor.extract(video, 2_990, dir.resolve("thumb.jpg"))).isTrue();

        assertThat(runner.seeks).containsExactly("2.966");
        assertThat(output.getAll()).contains("upload.thumbnail_scene_clamped offsetMs=2990 lastFrameMs=2966")
                .doesNotContain("upload.thumbnail_scene_fallback");
        Path late = dir.resolve("late.jpg");
        assertThatThrownBy(() -> new ProcessRunner().run(extractor.command(video, 2_967, late), dir,
                Instant.now().plusSeconds(30))).isInstanceOf(ProcessRunner.Failed.class);
        assertThat(late).doesNotExist();
    }

    /**
     * 가변 프레임(VFR): 1초는 30fps, 뒤 1초는 10fps 라 마지막 프레임 시작이 1.9초다. 프레임 수와 30fps 로 끝을 어림하면
     * (40장 → 1.3초) 마지막 앞 그림이 찍힌다.
     */
    @Test
    void 가변_프레임_영상의_끝을_넘는_자리는_표시_순_마지막_프레임을_찍는다() throws Exception {
        Path video = 끝만_초록인_가변_프레임_영상();
        Recording runner = new Recording();
        Path picture = dir.resolve("thumb.jpg");

        assertThat(recording(runner).extract(video, 999_999, picture)).isTrue();

        assertThat(runner.seeks).containsExactly("1.900");
        assertThat(sha256(picture)).isEqualTo(sha256(마지막_프레임(video)));
        assertThat(초록이다(picture)).isTrue();
    }

    /** 회전 표지(표시 행렬 90도)가 있어도 시각은 그대로다. 그림은 ffmpeg 가 세워서 낸다(세로 180x320). */
    @Test
    void 회전_영상의_끝을_넘는_자리도_마지막_프레임을_세워서_찍는다() throws Exception {
        Path video = 회전_표지를_단(끝만_초록인_영상());
        Recording runner = new Recording();
        Path picture = dir.resolve("thumb.jpg");

        assertThat(recording(runner).extract(video, 999_999, picture)).isTrue();

        assertThat(runner.seeks).containsExactly("2.000");
        assertThat(sha256(picture)).isEqualTo(sha256(마지막_프레임(video)));
        BufferedImage image = ImageIO.read(picture.toFile());
        assertThat(image.getWidth()).isEqualTo(180);
        assertThat(image.getHeight()).isEqualTo(320);
    }

    /**
     * 소리가 0초에, 영상이 0.5초에 시작한다(format 시작 0 · 영상 첫 PTS 0.5). 입력 시각 0은 format 시작이라 마지막 프레임 시작은
     * 2.5초다. 영상 첫 PTS 를 빼면 2.0초가 되어 빨강이 찍힌다.
     */
    @Test
    void 영상이_format_시작보다_늦게_시작해도_마지막_프레임은_format_시작_기준이다() throws Exception {
        Path video = 영상이_늦게_시작하는(끝만_초록인_영상());
        Recording runner = new Recording();
        Path picture = dir.resolve("thumb.jpg");

        assertThat(recording(runner).extract(video, 999_999, picture)).isTrue();

        assertThat(runner.seeks).containsExactly("2.500");
        assertThat(sha256(picture)).isEqualTo(sha256(마지막_프레임(video)));
        assertThat(초록이다(picture)).isTrue();
    }

    /** format 시작이 1.5초인 파일: 마지막 프레임 시작은 3.5 − 1.5 = 2.0초다. 빼지 않으면 끝을 넘어 0초(빨강)로 다시 뽑는다. */
    @Test
    void format_시작이_0이_아닌_파일은_그만큼_빼고_자른다() throws Exception {
        Path video = 시작_시각을_민(끝만_초록인_영상());
        Recording runner = new Recording();
        Path picture = dir.resolve("thumb.jpg");

        assertThat(recording(runner).extract(video, 999_999, picture)).isTrue();

        assertThat(runner.seeks).containsExactly("2.000");
        assertThat(sha256(picture)).isEqualTo(sha256(마지막_프레임(video)));
        assertThat(초록이다(picture)).isTrue();
    }

    // ── 가짜 실행기 ────────────────────────────────────────────

    /** 3초 · 30fps · B 프레임 없음: 마지막 프레임 시작 2.966667초 → 2990 자리는 2.966초로 자른다(고정 여유를 빼지 않는다). */
    @Test
    void 끝을_넘는_자리는_마지막_프레임_시작으로_자른다(CapturedOutput output) {
        Recorder runner = new Recorder(프레임들(90, 0), true);

        assertThat(fake(runner).extract(dir.resolve("v.mp4"), 2_990, dir.resolve("t.jpg"))).isTrue();

        assertThat(runner.seeks).containsExactly("2.966");
        assertThat(output.getAll()).contains("upload.thumbnail_scene_clamped offsetMs=2990 lastFrameMs=2966");
    }

    /** 결과 끝 값(결과 길이)이 그대로 와도 고정 여유 없이 마지막 프레임 시작이다. */
    @Test
    void 결과_길이_자리도_마지막_프레임_시작이다() {
        Recorder runner = new Recorder(프레임들(90, 0), true);

        fake(runner).extract(dir.resolve("v.mp4"), 3_000, dir.resolve("t.jpg"));

        assertThat(runner.seeks).containsExactly("2.966");
    }

    /** 패킷은 DTS 순으로 온다. B 프레임이면 마지막 줄이 아니라 PTS 최댓값이 마지막 표시 프레임이다. */
    @Test
    void B_프레임이면_마지막_줄이_아니라_PTS_최댓값으로_자른다() {
        Recorder runner = new Recorder("""
                pts_time=0.000000
                pts_time=0.100000
                pts_time=0.033333
                pts_time=0.066667
                start_time=0.000000
                """, true);

        fake(runner).extract(dir.resolve("v.mp4"), 999, dir.resolve("t.jpg"));

        assertThat(runner.seeks).containsExactly("0.100");
    }

    /** 입력 시각 0은 format.start_time 이다. P_last 는 PTS 최댓값에서 그것을 뺀 값이다. */
    @Test
    void 마지막_프레임_시작은_format_시작_시각을_뺀_값이다() {
        Recorder runner = new Recorder(프레임들(301, 0.14), true);

        fake(runner).extract(dir.resolve("v.mp4"), 12_000, dir.resolve("t.jpg"));

        assertThat(runner.seeks).containsExactly("10.000");
    }

    @Test
    void 끝_안의_자리는_그대로이고_자른_기록을_남기지_않는다(CapturedOutput output) {
        Recorder runner = new Recorder(프레임들(90, 0), true);

        fake(runner).extract(dir.resolve("v.mp4"), 2_966, dir.resolve("t.jpg"));

        assertThat(runner.seeks).containsExactly("2.966");
        assertThat(output.getAll()).doesNotContain("upload.thumbnail_scene_clamped");
    }

    @Test
    void 영초_자리는_0초다() {
        Recorder runner = new Recorder(프레임들(90, 0), true);

        fake(runner).extract(dir.resolve("v.mp4"), 0, dir.resolve("t.jpg"));

        assertThat(runner.seeks).containsExactly("0.000");
    }

    /** 영상 패킷이 없거나 시작 시각을 모르면 마지막 프레임을 못 잰 것이다. 고른 자리 그대로 해 본다. */
    @Test
    void 영상_패킷이나_시작_시각이_없으면_고른_자리_그대로_해_본다() {
        Recorder noPackets = new Recorder("start_time=0.000000\n", true);
        Recorder noStart = new Recorder("pts_time=1.000000\nstart_time=N/A\n", true);

        fake(noPackets).extract(dir.resolve("v.mp4"), 4_200, dir.resolve("t.jpg"));
        fake(noStart).extract(dir.resolve("v.mp4"), 4_200, dir.resolve("u.jpg"));

        assertThat(noPackets.seeks).containsExactly("4.200");
        assertThat(noStart.seeks).containsExactly("4.200");
    }

    /** PTS 가 {@code N/A} 인 패킷(PTS 없는 패킷)은 건너뛰고 나머지의 최댓값으로 자른다. */
    @Test
    void PTS_가_N_A_인_패킷은_건너뛰고_나머지로_자른다() {
        Recorder runner = new Recorder("""
                pts_time=0.000000
                pts_time=N/A
                pts_time=2.966667
                pts_time=N/A
                start_time=0.000000
                """, true);

        fake(runner).extract(dir.resolve("v.mp4"), 9_000, dir.resolve("t.jpg"));

        assertThat(runner.seeks).containsExactly("2.966");
    }

    /** PTS 가 전부 {@code N/A} 면 마지막 프레임을 못 잰 것이다. 고른 자리 그대로 해 본다. */
    @Test
    void PTS_가_전부_N_A_면_고른_자리_그대로_해_본다(CapturedOutput output) {
        Recorder runner = new Recorder("pts_time=N/A\npts_time=N/A\nstart_time=0.000000\n", true);

        assertThat(fake(runner).extract(dir.resolve("v.mp4"), 4_200, dir.resolve("t.jpg"))).isTrue();

        assertThat(runner.seeks).containsExactly("4.200");
        assertThat(output.getAll()).contains("upload.thumbnail_probe_failed");
    }

    /** 숫자가 아닌 값이 한 줄이라도 있으면 판독 실패다. 나머지 줄로 어림하지 않고 고른 자리 그대로 해 본다. */
    @Test
    void 숫자가_아닌_판독값이_있으면_고른_자리_그대로_해_본다(CapturedOutput output) {
        Recorder badPts = new Recorder(프레임들(90, 0) + "pts_time=1.0.0\n", true);
        Recorder badStart = new Recorder(프레임들(90, 0).replace("start_time=0.000000", "start_time=abc"), true);

        fake(badPts).extract(dir.resolve("v.mp4"), 4_200, dir.resolve("t.jpg"));
        fake(badStart).extract(dir.resolve("v.mp4"), 4_200, dir.resolve("u.jpg"));

        assertThat(badPts.seeks).containsExactly("4.200");
        assertThat(badStart.seeks).containsExactly("4.200");
        assertThat(output.getAll()).contains("upload.thumbnail_probe_failed");
    }

    /** ms 로 바꾼 값이 long 을 넘으면 판독 실패다. 예외가 새지 않고 고른 자리 그대로 해 본다. */
    @Test
    void long_범위를_넘는_판독값이면_고른_자리_그대로_해_본다(CapturedOutput output) {
        Recorder huge = new Recorder("pts_time=0.000000\npts_time=99999999999999999999.000000\nstart_time=0.000000\n",
                true);
        Recorder exponent = new Recorder("pts_time=1E+30\nstart_time=0.000000\n", true);

        assertThat(fake(huge).extract(dir.resolve("v.mp4"), 4_200, dir.resolve("t.jpg"))).isTrue();
        assertThat(fake(exponent).extract(dir.resolve("v.mp4"), 4_200, dir.resolve("u.jpg"))).isTrue();

        assertThat(huge.seeks).containsExactly("4.200");
        assertThat(exponent.seeks).containsExactly("4.200");
        assertThat(output.getAll()).contains("upload.thumbnail_probe_failed");
    }

    @Test
    void 음수_자리는_0초다() {
        Recorder runner = new Recorder(끝_10초(), true);

        fake(runner).extract(dir.resolve("v.mp4"), -5, dir.resolve("t.jpg"));

        assertThat(runner.seeks).containsExactly("0.000");
    }

    /** 첫 시도가 실패하며 덜 쓴 파일을 남겼다. 그 파일을 지우고 0초로 한 번 더 뽑는다. */
    @Test
    void 실패하면_덜_쓴_파일을_지우고_0초로_한_번_더_뽑는다() {
        Recorder runner = new Recorder(끝_10초(), true);
        runner.failFirstLeavingJunk = true;

        boolean ok = fake(runner).extract(dir.resolve("v.mp4"), 3_000, dir.resolve("t.jpg"));

        assertThat(ok).isTrue();
        assertThat(runner.seeks).containsExactly("3.000", "0.000");
    }

    /** 종료 코드 0인데 파일이 없다(판에 따라 그렇다). 0초로 한 번 더. */
    @Test
    void 성공했는데_파일이_없어도_0초로_한_번_더_뽑는다() {
        Recorder runner = new Recorder(끝_10초(), true);
        runner.firstWritesNothing = true;

        assertThat(fake(runner).extract(dir.resolve("v.mp4"), 3_000, dir.resolve("t.jpg"))).isTrue();
        assertThat(runner.seeks).containsExactly("3.000", "0.000");
    }

    @Test
    void 영초_재시도도_실패하면_실패를_돌려준다() {
        Recorder runner = new Recorder(끝_10초(), false);

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
        Recorder runner = new Recorder(끝_10초(), true);
        runner.timeout = true;

        assertThat(fake(runner).extract(dir.resolve("v.mp4"), 3_000, dir.resolve("t.jpg"))).isFalse();
    }

    @Test
    void 명령은_명세_모양이다() {
        Recorder runner = new Recorder(끝_10초(), true);

        fake(runner).extract(dir.resolve("v.mp4"), 1_234, dir.resolve("t.jpg"));

        assertThat(runner.commands.get(1)).containsExactly("/x/ffmpeg", "-hide_banner", "-nostdin", "-y", "-ss",
                "1.234", "-i", dir.resolve("v.mp4").toString(), "-frames:v", "1", "-q:v", "2",
                dir.resolve("t.jpg").toString());
        assertThat(runner.commands.getFirst()).containsExactly("/x/ffprobe", "-v", "error", "-select_streams", "v:0",
                "-show_entries", "packet=pts_time:format=start_time", "-of", "default=noprint_wrappers=1",
                dir.resolve("v.mp4").toString());
    }

    // ── 도우미 ──────────────────────────────────────────────────

    private SceneExtractor real() {
        return new SceneExtractor(new ProcessRunner(), "ffmpeg", "ffprobe", Duration.ofSeconds(30));
    }

    private SceneExtractor recording(Recording runner) {
        return new SceneExtractor(runner, "ffmpeg", "ffprobe", Duration.ofSeconds(30));
    }

    private SceneExtractor fake(Recorder runner) {
        return new SceneExtractor(runner, "/x/ffmpeg", "/x/ffprobe", Duration.ofSeconds(30));
    }

    /** ffprobe 출력: 30fps 프레임 {@code count}개(B 프레임 없음)의 패킷 PTS와 format 시작 시각. */
    private static String 프레임들(int count, double startSeconds) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < count; i++) {
            out.append(String.format(Locale.ROOT, "pts_time=%.6f%n", startSeconds + i / 30.0));
        }
        return out.append(String.format(Locale.ROOT, "start_time=%.6f%n", startSeconds)).toString();
    }

    /** 10초 · 30fps — 마지막 프레임 시작 9.966초. 끝 자르기와 상관없는 시험용. */
    private static String 끝_10초() {
        return 프레임들(300, 0);
    }

    /** 3초 빨강(320x180·30fps·B 프레임 없음) — 마지막 프레임 시작 2.966667초. */
    private Path 빨강_3초_영상() throws Exception {
        Assumptions.ffmpeg();
        Path video = dir.resolve("red3.mp4");
        run("ffmpeg", "-hide_banner", "-nostdin", "-y", "-f", "lavfi", "-i", "color=c=red:s=320x180:r=30:d=3",
                "-c:v", "libx264", "-bf", "0", "-pix_fmt", "yuv420p", video.toString());
        return video;
    }

    /**
     * 2초 빨강 뒤 초록 한 프레임(320x180·30fps·libx264 기본 B 프레임). 시험이 뜻을 가지려면 DTS 최댓값이 PTS 최댓값보다 앞서야
     * 한다 — 아니면 여기서 멈춘다.
     */
    private Path 끝만_초록인_B_프레임_영상() throws Exception {
        Assumptions.ffmpeg();
        Path video = dir.resolve("bf.mp4");
        run("ffmpeg", "-hide_banner", "-nostdin", "-y",
                "-f", "lavfi", "-i", "color=c=red:s=320x180:r=30:d=2",
                "-f", "lavfi", "-i", "color=c=green:s=320x180:r=30:d=0.0333",
                "-filter_complex", "[0:v][1:v]concat=n=2:v=1:a=0[v]", "-map", "[v]",
                "-c:v", "libx264", "-pix_fmt", "yuv420p", video.toString());
        String packets = new ProcessRunner().run(List.of("ffprobe", "-v", "error", "-select_streams", "v:0",
                "-show_entries", "packet=pts_time,dts_time", "-of", "default=noprint_wrappers=1", video.toString()),
                dir, Instant.now().plusSeconds(30)).stdout();
        assertThat(최댓값(packets, "dts_time=")).as("B 프레임이라 DTS 최댓값이 PTS 최댓값보다 앞서야 한다")
                .isLessThan(최댓값(packets, "pts_time="));
        return video;
    }

    /**
     * 1초 빨강(30fps) 뒤 0.9초 파랑과 초록 한 프레임(10fps) — 가변 프레임으로 담는다(320x180 · B 프레임 없음). 프레임 간격이 한
     * 가지면 가변 프레임 시험이 아니다 — 여기서 멈춘다.
     */
    private Path 끝만_초록인_가변_프레임_영상() throws Exception {
        Assumptions.ffmpeg();
        Path video = dir.resolve("vfr.mp4");
        run("ffmpeg", "-hide_banner", "-nostdin", "-y",
                "-f", "lavfi", "-i", "color=c=red:s=320x180:r=30:d=1",
                "-f", "lavfi", "-i", "color=c=blue:s=320x180:r=10:d=0.9",
                "-f", "lavfi", "-i", "color=c=green:s=320x180:r=10:d=0.1",
                "-filter_complex", "[0:v][1:v][2:v]concat=n=3:v=1:a=0[v]", "-map", "[v]", "-fps_mode", "vfr",
                "-c:v", "libx264", "-bf", "0", "-pix_fmt", "yuv420p", video.toString());
        List<Double> pts = probe(video, "-select_streams", "v:0", "-show_entries", "packet=pts_time").lines()
                .filter(l -> l.startsWith("pts_time=")).map(l -> Double.parseDouble(l.substring(9))).sorted().toList();
        assertThat(pts.get(1) - pts.get(0)).as("앞은 30fps").isLessThan(0.05);
        assertThat(pts.getLast() - pts.get(pts.size() - 2)).as("뒤는 10fps").isGreaterThan(0.09);
        return video;
    }

    /** 2초 빨강 뒤 초록 한 프레임(320x180 · 30fps · libx264 기본). 아래 셋이 이 파일을 다른 꼴로 옮긴다. */
    private Path 끝만_초록인_영상() throws Exception {
        Assumptions.ffmpeg();
        Path video = dir.resolve("plain.mp4");
        run("ffmpeg", "-hide_banner", "-nostdin", "-y",
                "-f", "lavfi", "-i", "color=c=red:s=320x180:r=30:d=2",
                "-f", "lavfi", "-i", "color=c=green:s=320x180:r=30:d=0.0333",
                "-filter_complex", "[0:v][1:v]concat=n=2:v=1:a=0[v]", "-map", "[v]",
                "-c:v", "libx264", "-pix_fmt", "yuv420p", video.toString());
        return video;
    }

    /** 다시 인코딩하지 않고 표시 행렬 90도만 단다. */
    private Path 회전_표지를_단(Path plain) throws Exception {
        Path video = dir.resolve("rot.mp4");
        run("ffmpeg", "-hide_banner", "-nostdin", "-y", "-display_rotation", "90", "-i", plain.toString(),
                "-c", "copy", video.toString());
        assertThat(probe(video, "-select_streams", "v:0", "-show_entries", "stream_side_data=rotation"))
                .as("회전 표지가 실려야 한다").contains("rotation=90");
        return video;
    }

    /** 0초부터 소리, 0.5초부터 영상. 영상 첫 PTS 가 format 시작보다 늦지 않으면 시험이 뜻이 없다 — 여기서 멈춘다. */
    private Path 영상이_늦게_시작하는(Path plain) throws Exception {
        Path video = dir.resolve("late.mp4");
        run("ffmpeg", "-hide_banner", "-nostdin", "-y",
                "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=3",
                "-itsoffset", "0.5", "-i", plain.toString(),
                "-map", "0:a", "-map", "1:v", "-c:v", "copy", "-c:a", "aac", video.toString());
        assertThat(시각(video, "format=start_time")).isEqualTo(0.0);
        assertThat(시각(video, "stream=start_time", "-select_streams", "v:0")).isEqualTo(0.5);
        return video;
    }

    /** 다시 인코딩하지 않고 시각만 1.5초 민다(format 시작 1.5초). format 시작이 그대로 0이면 시험이 뜻이 없다 — 여기서 멈춘다. */
    private Path 시작_시각을_민(Path plain) throws Exception {
        Path video = dir.resolve("offset.mp4");
        run("ffmpeg", "-hide_banner", "-nostdin", "-y", "-i", plain.toString(), "-c", "copy",
                "-output_ts_offset", "1.5", video.toString());
        assertThat(시각(video, "format=start_time")).isEqualTo(1.5);
        return video;
    }

    private String probe(Path video, String... entries) {
        List<String> command = new ArrayList<>(List.of("ffprobe", "-v", "error"));
        command.addAll(List.of(entries));
        command.addAll(List.of("-of", "default=noprint_wrappers=1", video.toString()));
        return new ProcessRunner().run(command, dir, Instant.now().plusSeconds(30)).stdout();
    }

    private double 시각(Path video, String entry, String... select) {
        List<String> args = new ArrayList<>(List.of(select));
        args.addAll(List.of("-show_entries", entry));
        String out = probe(video, args.toArray(String[]::new)).strip();
        return Double.parseDouble(out.substring(out.indexOf('=') + 1));
    }

    /** 오라클: 프레임을 거꾸로 세워 첫 장을 뽑으면 표시 순 마지막 프레임이다(같은 JPEG 설정). */
    private Path 마지막_프레임(Path video) throws Exception {
        Path last = dir.resolve("last.jpg");
        run("ffmpeg", "-hide_banner", "-nostdin", "-y", "-i", video.toString(), "-vf", "reverse",
                "-frames:v", "1", "-q:v", "2", last.toString());
        return last;
    }

    private void run(String... command) throws Exception {
        Process p = new ProcessBuilder(command)
                .redirectErrorStream(true).redirectOutput(dir.resolve("gen.log").toFile()).start();
        assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(p.exitValue()).isZero();
    }

    private static double 최댓값(String lines, String key) {
        return lines.lines().filter(l -> l.startsWith(key))
                .mapToDouble(l -> Double.parseDouble(l.substring(key.length()))).max().orElseThrow();
    }

    private static String sha256(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    private static boolean 초록이다(Path jpg) throws IOException {
        BufferedImage image = ImageIO.read(jpg.toFile());
        int rgb = image.getRGB(image.getWidth() / 2, image.getHeight() / 2);
        int g = (rgb >> 8) & 0xFF;
        return g > ((rgb >> 16) & 0xFF) && g > (rgb & 0xFF);
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

    /** 진짜로 돌리되 ffmpeg 의 {@code -ss} 값을 기록한다. */
    private static final class Recording extends ProcessRunner {
        final List<String> seeks = new ArrayList<>();

        @Override
        public Result run(List<String> command, Path workDir, Instant deadline) {
            if (command.contains("-ss")) {
                seeks.add(command.get(command.indexOf("-ss") + 1));
            }
            return super.run(command, workDir, deadline);
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
