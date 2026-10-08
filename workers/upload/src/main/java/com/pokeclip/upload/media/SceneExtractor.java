package com.pokeclip.upload.media;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * 완성 영상에서 썸네일로 쓸 한 장면을 JPEG로 뽑는다(POK-291 명세 §7). 크기는 줄이지 않는다(출력 해상도 그대로).
 *
 * <p>🔴 ffmpeg는 마지막 프레임 시각을 조금만 넘겨도 파일 없이 실패한다(렌더 일꾼 실측: 1ms만 넘어도 종료 코드 234, 표준에러 첫 줄은
 * 엉뚱한 「Non full-range YUV」 경고다). 그래서
 * <ol>
 *   <li>ffprobe로 길이를 재 고른 자리를 {@code [0, 길이−100ms]}로 <b>당겨 맞춘다</b>(길이를 못 재면 고른 자리 그대로).</li>
 *   <li>그래도 파일이 없으면 0초로 한 번 더 뽑는다.</li>
 * </ol>
 * 실패해도 예외를 던지지 않고 false를 돌려준다: 썸네일 실패가 영상 보고를 막으면 안 된다.
 */
public class SceneExtractor {

    private static final Logger log = LoggerFactory.getLogger(SceneExtractor.class);
    /** 끝에서 이만큼 앞으로 당긴다. 30fps 한 프레임(33ms)보다 넉넉하다. */
    static final long TAIL_MARGIN_MS = 100;

    private final ProcessRunner runner;
    private final String ffmpeg;
    private final String ffprobe;
    private final Duration timeout;

    public SceneExtractor(ProcessRunner runner, String ffmpeg, String ffprobe, Duration timeout) {
        this.runner = runner;
        this.ffmpeg = ffmpeg;
        this.ffprobe = ffprobe;
        this.timeout = timeout;
    }

    /** @return true면 {@code picture}에 그림이 있다 */
    public boolean extract(Path video, long offsetMs, Path picture) {
        // 실행 폴더를 그림 폴더로 바꾸므로 인자도 절대 경로로 넘긴다. 상대 경로(UPLOAD_WORK_DIR=work 등)를 그대로 넘기면
        // ffprobe·ffmpeg가 「그림 폴더/상대 경로」를 찾다 늘 실패한다.
        video = video.toAbsolutePath();
        picture = picture.toAbsolutePath();
        Path dir = picture.getParent();
        long durationMs = durationMs(video, dir);
        long at = durationMs < 0 ? Math.max(0, offsetMs)
                : Math.clamp(offsetMs, 0, Math.max(0, durationMs - TAIL_MARGIN_MS));
        if (capture(video, at, picture, dir)) {
            return true;
        }
        if (at > 0 && capture(video, 0, picture, dir)) {
            log.info("upload.thumbnail_scene_fallback offsetMs={} durationMs={}", offsetMs, durationMs);
            return true;
        }
        return false;
    }

    /** @return 길이(ms). 못 재면 -1 */
    private long durationMs(Path video, Path dir) {
        try {
            String out = runner.run(List.of(ffprobe, "-v", "error", "-show_entries", "format=duration",
                    "-of", "default=noprint_wrappers=1:nokey=1", video.toString()), dir, deadline()).stdout();
            return Math.round(Double.parseDouble(out.strip()) * 1000);
        } catch (RuntimeException e) {
            log.warn("upload.thumbnail_probe_failed what={}", e.getMessage());
            return -1;
        }
    }

    /** 한 번 뽑는다. 실패하면서 빈·덜 쓴 파일을 남겼으면 지운다(남기면 그것을 그림으로 보고 올린다). */
    private boolean capture(Path video, long atMs, Path picture, Path dir) {
        try {
            runner.run(command(video, atMs, picture), dir, deadline());
        } catch (ProcessRunner.Failed | ProcessRunner.Timeout e) {
            log.warn("upload.thumbnail_ffmpeg_failed atMs={} what={}", atMs, e.getMessage());
            deleteQuietly(picture);
            return false;
        }
        try {
            if (Files.exists(picture) && Files.size(picture) > 0) {
                return true;
            }
        } catch (IOException e) {
            // 아래에서 지우고 실패로 본다
        }
        deleteQuietly(picture);
        return false;
    }

    List<String> command(Path video, long atMs, Path picture) {
        return List.of(ffmpeg, "-hide_banner", "-nostdin", "-y",
                "-ss", String.format(Locale.ROOT, "%.3f", atMs / 1000.0),
                "-i", video.toString(),
                "-frames:v", "1",
                "-q:v", "2",
                picture.toString());
    }

    private Instant deadline() {
        return Instant.now().plus(timeout);
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // 주문이 끝날 때 처리기가 다시 지운다
        }
    }
}
