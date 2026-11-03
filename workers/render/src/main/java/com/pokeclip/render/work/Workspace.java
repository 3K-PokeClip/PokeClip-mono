package com.pokeclip.render.work;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/** 주문 하나의 임시 폴더. 성공이든 실패든 닫을 때 통째로 지운다. 조각 수백 MB가 디스크에 쌓이지 않게. */
final class Workspace implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Workspace.class);

    private final UUID jobId;
    private final Path dir;

    Workspace(Path root, UUID jobId) {
        this.jobId = jobId;
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

    /**
     * 깊은 것부터 지운다. 하나를 못 지워도 나머지는 계속 지우고, 남은 것이 있으면 WARN
     * {@code render.workspace_cleanup_failed}(jobId · 남은 폴더 · 남은 항목 수 · 첫 원인)를 남긴다. 던지지 않는다 — 여기서 던지면 원래
     * 결과(성공 보고 · 실패 원인)를 가린다. 남은 것은 다음 기동 때 운영자가 지운다.
     */
    @Override
    public void close() {
        IOException first = null;
        for (Path p : listDeepestFirst()) {
            try {
                Files.deleteIfExists(p);
            } catch (IOException e) {
                // 아래에서 첫 원인과 남은 수로 한 번에 알린다(그 아래가 남은 폴더는 DirectoryNotEmpty 로 온다)
                first = first == null ? e : first;
            }
        }
        if (Files.exists(dir)) {
            log.warn("render.workspace_cleanup_failed jobId={} dir={} left={} err={}", jobId, dir,
                    listDeepestFirst().size(), first == null ? "unknown" : first.getClass().getSimpleName());
        }
    }

    /** 폴더 안 모든 항목(폴더 자신 포함 · 깊은 것 먼저). 읽지 못하는 것은 셀 수 있는 데까지만이다. */
    private List<Path> listDeepestFirst() {
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.sorted(Comparator.reverseOrder()).toList();
        } catch (IOException | UncheckedIOException e) {
            return Files.exists(dir) ? List.of(dir) : List.of();
        }
    }
}
