package com.pokeclip.render.media;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 깔린 ffmpeg의 판 첫 줄({@code ffmpeg version 8.0.1-3ubuntu2 ...}). 시작 로그에 실어 배포된 판이 고정한 판인지 본다(PDT 설계 7.7 ·
 * 15절 {@code render.worker_started}).
 *
 * <p>판을 못 읽어도 기동은 막지 않는다(지금까지처럼 뜬다). 대신 경고 한 줄과 {@link #UNAVAILABLE}로 남긴다.
 */
public final class FfmpegVersion {

    /** 판을 못 읽었을 때 시작 로그의 {@code ffmpeg=} 값. */
    public static final String UNAVAILABLE = "unavailable";

    private static final Logger log = LoggerFactory.getLogger(FfmpegVersion.class);
    /** {@code -version}은 바로 끝난다. 그래도 자식 프로세스에는 마감을 둔다(ProcessRunner 규칙). */
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private FfmpegVersion() {
    }

    /** @param workDir 출력 임시 파일을 둘 폴더. 없으면 만든다 */
    public static String firstLine(ProcessRunner runner, String ffmpeg, Path workDir) {
        try {
            Files.createDirectories(workDir);
            String stdout = runner.run(List.of(ffmpeg, "-hide_banner", "-version"), workDir, Instant.now().plus(TIMEOUT))
                    .stdout();
            String first = stdout.lines().findFirst().orElse("").strip();
            if (first.isEmpty()) {
                log.warn("render.ffmpeg_version_unavailable err=empty_output");
                return UNAVAILABLE;
            }
            return first;
        } catch (IOException | ProcessRunner.Failed | ProcessRunner.Timeout e) {
            log.warn("render.ffmpeg_version_unavailable err={}", e.getMessage());
            return UNAVAILABLE;
        }
    }
}
