package com.pokeclip.render.work;

import com.pokeclip.render.RenderProperties;
import com.pokeclip.render.job.EnvelopeParser;
import com.pokeclip.render.media.MediaProbe;
import com.pokeclip.render.media.ProcessRunner;
import com.pokeclip.render.report.ClipReporter;
import com.pokeclip.render.storage.S3Store;
import com.pokeclip.render.support.DirectSegments;
import com.pokeclip.render.support.DirectSegments.Recording;
import com.pokeclip.render.support.FakeClip;
import com.pokeclip.render.support.Ffmpeg;
import com.pokeclip.render.support.Fixtures;
import com.pokeclip.render.support.PlanOrder;
import com.pokeclip.render.support.RecordingRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * A′ 중간 파일이 모든 갈래에서 남지 않는다(PDT 설계 r7 16.2 P33 · 계획 C7). 주문 하나를 {@link JobProcessor} 로 끝까지 돌리고 —
 * 렌더 · ffmpeg 는 실물, S3 만 가짜, clip 보고 문은 HTTP 가짜 — 끝난 뒤 작업 뿌리 폴더가 통째로 비었는지 본다. 중간 파일이 실제로
 * 생겼는지는 실패를 넣기 직전에 작업 폴더를 들여다본 기록으로 함께 단언한다(빈 단언 방지).
 */
class JoinedWorkspaceTest {

    @TempDir
    static Path fixtures;
    private static Recording ra;

    @TempDir
    Path workRoot;

    private FakeClip clip;
    private final RecordingRunner runner = new RecordingRunner();
    private final S3Store store = mock(S3Store.class);
    private final List<List<String>> seen = new ArrayList<>();
    private JobProcessor processor;

    @BeforeAll
    static void segments() {
        org.assertj.core.api.Assumptions.assumeThat(Ffmpeg.available()).as("ffmpeg 필요").isTrue();
        ra = DirectSegments.write(fixtures, "wa", "testsrc2=size=160x90:rate=30", 8, -1, 51, 4);
    }

    @BeforeEach
    void setUp() {
        clip = new FakeClip();
        doAnswer(inv -> {
            String key = inv.getArgument(1);
            int i = Integer.parseInt(key.substring(key.lastIndexOf('_') + 1, key.lastIndexOf('.'))) - 10;
            Files.copy(ra.pieces().get(i), inv.<Path>getArgument(2));
            return null;
        }).when(store).download(anyString(), anyString(), any(), any());
        RenderProperties props = new RenderProperties(null, null, null, false, "ap-northeast-2", clip.baseUrl(),
                "secret-token", workRoot, "ffmpeg", "ffprobe", Duration.ofMinutes(3), Duration.ofSeconds(600),
                Duration.ofSeconds(1), List.of(Duration.ZERO, Duration.ZERO), null);
        ClipReporter reporter = new ClipReporter(props, Fixtures.MAPPER, d -> { });
        ClipRenderer renderer = new ClipRenderer(store, new MediaProbe(runner, Fixtures.MAPPER, "ffprobe"), runner,
                Fixtures.MAPPER, "ffmpeg", null);
        processor = new JobProcessor(new EnvelopeParser(Fixtures.MAPPER), reporter, renderer, store, workRoot,
                Duration.ofMinutes(3), Clock.systemUTC());
    }

    @AfterEach
    void tearDown() {
        clip.close();
    }

    @Test
    void 성공() {
        runner.intercept((command, dir, deadline, r) -> {
            if (RecordingRunner.isFinalStage(command)) {
                seen.add(listing(dir));
            }
            return null;
        });

        assertThat(processor.process(body(1))).isEqualTo(Disposition.DELETE);

        assertThat(clip.types()).contains("SUCCEEDED");
        assertThat(seen.getFirst()).contains("mid_000.nut", "mid_001.nut", "mid_list.ffconcat", "run_0.ffconcat");
        assertWorkRootEmpty();
    }

    @Test
    void 조각_단계_실패() {
        AtomicInteger pieces = new AtomicInteger();
        runner.intercept((command, dir, deadline, r) -> {
            if (RecordingRunner.isCutPiece(command) && pieces.incrementAndGet() == 2) {
                return fail(command, dir);
            }
            return null;
        });

        assertThat(processor.process(body(1)).kind()).isEqualTo(Disposition.Kind.DELAY);

        assertThat(seen.getFirst()).contains("mid_000.nut").doesNotContain("mid_001.nut");
        assertWorkRootEmpty();
    }

    @Test
    void 최종_단계_실패() {
        runner.intercept((command, dir, deadline, r) -> RecordingRunner.isFinalStage(command) ? fail(command, dir)
                : null);

        assertThat(processor.process(body(1)).kind()).isEqualTo(Disposition.Kind.DELAY);

        assertThat(seen.getFirst()).contains("mid_000.nut", "mid_001.nut", "mid_list.ffconcat");
        assertWorkRootEmpty();
    }

    /** 주문 공통 시한이 최종 단계 도중에 다 된다 — 실제 실행기가 ffmpeg 를 강제로 끝내고 시한 초과를 던진다. */
    @Test
    void 공통_시한_초과() {
        runner.intercept((command, dir, deadline, r) -> {
            if (!RecordingRunner.isFinalStage(command)) {
                return null;
            }
            seen.add(listing(dir));
            return r.proceed(command, dir, Instant.now());
        });

        assertThat(processor.process(body(1)).kind()).isEqualTo(Disposition.Kind.DELAY);

        assertThat(clip.last("RETRY_SCHEDULED").body().path("error").path("message").asString()).contains("시간");
        assertThat(seen.getFirst()).contains("mid_000.nut", "mid_001.nut");
        assertWorkRootEmpty();
    }

    @Test
    void 뒤_출력_실패() {
        runner.intercept((command, dir, deadline, r) -> RecordingRunner.isFinalStage(command)
                && command.getLast().equals("o2.mp4") ? fail(command, dir) : null);

        assertThat(processor.process(body(2)).kind()).isEqualTo(Disposition.Kind.DELAY);

        assertThat(seen.getFirst()).contains("o1.mp4", "mid_000.nut", "mid_001.nut");
        assertWorkRootEmpty();
    }

    /** 일시 실패 뒤 다시 받은 실행은 새 폴더에서 돈다. 앞 실행 폴더와 새 실행 폴더 모두 남지 않는다. */
    @Test
    void 재시도_앞_실행과_새_실행() {
        String body = body(1);
        runner.intercept((command, dir, deadline, r) -> RecordingRunner.isFinalStage(command) ? fail(command, dir)
                : null);
        assertThat(processor.process(body).kind()).isEqualTo(Disposition.Kind.DELAY);
        runner.intercept(null);

        assertThat(processor.process(body)).isEqualTo(Disposition.DELETE);

        List<Path> dirs = runner.workDirs();
        assertThat(dirs).hasSize(2);
        assertThat(dirs.get(0)).isNotEqualTo(dirs.get(1));
        assertThat(dirs).allSatisfy(d -> {
            assertThat(d).doesNotExist();
            assertThat(d.getParent()).isEqualTo(workRoot);
        });
        assertThat(clip.types()).contains("RETRY_SCHEDULED", "SUCCEEDED");
        assertWorkRootEmpty();
    }

    // ---------------------------------------------------------------- 자료

    private String body(int outputs) {
        ObjectNode envelope = PlanOrder.of(UUID.randomUUID()).segment(10, false, false).segment(11, false, true)
                .in(10, 3_000_000).out(11, 500_000).json();
        ObjectNode recipe = (ObjectNode) envelope.get("recipe");
        recipe.putNull("subtitles");
        ArrayNode list = (ArrayNode) recipe.get("outputs");
        if (outputs == 2) {
            // 출력마다 비율이 달라야 한다(레시피 검사). 160x90 에서 1:1 = 높이 전부 · 폭 0.5625
            ObjectNode square = list.addObject().put("outputId", "o2").put("aspect", "SQUARE_1_1");
            square.putObject("crop").put("x", 0.2).put("y", 0.0).put("w", 0.5625).put("h", 1.0);
        }
        return Fixtures.MAPPER.writeValueAsString(envelope);
    }

    private ProcessRunner.Result fail(List<String> command, Path dir) {
        seen.add(listing(dir));
        throw new ProcessRunner.Failed(command.getFirst(), 1, "주입한 실패");
    }

    private static List<String> listing(Path dir) {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void assertWorkRootEmpty() {
        try (Stream<Path> left = Files.walk(workRoot)) {
            assertThat(left.filter(p -> !p.equals(workRoot)).map(Path::toString)).as("작업 뿌리 잔여").isEmpty();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
