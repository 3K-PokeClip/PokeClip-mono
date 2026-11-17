package com.pokeclip.render.work;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.pokeclip.render.job.EnvelopeParser;
import com.pokeclip.render.job.ErrorCode;
import com.pokeclip.render.job.JobEnvelope;
import com.pokeclip.render.job.RenderFailure;
import com.pokeclip.render.media.MediaProbe;
import com.pokeclip.render.media.ProcessRunner;
import com.pokeclip.render.media.RenderCommands;
import com.pokeclip.render.media.RenderCommands.VideoPiece;
import com.pokeclip.render.media.SegmentTiming;
import com.pokeclip.render.storage.S3Store;
import com.pokeclip.render.support.DirectSegments;
import com.pokeclip.render.support.DirectSegments.Recording;
import com.pokeclip.render.support.Ffmpeg;
import com.pokeclip.render.support.Fixtures;
import com.pokeclip.render.support.PlanOrder;
import com.pokeclip.render.support.RecordingRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * 조각 단계 중간 파일의 디스크 가드(D35 · kty 2026-11-11 (가)). 조각마다 쓰기 전에 남은 디스크를 보고(상한 + 여유 1GiB), 조각
 * 출력에 바이트 상한({@code -fs})을 건다. 상한에 닿은 파일(ffmpeg 가 잘라 쓰고 정상 종료)은 결과를 짧게 만들어 싱크를 깨므로
 * 종결한다. 렌더 · ffmpeg 는 실물, S3 만 가짜, 남은 디스크는 시험이 정한다.
 */
class JoinedDiskGuardTest {

    private static final int WIDTH = 160;
    private static final int HEIGHT = 90;
    /** 여유분 — 상수를 0 으로 바꾸는 변이도 잡도록 코드 상수를 쓰지 않고 따로 적는다. */
    private static final long RESERVE = 1L << 30;
    /** 조각 하나의 담는 꼴 몫 1MiB — 여유분과 같은 까닭으로 따로 적는다. */
    private static final long CONTAINER = 1L << 20;

    @TempDir
    static Path fixtures;
    private static Recording ra;

    @TempDir
    Path dir;

    private final RecordingRunner runner = new RecordingRunner();
    private final S3Store store = mock(S3Store.class);
    private final List<Path> asked = new ArrayList<>();
    private ListAppender<ILoggingEvent> logs;
    /** 이 앞 명령은 기준 실행({@link #firstCap}) 몫이라 단언에서 뺀다. */
    private int baseline;

    @BeforeAll
    static void segments() {
        assumeThat(Ffmpeg.available()).as("ffmpeg 필요").isTrue();
        ra = DirectSegments.write(fixtures, "da", "testsrc2=size=160x90:rate=30", 8, -1, 61, 4);
    }

    @BeforeEach
    void setUp() {
        doAnswer(inv -> {
            String key = inv.getArgument(1);
            int i = Integer.parseInt(key.substring(key.lastIndexOf('_') + 1, key.lastIndexOf('.'))) - 10;
            Files.copy(ra.pieces().get(i), inv.<Path>getArgument(2));
            return null;
        }).when(store).download(anyString(), anyString(), any(), any());
        logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger(JoinedRenderer.class)).addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(JoinedRenderer.class)).detachAppender(logs);
    }

    @Test
    void 여유분은_1GiB_다() {
        assertThat(JoinedRenderer.DISK_RESERVE_BYTES).isEqualTo(RESERVE);
    }

    /**
     * 상한 = 그 조각이 실제로 쓰는 프레임 수 × 폭 × 높이 × 1.5(yuv420p 날 바이트) × 5/4 + 1MiB — 프레임 수는 남은 중간 파일을
     * ffprobe 로 세어 맞댄다. 160x90 은 화소 14,400 이라 프레임당 27,000 바이트로 나누어떨어진다.
     */
    @Test
    void 조각_출력에는_실제로_쓰는_프레임_수로_낸_바이트_상한이_걸린다() {
        render(dir, d -> Long.MAX_VALUE);

        List<List<String>> cuts = cutCommands();
        assertThat(cuts).hasSize(2);
        for (int j = 0; j < cuts.size(); j++) {
            long frames = countFrames(dir.resolve(RenderCommands.midFile(j)));
            assertThat(frames).isPositive();
            assertThat(capOf(cuts.get(j))).isEqualTo(frames * WIDTH * HEIGHT * 3 * 5 / 8 + CONTAINER);
        }
    }

    @Test
    void 남은_디스크가_상한과_여유분보다_1바이트_모자라면_자르지_않고_일시_실패한다() {
        long cap = firstCap();
        Path second = newDir();

        assertThatThrownBy(() -> render(second, d -> cap + RESERVE - 1))
                .isInstanceOfSatisfying(RenderFailure.class, f -> {
                    assertThat(f.retryable()).isTrue();
                    assertThat(f.code()).isEqualTo(ErrorCode.INTERNAL);
                    assertThat(f.getMessage()).doesNotContainPattern("[0-9]");
                });

        assertThat(cutCommands()).isEmpty();
        assertThat(asked).containsExactly(second);
        assertThat(warnings()).singleElement().asString().startsWith("render.disk_short ")
                .contains("seq=10 needBytes=" + (cap + RESERVE) + " usableBytes=" + (cap + RESERVE - 1));
    }

    @Test
    void 남은_디스크가_상한과_여유분만큼_있으면_자른다() {
        long cap = firstCap();
        Path second = newDir();

        render(second, d -> cap + RESERVE);

        assertThat(cutCommands()).hasSize(2);
        assertThat(asked).containsExactly(second, second);
        assertThat(warnings()).noneMatch(m -> m.startsWith("render.disk_short"));
    }

    /** ffmpeg 는 {@code -fs} 에 닿으면 파일을 잘라 쓰고 0 으로 끝난다 — 그래서 크기를 보고 갈라야 한다(이 가드의 전제). */
    @Test
    void ffmpeg_는_바이트_상한에_닿으면_정상_종료하고_상한_이상_크기를_남긴다() {
        Path src = dir.resolve("src.m4s");
        copy(ra.pieces().getFirst(), src);
        VideoPiece piece = new VideoPiece("src.m4s", 0, 360_000, 0);

        ProcessRunner real = new ProcessRunner();
        real.run(RenderCommands.cutPiece("ffmpeg", piece, 0, DirectSegments.TIMESCALE, 50_000), dir, deadline());
        real.run(RenderCommands.cutPiece("ffmpeg", piece, 1, DirectSegments.TIMESCALE, Long.MAX_VALUE), dir,
                deadline());

        long capped = size(dir.resolve(RenderCommands.midFile(0)));
        assertThat(capped).isGreaterThanOrEqualTo(50_000).isLessThan(size(dir.resolve(RenderCommands.midFile(1))));
    }

    /** 상한에 닿은 중간 파일 — 둘째 조각 출력을 상한 크기까지 늘려 ffmpeg 가 잘라 쓴 꼴을 만든다. */
    @Test
    void 중간_파일이_상한에_닿으면_원본_불일치로_종결하고_최종_단계를_돌리지_않는다() {
        assertCappedAt(0);
    }

    /** ffmpeg 는 상한을 조금 넘겨 쓰고 멈춘다(전제 시험) — 상한보다 큰 파일도 같은 갈래다. */
    @Test
    void 중간_파일이_상한보다_크면_원본_불일치로_종결하고_최종_단계를_돌리지_않는다() {
        assertCappedAt(1);
    }

    /**
     * 고엔트로피 720p yuv444p 4프레임(cx 재현 · ffmpeg 9.0.2) — 8비트 4:2:0 을 가정한 옛 상한(× 2)이면 3프레임만 남아 원본
     * 불일치로 끝났다. 판독한 화소 꼴로 낸 상한이면 4프레임을 다 쓴다.
     */
    @Test
    void 고엔트로피_yuv444p_720p_조각은_상한에_닿지_않고_다_잘린다() {
        long bytes = cutWhole("noise444.mp4",
                "nullsrc=s=1280x720:r=30,format=yuv444p,geq=lum='random(1)*255':cb='random(2)*255':cr='random(3)*255'",
                "yuv444p", 4, 1_280, 720);

        assertThat(bytes).as("옛 상한을 넘는 자료").isGreaterThan(4L * 1_280 * 720 * 2);
    }

    /** 16x16 한 장 — NUT 머리 몇백 바이트가 날 화소 바이트를 넘는다. 담는 꼴 몫 1MiB 가 받아 준다. */
    @Test
    void 작은_16x16_조각도_상한에_닿지_않는다() {
        cutWhole("tiny.mp4", "testsrc2=size=16x16:rate=30", "yuv420p", 1, 16, 16);
    }

    private void assertCappedAt(long over) {
        runner.intercept((command, workDir, deadline, r) -> {
            if (!RecordingRunner.isCutPiece(command) || !command.getLast().equals(RenderCommands.midFile(1))) {
                return null;
            }
            ProcessRunner.Result result = r.proceed(command, workDir, deadline);
            stretch(workDir.resolve(RenderCommands.midFile(1)), capOf(command) + over);
            return result;
        });

        assertThatThrownBy(() -> render(dir, d -> Long.MAX_VALUE))
                .isInstanceOfSatisfying(RenderFailure.class, f -> {
                    assertThat(f.retryable()).isFalse();
                    assertThat(f.code()).isEqualTo(ErrorCode.SOURCE_MISMATCH);
                    assertThat(f.getMessage()).doesNotContainPattern("[0-9]");
                });

        assertThat(commands()).noneMatch(RecordingRunner::isFinalStage);
        long cap = capOf(cutCommands().get(1));
        assertThat(warnings()).singleElement().asString().startsWith("render.piece_capped ")
                .contains("seq=11 capBytes=" + cap + " bytes=" + (cap + over));
    }

    @Test
    void 상한_정수식이_넘치면_원본_불일치로_종결한다() {
        assertThatThrownBy(() -> JoinedRenderer.pieceCapBytes(Long.MAX_VALUE / 4, 1_920, 1_080, "yuv420p"))
                .isInstanceOfSatisfying(RenderFailure.class, f -> {
                    assertThat(f.retryable()).isFalse();
                    assertThat(f.code()).isEqualTo(ErrorCode.SOURCE_MISMATCH);
                    assertThat(f.getMessage()).doesNotContainPattern("[0-9]");
                });
        // 30 × 14,400 × 1.5 × 5/4 + 1MiB
        assertThat(JoinedRenderer.pieceCapBytes(30, WIDTH, HEIGHT, "yuv420p")).isEqualTo(810_000 + CONTAINER);
    }

    /** 화소 꼴마다 날 바이트가 다르다 — 1080p30 4초(120프레임 · 화소 2,073,600). 값은 손으로 셈했다. */
    @Test
    void 상한은_판독한_화소_꼴의_날_바이트로_낸다() {
        // 248,832,000 화소 × 1.5 × 5/4 = 466,560,000
        assertThat(JoinedRenderer.pieceCapBytes(120, 1_920, 1_080, "yuv420p")).isEqualTo(466_560_000 + CONTAINER);
        // × 3 × 5/4 = 933,120,000
        assertThat(JoinedRenderer.pieceCapBytes(120, 1_920, 1_080, "yuv444p")).isEqualTo(933_120_000 + CONTAINER);
        // × 6 × 5/4 = 1,866,240,000 — 10비트 4:4:4 · 모르는 꼴 · 판독 없음이 같은 값
        assertThat(JoinedRenderer.pieceCapBytes(120, 1_920, 1_080, "yuv444p10le"))
                .isEqualTo(1_866_240_000L + CONTAINER);
        assertThat(JoinedRenderer.pieceCapBytes(120, 1_920, 1_080, null)).isEqualTo(1_866_240_000L + CONTAINER);
    }

    /** 반바이트 × 5/8 이 나누어떨어지지 않으면 올린다(상한을 낮추는 쪽으로 버리지 않는다). */
    @Test
    void 상한의_나머지는_올린다() {
        // 1 × 1 × 1.5 × 5/4 = 1.875 → 2
        assertThat(JoinedRenderer.pieceCapBytes(1, 1, 1, "yuv420p")).isEqualTo(2 + CONTAINER);
    }

    @Test
    void 상한에_여유분을_더한_정수식이_넘쳐도_원본_불일치로_종결한다() {
        long cap = Long.MAX_VALUE - RESERVE + 1;

        assertThatThrownBy(() -> JoinedRenderer.diskNeedBytes(cap))
                .isInstanceOfSatisfying(RenderFailure.class,
                        f -> assertThat(f.code()).isEqualTo(ErrorCode.SOURCE_MISMATCH));
    }

    // ---------------------------------------------------------------- 자료

    /** 남은 디스크를 넉넉히 주고 한 번 돌려 첫 조각 상한을 읽는다(경계 시험의 기준). */
    private long firstCap() {
        render(newDir(), d -> Long.MAX_VALUE);
        long cap = capOf(cutCommands().getFirst());
        baseline = runner.commands().size();
        asked.clear();
        logs.list.clear();
        return cap;
    }

    private void render(Path workDir, JoinedRenderer.UsableSpace space) {
        ObjectNode envelope = PlanOrder.of(UUID.randomUUID()).segment(10, false, false).segment(11, false, true)
                .in(10, 3_000_000).out(11, 500_000).json();
        ((ObjectNode) envelope.get("recipe")).putNull("subtitles");
        JobEnvelope job = new EnvelopeParser(Fixtures.MAPPER).parse(Fixtures.MAPPER.writeValueAsString(envelope));
        JoinedRenderer.UsableSpace recorded = d -> {
            asked.add(d);
            return space.bytes(d);
        };
        new JoinedRenderer(store, new MediaProbe(runner, Fixtures.MAPPER, "ffprobe"), runner, Fixtures.MAPPER,
                "ffmpeg", null, recorded).render(job, workDir, deadline(), (p, s) -> { });
    }

    private List<List<String>> commands() {
        List<List<String>> all = runner.commands();
        return all.subList(baseline, all.size());
    }

    private List<List<String>> cutCommands() {
        return commands().stream().filter(RecordingRunner::isCutPiece).toList();
    }

    private static long capOf(List<String> command) {
        int at = command.indexOf("-fs");
        assertThat(at).as("-fs 옵션").isNotNegative();
        return Long.parseLong(command.get(at + 1));
    }

    private List<String> warnings() {
        return logs.list.stream().filter(e -> e.getLevel() == Level.WARN).map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    /**
     * lavfi 원천을 그 화소 꼴로 무손실 libx264 조각(90kHz · 3000틱 간격)에 쓰고, 실제 판독으로 낸 상한을 걸어 통째로 자른다.
     * 상한에 닿지 않고 모든 프레임이 남았는지 보고, 중간 파일 크기를 돌려준다.
     */
    private long cutWhole(String name, String lavfi, String pixFmt, int frames, int width, int height) {
        ProcessRunner real = new ProcessRunner();
        real.run(List.of("ffmpeg", "-hide_banner", "-nostdin", "-y", "-loglevel", "error", "-f", "lavfi", "-i", lavfi,
                "-frames:v", Integer.toString(frames), "-vf", "settb=1/90000,setpts=N*3000", "-fps_mode", "passthrough",
                "-enc_time_base:v", "1/90000", "-c:v", "libx264", "-qp", "0", "-preset", "ultrafast", "-bf", "0",
                "-pix_fmt", pixFmt, "-video_track_timescale", "90000", "-f", "mp4", name), dir, deadline());
        SegmentTiming timing = new MediaProbe(real, Fixtures.MAPPER, "ffprobe").timing(dir.resolve(name), dir,
                deadline());
        assertThat(timing.pixelFormat()).isEqualTo(pixFmt);
        assertThat(timing.frameCount()).isEqualTo(frames);
        assertThat(new long[] {timing.width(), timing.height()}).containsExactly(width, height);

        long cap = JoinedRenderer.pieceCapBytes(timing.frameCount(), timing.width(), timing.height(),
                timing.pixelFormat());
        VideoPiece piece = new VideoPiece(name, 0, frames * DirectSegments.FRAME_TICKS, 0);
        real.run(RenderCommands.cutPiece("ffmpeg", piece, 0, DirectSegments.TIMESCALE, cap), dir, deadline());

        Path mid = dir.resolve(RenderCommands.midFile(0));
        long bytes = size(mid);
        assertThat(bytes).as("상한 %d 아래", cap).isLessThan(cap);
        assertThat(countFrames(mid)).isEqualTo(frames);
        return bytes;
    }

    private static long countFrames(Path file) {
        String out = Ffmpeg.probe("-v", "error", "-count_frames", "-select_streams", "v:0", "-show_entries",
                "stream=nb_read_frames", "-of", "csv=p=0", file.toString());
        assertThat(out).as("ffprobe 프레임 수").isNotNull();
        return Long.parseLong(out.strip());
    }

    private Path newDir() {
        try {
            return Files.createTempDirectory(dir, "run");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void stretch(Path file, long length) {
        try (RandomAccessFile f = new RandomAccessFile(file.toFile(), "rw")) {
            f.setLength(length);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void copy(Path from, Path to) {
        try {
            Files.copy(from, to);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static long size(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Instant deadline() {
        return Instant.now().plus(Duration.ofMinutes(3));
    }
}
