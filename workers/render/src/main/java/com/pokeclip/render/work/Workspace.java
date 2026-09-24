package com.pokeclip.render.work;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Stream;

/** 주문 하나의 임시 폴더. 성공이든 실패든 닫을 때 통째로 지운다. 조각 수백 MB가 디스크에 쌓이지 않게. */
final class Workspace implements AutoCloseable {

    private final Path dir;

    Workspace(Path root, UUID jobId) {
        try {
            Files.createDirectories(root);
            this.dir = Files.createTempDirectory(root, "job-" + jobId + "-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    Path dir() {
        return dir;
    }

    Path file(String name) {
        return dir.resolve(name);
    }

    @Override
    public void close() {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // 다음 기동 때 운영자가 지운다. 여기서 던지면 원래 실패를 가린다.
        }
    }
}
