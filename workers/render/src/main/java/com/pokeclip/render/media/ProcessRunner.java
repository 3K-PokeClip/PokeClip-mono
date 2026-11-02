package com.pokeclip.render.media;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * ffmpeg·ffprobe를 자식 프로세스로 돌린다. 출력은 파일로 흘린다. 파이프로 받으면 버퍼가 차는 순간 자식이 멈춘다.
 *
 * <p>모든 실행에 마감 시각이 있다. 넘기면 자식을 강제로 끝내고 {@link Timeout}을 던진다. 한 주문이 무한히 돌지 않게.
 */
public class ProcessRunner {

    /** @return 종료 코드 0일 때의 결과. 표준출력·표준에러 전문을 들고 있다 */
    public Result run(List<String> command, Path workDir, Instant deadline) {
        Path out = null;
        Path err = null;
        Process process = null;
        try {
            out = Files.createTempFile(workDir, "proc-", ".out");
            err = Files.createTempFile(workDir, "proc-", ".err");
            process = new ProcessBuilder(command)
                    .directory(workDir.toFile())
                    .redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null")))
                    .redirectOutput(out.toFile())
                    .redirectError(err.toFile())
                    .start();
            long waitMs = Math.max(0, Duration.between(Instant.now(), deadline).toMillis());
            if (!process.waitFor(waitMs, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                throw new Timeout(command.get(0));
            }
            String stdout = Files.readString(out, StandardCharsets.UTF_8);
            String stderr = Files.readString(err, StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                throw new Failed(command.get(0), process.exitValue(), tail(stderr));
            }
            return new Result(stdout, stderr);
        } catch (IOException e) {
            throw new Failed(command.get(0), -1, e.getMessage());
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new Timeout(command.get(0));
        } finally {
            deleteQuietly(out);
            deleteQuietly(err);
        }
    }

    private static String tail(String text) {
        String trimmed = text.strip();
        return trimmed.length() <= 2000 ? trimmed : trimmed.substring(trimmed.length() - 2000);
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 주문 폴더째 지워진다
        }
    }

    public record Result(String stdout, String stderr) {
    }

    /** 마감을 넘겼다. */
    public static class Timeout extends RuntimeException {
        public Timeout(String program) {
            super(program + " 시한 초과");
        }
    }

    /** 0이 아닌 코드로 끝났다. {@code detail}은 표준에러 끝부분이다(로그 전용: 사용자에게 내보내지 않는다). */
    public static class Failed extends RuntimeException {
        private final String detail;

        public Failed(String program, int exitCode, String detail) {
            super(program + " 실패(exit " + exitCode + ")");
            this.detail = detail;
        }

        public String detail() {
            return detail;
        }
    }
}
