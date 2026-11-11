package com.pokeclip.upload.media;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * 완성 영상에서 썸네일로 쓸 한 장면을 JPEG로 뽑는다(POK-291 명세 §7). 크기는 줄이지 않는다(출력 해상도 그대로).
 *
 * <p>🔴 ffmpeg는 마지막 프레임 시각을 조금만 넘겨도 파일 없이 실패한다(1ms만 넘어도 종료 코드 234, 표준에러 첫 줄은 엉뚱한
 * 「Non full-range YUV」 경고다). 그래서
 * <ol>
 *   <li>ffprobe로 <b>마지막 표시 프레임 시작</b>(P_last = 영상 패킷 PTS 최댓값 − {@code format.start_time}, ms 로 내림)을 읽어
 *       고른 자리를 {@code [0, P_last]}로 자른다. 그 자리의 {@code -ss}가 실제 마지막 표시 프레임을 낸다. 패킷은 DTS 순이라 B 프레임
 *       영상에서는 마지막 줄이 아니라 PTS 최댓값을 쓴다. 결과 끝 규칙(결과 길이 값이 오면 마지막 화면)의 집행자라 고정 여유를 빼지
 *       않는다. 못 읽으면 고른 자리 그대로.</li>
 *   <li>그래도 파일이 없으면 0초로 한 번 더 뽑는다.</li>
 * </ol>
 * 실패해도 예외를 던지지 않고 false를 돌려준다: 썸네일 실패가 영상 보고를 막으면 안 된다.
 */
public class SceneExtractor {

    private static final Logger log = LoggerFactory.getLogger(SceneExtractor.class);
    private static final String PTS_KEY = "pts_time=";
    private static final String START_KEY = "start_time=";

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
        long lastFrameMs = lastFrameMs(video, dir);
        if (lastFrameMs >= 0 && offsetMs > lastFrameMs) {
            log.info("upload.thumbnail_scene_clamped offsetMs={} lastFrameMs={}", offsetMs, lastFrameMs);
        }
        long at = lastFrameMs < 0 ? Math.max(0, offsetMs) : Math.clamp(offsetMs, 0, lastFrameMs);
        if (capture(video, at, picture, dir)) {
            return true;
        }
        if (at > 0 && capture(video, 0, picture, dir)) {
            log.info("upload.thumbnail_scene_fallback offsetMs={} lastFrameMs={}", offsetMs, lastFrameMs);
            return true;
        }
        return false;
    }

    /** @return 마지막 표시 프레임 시작(ms, 입력 시각 0 기준). 못 재면 -1 */
    private long lastFrameMs(Path video, Path dir) {
        try {
            String out = runner.run(List.of(ffprobe, "-v", "error", "-select_streams", "v:0",
                    "-show_entries", "packet=pts_time:format=start_time", "-of", "default=noprint_wrappers=1",
                    video.toString()), dir, deadline()).stdout();
            return lastFrameMs(out);
        } catch (RuntimeException e) {
            log.warn("upload.thumbnail_probe_failed what={}", e.getMessage());
            return -1;
        }
    }

    /**
     * ffprobe 출력({@code pts_time=}·{@code start_time=} 줄)에서 P_last 를 낸다. 소수 그대로 빼고 ms 로 내린다(반올림하면 마지막
     * 프레임 시작을 넘어 ffmpeg가 실패한다). PTS가 {@code N/A}인 패킷은 건너뛴다.
     *
     * @throws IllegalArgumentException 영상 패킷 PTS나 시작 시각이 없거나 숫자가 아니면
     */
    private static long lastFrameMs(String probeOut) {
        BigDecimal maxPts = null;
        BigDecimal start = null;
        for (String line : probeOut.lines().map(String::strip).toList()) {
            if (line.startsWith(PTS_KEY) && !line.endsWith("N/A")) {
                BigDecimal pts = new BigDecimal(line.substring(PTS_KEY.length()));
                maxPts = maxPts == null ? pts : maxPts.max(pts);
            } else if (line.startsWith(START_KEY)) {
                start = new BigDecimal(line.substring(START_KEY.length()));
            }
        }
        if (maxPts == null || start == null) {
            throw new IllegalArgumentException("영상 패킷 PTS 또는 시작 시각 없음");
        }
        return Math.max(0, maxPts.subtract(start).movePointRight(3).setScale(0, RoundingMode.FLOOR).longValueExact());
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
