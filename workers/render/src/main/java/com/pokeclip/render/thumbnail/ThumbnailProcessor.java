package com.pokeclip.render.thumbnail;

import com.pokeclip.render.job.ErrorCode;
import com.pokeclip.render.job.RenderFailure;
import com.pokeclip.render.media.MediaProbe;
import com.pokeclip.render.media.ProcessRunner;
import com.pokeclip.render.media.SegmentTiming;
import com.pokeclip.render.storage.S3Store;
import com.pokeclip.render.work.Disposition;
import com.pokeclip.render.work.MessageHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
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
 *
 * <p>찍을 자리는 받은 파일을 판독해 정한다({@link Seek} · PDT 설계 r7 8.1). 한 장 뽑기라 프레임 정확 계약 밖의 거친 좌표다 — 입력
 * {@code -ss} 를 그대로 쓴다(설계 19.4 9).
 */
public class ThumbnailProcessor implements MessageHandler {

    private static final Logger log = LoggerFactory.getLogger(ThumbnailProcessor.class);

    /** 사진 가로 상한. 화면 카드가 이보다 작다. 세로 영상은 가로 기준으로 줄어 세로가 더 길다. */
    static final int MAX_WIDTH = 640;

    private final ObjectMapper mapper;
    private final S3Store store;
    private final ProcessRunner runner;
    private final MediaProbe probe;
    private final ThumbnailReporter reporter;
    private final String ffmpeg;
    private final Path workRoot;
    private final Duration timeout;
    private final Clock clock;

    public ThumbnailProcessor(ObjectMapper mapper, S3Store store, ProcessRunner runner, MediaProbe probe,
                       ThumbnailReporter reporter, String ffmpeg, Path workRoot, Duration timeout, Clock clock) {
        this.mapper = mapper;
        this.store = store;
        this.runner = runner;
        this.probe = probe;
        this.reporter = reporter;
        this.ffmpeg = ffmpeg;
        this.workRoot = workRoot;
        this.timeout = timeout;
        this.clock = clock;
    }

    @Override
    public Disposition process(String body) {
        return process(body, 1);
    }

    @Override
    public Disposition process(String body, int receiveCount) {
        Optional<ThumbnailJob> parsed = ThumbnailJob.parse(mapper, body);
        if (parsed.isEmpty()) {
            log.warn("thumbnail.invalid_job");
            return Disposition.DELETE;
        }
        ThumbnailJob job = parsed.get();
        // 다시 받은 라이브 주문은 하지 않는다. 앞 수신이 올린 뒤 죽었거나 지우기가 실패해 SQS가 다시 내준 것이라, 그 사이 뒤 주문이
        // 올린 새 장면을 옛 장면으로 덮는다(PR #212 codex 2판). 다음 분 주문이 새로 찍는다
        if ("live".equals(job.kind()) && receiveCount > 1) {
            log.info("thumbnail.live_redelivered_dropped targetId={} receiveCount={}", job.targetId(), receiveCount);
            return Disposition.DELETE;
        }
        Instant deadline = clock.instant().plus(timeout);
        Path dir = null;
        try {
            Files.createDirectories(workRoot);
            dir = Files.createTempDirectory(workRoot, "thumb-");
            Path source = dir.resolve("source");
            store.download(job.sourceBucket(), job.sourceKey(), source, deadline);
            Seek seek = Seek.of(job, probe.timing(source, dir, deadline));
            log.debug("thumbnail.seek kind={} origin={} seekMs={} clamped={}", job.kind(),
                    "clip".equals(job.kind()) ? "none" : job.offsetOrigin(), seek.ms(), seek.clamped());
            Path picture = dir.resolve("picture.jpg");
            capture(source, seek.ms(), picture, dir, deadline);
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
        // 상한 안에서 보고까지 끝낸다. 보고는 최대 연결 3초 + 읽기 10초라, 그만큼 안 남았으면 시작하지 않는다(PR #212 codex 1·2판)
        if (clock.instant().plus(ThumbnailReporter.MAX_DURATION).isAfter(deadline)) {
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
            case GONE -> {
                // 그 사이 탈퇴로 대상이 지워졌다(POK-256). clip의 지우기는 이미 끝나 이 사진을 모른다.
                boolean deleted = store.deleteQuietly(job.outputBucket(), List.of(job.outputKey()));
                log.warn("thumbnail.target_gone kind={} targetId={} deleted={}", job.kind(), job.targetId(), deleted);
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
     * (9.0 실측, 종료 코드 0이 아니다. 판에 따라 성공하고 파일만 없을 수도 있다). 끝은 {@link Seek} 가 마지막 표시 프레임에서
     * 미리 자르고, 그래도 그림이 안 나오면 첫 장면으로 한 번 더 뽑는다.
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

    /**
     * 찍을 자리 — ffmpeg 입력 {@code -ss}(ms) 값(PDT 설계 r7 8.1). {@code -ss} 는 입력 시각 0 = 파일 {@code format.start_time}
     * ({@code st})에서 잰다.
     * <ul>
     *   <li>카드 · 라이브: {@code (원점 + offsetMs) − st}. 원점은 {@code VIDEO_START} 면 영상 첫 DTS({@code D0}),
     *       {@code FILE_HEAD} 면 파일 머리({@code Hd} — 모든 스트림 첫 DTS 의 최솟값). 녹화 조각은 소리가 머리라 평시에
     *       {@code Hd = st} 이고, {@code FILE_HEAD} 는 {@code offsetMs} 그대로다(원점 칸 없는 지금 주문과 같은 자리).</li>
     *   <li>완성 영상({@code clip}): {@code offsetMs} 그대로 — 결과 파일 안 자리다.</li>
     * </ul>
     * 셋 모두 {@code [0, P_last]} 로 자른다. {@code P_last} = 영상 PTS 최댓값 − {@code st} 를 ms 로 <b>내린</b> 값 — 표시 순 마지막
     * 프레임의 시작이라 {@code -ss} 가 그림을 낼 수 있는 마지막 자리다(올리면 마지막 프레임보다 뒤라 그림이 없다). 자리는 ms 로
     * 반올림한다(µs 반올림 차 1µs 가 ms 를 바꾸지 않게).
     *
     * @param clamped {@code [0, P_last]} 로 잘렸는가(신호 {@code thumbnail.seek})
     */
    record Seek(long ms, boolean clamped) {

        private static final BigInteger MICROS = BigInteger.valueOf(1_000_000);
        private static final BigInteger THOUSAND = BigInteger.valueOf(1_000);

        static Seek of(ThumbnailJob job, SegmentTiming timing) {
            // 모든 값을 「µs × ts」 단위 정수로 센다 — 영상 틱(1/ts 초)과 µs 를 실수 없이 함께 쓰고, 큰 offsetMs 에도 넘치지 않는다
            BigInteger ts = BigInteger.valueOf(timing.videoTimescale());
            BigInteger start = BigInteger.valueOf(timing.formatStartUs()).multiply(ts);
            BigInteger origin = "clip".equals(job.kind()) ? start : switch (job.offsetOrigin()) {
                case VIDEO_START -> BigInteger.valueOf(timing.videoFirstDts()).multiply(MICROS);
                case FILE_HEAD -> BigInteger.valueOf(timing.fileHeadUs()).multiply(ts);
            };
            BigInteger seek = origin.subtract(start).add(BigInteger.valueOf(job.offsetMs()).multiply(THOUSAND).multiply(ts));
            BigInteger msScale = ts.multiply(THOUSAND);
            BigInteger wanted = floorDiv(seek.add(msScale.shiftRight(1)), msScale);
            BigInteger last = floorDiv(BigInteger.valueOf(timing.lastPts()).multiply(MICROS).subtract(start), msScale)
                    .max(BigInteger.ZERO);
            BigInteger ms = wanted.max(BigInteger.ZERO).min(last);
            return new Seek(ms.longValueExact(), !ms.equals(wanted));
        }

        private static BigInteger floorDiv(BigInteger num, BigInteger den) {
            BigInteger[] qr = num.divideAndRemainder(den);
            return qr[1].signum() < 0 ? qr[0].subtract(BigInteger.ONE) : qr[0];
        }
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
