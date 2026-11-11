package com.pokeclip.render.work;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/** 주문 작업 폴더 정리(sec L4) — 다 지우면 조용하고, 남기면 WARN 으로 주문 번호와 남은 경로를 남긴다. 닫기는 던지지 않는다. */
class WorkspaceTest {

    private static final UUID JOB = UUID.fromString("00000000-0000-0000-0000-0000000000b4");

    @TempDir
    Path root;

    private ListAppender<ILoggingEvent> logs;
    private Path locked;

    @BeforeEach
    void setUp() {
        logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger(Workspace.class)).addAppender(logs);
    }

    @AfterEach
    void tearDown() throws IOException {
        ((Logger) LoggerFactory.getLogger(Workspace.class)).detachAppender(logs);
        if (locked != null && Files.exists(locked)) {
            // @TempDir 가 지울 수 있게 권한을 되돌린다
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    void 닫으면_폴더를_통째로_지우고_경고가_없다() throws IOException {
        Workspace workspace = new Workspace(root, JOB);
        Files.createDirectories(workspace.file("sub"));
        Files.writeString(workspace.file("sub/a.nut"), "x");

        workspace.close();

        assertThat(workspace.dir()).doesNotExist();
        assertThat(logs.list).isEmpty();
    }

    @Test
    void 지우지_못한_것이_남으면_주문_번호와_남은_경로를_경고로_남기고_던지지_않는다() throws IOException {
        assumeThat(root.getFileSystem().supportedFileAttributeViews()).as("POSIX 권한 필요").contains("posix");
        Workspace workspace = new Workspace(root, JOB);
        locked = workspace.file("locked");
        Files.createDirectories(locked);
        Files.writeString(locked.resolve("a.nut"), "x");
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("r-x------"));
        assumeThat(Files.isWritable(locked)).as("권한으로 지우기를 막을 수 있어야 한다(root 아님)").isFalse();

        workspace.close();

        assertThat(workspace.dir()).exists();
        assertThat(logs.list).singleElement().satisfies(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.WARN);
            // 남은 것 = 작업 폴더 · locked · a.nut. 첫 원인은 a.nut 지우기 거절(예외 이름은 파일 시스템마다 다를 수 있다)
            assertThat(e.getFormattedMessage()).startsWith("render.workspace_cleanup_failed jobId=" + JOB
                    + " dir=" + workspace.dir() + " left=3 err=").doesNotEndWith("err=unknown");
        });
    }
}
