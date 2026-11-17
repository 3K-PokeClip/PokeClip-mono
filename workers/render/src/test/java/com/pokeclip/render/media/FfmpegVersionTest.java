package com.pokeclip.render.media;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.assertj.core.api.Assertions.assertThat;

/** 시작 로그에 싣는 ffmpeg 판 첫 줄(PDT 설계 7.7 · 15절 {@code render.worker_started}). 진짜 ffmpeg 대신 판을 흉내 내는 스크립트를 쓴다. */
class FfmpegVersionTest {

    @TempDir
    Path dir;

    @Test
    void 판_출력의_첫_줄을_돌려준다() throws IOException {
        Path ffmpeg = script("echo \"ffmpeg version 8.0.1-3ubuntu2 args=$*\"", "echo 'built with gcc 15'");

        String line = FfmpegVersion.firstLine(new ProcessRunner(), ffmpeg.toString(), dir.resolve("work"));

        assertThat(line).isEqualTo("ffmpeg version 8.0.1-3ubuntu2 args=-hide_banner -version");
    }

    @Test
    void 실행_파일이_없으면_unavailable() {
        String line = FfmpegVersion.firstLine(new ProcessRunner(), dir.resolve("no-ffmpeg").toString(), dir.resolve("work"));

        assertThat(line).isEqualTo(FfmpegVersion.UNAVAILABLE);
    }

    @Test
    void 판_명령이_실패하면_unavailable() throws IOException {
        Path ffmpeg = script("echo 'ffmpeg version 8.0.1-3ubuntu2'", "exit 1");

        assertThat(FfmpegVersion.firstLine(new ProcessRunner(), ffmpeg.toString(), dir.resolve("work")))
                .isEqualTo(FfmpegVersion.UNAVAILABLE);
    }

    @Test
    void 출력이_비면_unavailable() throws IOException {
        Path ffmpeg = script("exit 0");

        assertThat(FfmpegVersion.firstLine(new ProcessRunner(), ffmpeg.toString(), dir.resolve("work")))
                .isEqualTo(FfmpegVersion.UNAVAILABLE);
    }

    private Path script(String... body) throws IOException {
        Path file = dir.resolve("fake-ffmpeg");
        Files.writeString(file, "#!/bin/sh\n" + String.join("\n", body) + "\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwx------"));
        return file;
    }
}
