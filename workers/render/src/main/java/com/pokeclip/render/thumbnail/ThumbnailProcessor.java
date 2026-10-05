package com.pokeclip.render.thumbnail;

import com.pokeclip.render.job.ErrorCode;
import com.pokeclip.render.job.RenderFailure;
import com.pokeclip.render.media.ProcessRunner;
import com.pokeclip.render.storage.S3Store;
import com.pokeclip.render.work.Disposition;
import com.pokeclip.render.work.MessageHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 사진 주문 하나(POK-277): 원본(영상 조각 또는 완성 영상)을 받아 {@code offsetMs} 자리의 한 장면을 jpg로 뽑아 올리고 clip에 알린다.
 *
 * <p>메시지 처분은 셋으로 갈린다.
 * <ul>
 *   <li><b>지운다</b>: 끝났다 · 주문서 모양이 틀리다 · 원본이 창고에 없다(지워진 조각) · clip이 보고를 거절했다. 다시 해도 같다.</li>
 *   <li><b>둔다</b>: 창고·ffmpeg·clip이 잠깐 안 됐다. 숨김 시간 뒤 다시 오고, 세 번이면 DLQ로 간다(clip은 DLQ를 안 읽는다. 다음 순회가 다시 찍는다).</li>
 *   <li>🔴 <b>라이브는 잠깐 안 돼도 지운다.</b> 라이브는 같은 키에 1분마다 덮어쓴다. 실패한 주문을 숨김 시간(2분) 뒤 다시 하면 그 사이
 *       뒤 주문이 올린 새 장면을 옛 장면으로 덮는다(PR #212 codex). 다음 분의 주문이 새로 찍으므로 다시 할 이유가 없다.</li>
 * </ul>
 * 렌더 주문처럼 시작·진행 보고가 없다: 몇 초짜리 일이고, 같은 키에 덮어써도 사진은 하나라 실행을 가를 필요가 없다.
 */
public class ThumbnailProcessor implements MessageHandler {

    private static final Logger log = LoggerFactory.getLogger(ThumbnailProcessor.class);

    /** 사진 가로 상한. 화면 카드가 이보다 작다. 세로 영상은 가로 기준으로 줄어 세로가 더 길다. */
    static final int MAX_WIDTH = 640;

    private final ObjectMapper mapper;
    private final S3Store store;
    private final ProcessRunner runner;
    private final ThumbnailReporter reporter;
    private final String ffmpeg;
    private final Path workRoot;
    private final Duration timeout;
    private final Clock clock;

    public ThumbnailProcessor(ObjectMapper mapper, S3Store store, ProcessRunner runner, ThumbnailReporter reporter,
                       String ffmpeg, Path workRoot, Duration timeout, Clock clock) {
        this.mapper = mapper;
        this.store = store;
        this.runner = runner;
        this.reporter = reporter;
        this.ffmpeg = ffmpeg;
        this.workRoot = workRoot;
        this.timeout = timeout;
        this.clock = clock;
    }

    @Override
    public Disposition process(String body) {
        Optional<ThumbnailJob> parsed = ThumbnailJob.parse(mapper, body);
        if (parsed.isEmpty()) {
            log.warn("thumbnail.invalid_job");
            return Disposition.DELETE;
        }
        ThumbnailJob job = parsed.get();
        Instant deadline = clock.instant().plus(timeout);
        Path dir = null;
        try {
            Files.createDirectories(workRoot);
            dir = Files.createTempDirectory(workRoot, "thumb-");
            Path source = dir.resolve("source");
            store.download(job.sourceBucket(), job.sourceKey(), source, deadline);
            Path picture = dir.resolve("picture.jpg");
            capture(source, job.offsetMs(), picture, dir, deadline);
            store.upload(job.outputBucket(), job.outputKey(), picture, "image/jpeg", deadline);
        } catch (RenderFailure e) {
            log.warn("thumbnail.failed kind={} targetId={} retryable={} code={}", job.kind(), job.targetId(),
                    e.retryable(), e.code());
            return e.retryable() ? retryOrDrop(job) : Disposition.DELETE;
        } catch (ProcessRunner.Timeout | ProcessRunner.Failed | IOException | UncheckedIOException e) {
            log.warn("thumbnail.failed kind={} targetId={} reason={}", job.kind(), job.targetId(), e.getClass().getSimpleName());
            return retryOrDrop(job);
        } finally {
            deleteQuietly(dir);
        }
        // 상한 안에서 보고까지 끝낸다. 넘겼으면 보고를 시작하지 않는다(보고의 읽기 시한 10초가 상한 밖에 붙지 않게, PR #212 codex)
        if (!clock.instant().isBefore(deadline)) {
            log.warn("thumbnail.deadline_before_report kind={} targetId={}", job.kind(), job.targetId());
            return retryOrDrop(job);
        }
        return switch (reporter.captured(job.kind(), job.targetId(), job.capturedAt())) {
            case ACCEPTED -> {
                log.info("thumbnail.captured kind={} targetId={}", job.kind(), job.targetId());
                yield Disposition.DELETE;
            }
            case REJECTED -> {
                log.warn("thumbnail.report_rejected kind={} targetId={}", job.kind(), job.targetId());
                yield Disposition.DELETE;
            }
            case UNAVAILABLE -> {
                log.warn("thumbnail.report_unavailable kind={} targetId={}", job.kind(), job.targetId());
                yield retryOrDrop(job);
            }
        };
    }

    /** 잠깐 안 된 실패의 처분. 라이브는 다시 하지 않는다(클래스 주석). */
    private static Disposition retryOrDrop(ThumbnailJob job) {
        return "live".equals(job.kind()) ? Disposition.DELETE : Disposition.LEAVE;
    }

    /**
     * 한 장면을 뽑는다. 자리가 원본 끝을 넘으면(조각 길이가 장부와 조금 다르다) ffmpeg가 파일 없이 「Conversion failed」로 끝난다
     * (9.0 실측, 종료 코드 0이 아니다. 판에 따라 성공하고 파일만 없을 수도 있다). 어느 쪽이든 첫 장면으로 한 번 더 뽑는다.
     */
    private void capture(Path source, long offsetMs, Path picture, Path dir, Instant deadline) {
        try {
            runner.run(command(source, offsetMs, picture), dir, deadline);
        } catch (ProcessRunner.Failed e) {
            if (offsetMs == 0) {
                throw e;
            }
            // 실패하면서 빈·덜 쓴 파일을 남겼을 수 있다. 남기면 아래 검사가 그것을 사진으로 보고 올린다(PR #212 codex)
            deleteFile(picture);
        }
        if (!Files.exists(picture) && offsetMs > 0) {
            runner.run(command(source, 0, picture), dir, deadline);
        }
        if (!Files.exists(picture)) {
            throw RenderFailure.permanent(ErrorCode.SOURCE_MISMATCH, "원본에서 장면을 못 뽑았다");
        }
    }

    List<String> command(Path source, long offsetMs, Path picture) {
        return List.of(ffmpeg, "-hide_banner", "-nostdin", "-y",
                "-ss", String.format(Locale.ROOT, "%.3f", offsetMs / 1000.0),
                "-i", source.toString(),
                "-frames:v", "1",
                "-vf", "scale='min(" + MAX_WIDTH + ",iw)':-2",
                "-q:v", "4",
                picture.toString());
    }

    private static void deleteFile(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        } catch (IOException ignored) {
            // 임시 폴더다. 못 지워도 다음 주문에 영향이 없다
        }
    }
}
