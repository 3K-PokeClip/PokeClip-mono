package com.pokeclip.render.work;

import com.pokeclip.render.job.EnvelopeParser;
import com.pokeclip.render.job.ErrorCode;
import com.pokeclip.render.job.JobEnvelope;
import com.pokeclip.render.job.RenderFailure;
import com.pokeclip.render.media.ProcessRunner;
import com.pokeclip.render.report.ClipReporter;
import com.pokeclip.render.report.ClipReporter.ResultItem;
import com.pokeclip.render.report.Reply;
import com.pokeclip.render.report.ReportUnavailable;
import com.pokeclip.render.storage.S3Store;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 주문서 한 통을 처음부터 끝까지 다룬다. 계약1 4절의 일꾼 쪽 상태머신이 전부 여기 있다.
 *
 * <pre>
 * 읽기 ─ 실패 → 무토큰 TERMINAL_FAILED(preflight)
 *   └ STARTED ─ proceed:false → 지운다
 *        └ 렌더 → 올리기 → SUCCEEDED
 *             ├ 실패(다시 해도 같다 · 마지막 시도) → TERMINAL_FAILED
 *             └ 실패(다시 해 볼 만하다)            → RETRY_SCHEDULED + 숨김 60~120초
 * </pre>
 *
 * <p>어느 보고든 409면 reason으로 가른다: SUPERSEDED는 손대지 않고(유효한 새 실행이 돈다), TERMINAL·CANCELLED는 지운다.
 * clip이 끝내 답을 안 하면({@link ReportUnavailable}) 메시지를 그대로 둔다. 다시 받으면 clip의 판정이 이어진다.
 */
public class JobProcessor {

    private static final Logger log = LoggerFactory.getLogger(JobProcessor.class);

    /** 계약1 4절: PROGRESS는 5초 이상 간격. */
    static final Duration PROGRESS_INTERVAL = Duration.ofSeconds(5);

    private final EnvelopeParser parser;
    private final ClipReporter reporter;
    private final ClipRenderer renderer;
    private final S3Store store;
    private final Path workRoot;
    private final Duration jobTimeout;
    private final Clock clock;

    public JobProcessor(EnvelopeParser parser, ClipReporter reporter, ClipRenderer renderer, S3Store store,
                        Path workRoot, Duration jobTimeout, Clock clock) {
        this.parser = parser;
        this.reporter = reporter;
        this.renderer = renderer;
        this.store = store;
        this.workRoot = workRoot;
        this.jobTimeout = jobTimeout;
        this.clock = clock;
    }

    public Disposition process(String body) {
        JobEnvelope job;
        try {
            job = parser.parse(body);
        } catch (EnvelopeParser.Unreadable e) {
            // 보고할 jobId가 없다. 줄에 두면 세 번 돌고 실패 큐로 가지만 거기서도 아무도 못 닫는다. 지우고 크게 남긴다.
            log.error("render.envelope_unreadable reason={}", e.getMessage());
            return Disposition.DELETE;
        } catch (EnvelopeParser.Rejected e) {
            log.warn("render.preflight_rejected jobId={} code={} reason={}", e.jobId(), e.failure().code(),
                    e.getMessage());
            return afterTerminal(e.jobId(), () -> reporter.terminalFailed(e.jobId(), null, e.failure().code(),
                    e.getMessage(), false));
        }
        try {
            return run(job);
        } catch (ReportUnavailable e) {
            log.warn("render.report_unavailable jobId={} err={}", job.jobId(), e.getMessage());
            return Disposition.LEAVE;
        }
    }

    private Disposition run(JobEnvelope job) {
        Reply started = reporter.started(job.jobId());
        if (started.status() == 404) {
            log.warn("render.unknown_job jobId={}", job.jobId());
            return Disposition.DELETE;
        }
        if (!started.ok()) {
            log.error("render.started_rejected jobId={} status={} reason={}", job.jobId(), started.status(),
                    started.reason());
            return Disposition.LEAVE;
        }
        if (!started.body().path("proceed").asBoolean(false)) {
            log.info("render.not_proceeding jobId={}", job.jobId());
            return Disposition.DELETE;
        }
        String token = started.body().path("executionToken").asString();
        boolean finalAttempt = started.body().path("isFinalAttempt").asBoolean(false);
        int attempt = started.body().path("attemptOrdinal").asInt(0);
        log.info("render.started jobId={} clipId={} attempt={} final={} outputs={} segments={}", job.jobId(),
                job.clipId(), attempt, finalAttempt, job.recipe().outputs().size(), job.sources().size());

        Instant began = clock.instant();
        ProgressGate gate = new ProgressGate(job.jobId(), token);
        try (Workspace workspace = new Workspace(workRoot, job.jobId())) {
            Instant deadline = began.plus(jobTimeout);
            List<ClipRenderer.Produced> produced = renderer.render(job, workspace.dir(), deadline, gate::report);
            List<ResultItem> result = new ArrayList<>();
            for (ClipRenderer.Produced p : produced) {
                String name = p.file().getFileName().toString();
                String key = job.outputKey(token, name);
                store.upload(job.outputBucket(), key, p.file(),
                        "video".equals(p.kind()) ? "video/mp4" : "application/x-subrip", deadline);
                result.add(new ResultItem(p.outputId(), p.kind(), key));
            }
            gate.report(95, "upload");
            Reply reply = reporter.succeeded(job.jobId(), token, result);
            log.info("render.succeeded jobId={} clipId={} files={} elapsedMs={} status={}", job.jobId(), job.clipId(),
                    result.size(), Duration.between(began, clock.instant()).toMillis(), reply.status());
            if (reply.status() == 400 && "INVALID_RESULT".equals(reply.reason())) {
                return afterTerminal(job.jobId(), () -> reporter.terminalFailed(job.jobId(), token,
                        ErrorCode.RESULT_VALIDATION, "완성 파일 목록이 주문과 맞지 않는다", true));
            }
            return afterReply(job.jobId(), reply);
        } catch (Aborted e) {
            return e.disposition;
        } catch (RenderFailure f) {
            return failed(job, token, finalAttempt, f);
        } catch (ProcessRunner.Timeout e) {
            return failed(job, token, finalAttempt, RenderFailure.transientFailure("영상 만들기가 시간 안에 안 끝났다", e));
        } catch (ProcessRunner.Failed e) {
            log.warn("render.ffmpeg_failed jobId={} detail={}", job.jobId(), e.detail());
            return failed(job, token, finalAttempt, RenderFailure.transientFailure("영상 만들기에 실패했다", e));
        } catch (ReportUnavailable e) {
            throw e;
        } catch (RuntimeException e) {
            log.error("render.unexpected jobId={}", job.jobId(), e);
            return failed(job, token, finalAttempt, RenderFailure.transientFailure("영상 만들기 중 알 수 없는 오류", e));
        }
    }

    /**
     * 다시 해도 같은 실패이거나 마지막 시도면 종결, 아니면 「다시 해 볼게요」. 마지막 시도의 실패는 RETRY_SCHEDULED를
     * 건너뛰고 TERMINAL_FAILED로 바로 간다(계약1: 원인 코드를 보존한다).
     */
    private Disposition failed(JobEnvelope job, String token, boolean finalAttempt, RenderFailure f) {
        log.warn("render.failed jobId={} code={} retryable={} final={} reason={}", job.jobId(), f.code(),
                f.retryable(), finalAttempt, f.getMessage(), f.getCause());
        if (!f.retryable() || finalAttempt) {
            return afterTerminal(job.jobId(), () -> reporter.terminalFailed(job.jobId(), token, f.code(),
                    f.getMessage(), f.retryable()));
        }
        Reply reply = reporter.retryScheduled(job.jobId(), token, f.code(), f.getMessage());
        if (reply.ok()) {
            // equal jitter: 절반은 고정, 절반은 무작위. 여러 일꾼이 같은 순간에 다시 몰리지 않고, 하한은 보장된다.
            return Disposition.delay(Duration.ofSeconds(60 + ThreadLocalRandom.current().nextInt(61)));
        }
        return afterReply(job.jobId(), reply);
    }

    private Disposition afterTerminal(UUID jobId, java.util.function.Supplier<Reply> send) {
        return afterReply(jobId, send.get());
    }

    /** 종결 보고의 답을 메시지 처리로 바꾼다. */
    private Disposition afterReply(UUID jobId, Reply reply) {
        if (reply.ok() || reply.status() == 404) {
            return Disposition.DELETE;
        }
        if (reply.status() == 409) {
            return "SUPERSEDED".equals(reply.reason()) ? Disposition.LEAVE : Disposition.DELETE;
        }
        // 400은 확정 응답이라 다시 보내지 않는다(계약1 4절). 메시지를 두면 세 번 뒤 실패 큐 → 정리기가 닫는다.
        log.error("render.report_rejected jobId={} status={} reason={}", jobId, reply.status(), reply.reason());
        return Disposition.LEAVE;
    }

    /** 진행 보고를 5초에 한 번으로 거르고, 보고가 409면 렌더를 멈춘다(내 실행이 무효가 됐다). */
    private final class ProgressGate {
        private final UUID jobId;
        private final String token;
        private Instant last = Instant.MIN;

        ProgressGate(UUID jobId, String token) {
            this.jobId = jobId;
            this.token = token;
        }

        void report(int percent, String stage) {
            Instant now = clock.instant();
            if (Duration.between(last, now).compareTo(PROGRESS_INTERVAL) < 0) {
                return;
            }
            last = now;
            Reply reply;
            try {
                reply = reporter.progress(jobId, token, percent, stage);
            } catch (ReportUnavailable e) {
                // 진행 보고는 선택이다(계약1 4절). 못 보내도 일은 계속한다.
                return;
            }
            if (reply.status() == 409) {
                log.info("render.aborted jobId={} reason={}", jobId, reply.reason());
                throw new Aborted("SUPERSEDED".equals(reply.reason()) ? Disposition.LEAVE : Disposition.DELETE);
            }
        }
    }

    private static final class Aborted extends RuntimeException {
        private final Disposition disposition;

        Aborted(Disposition disposition) {
            super(null, null, false, false);
            this.disposition = disposition;
        }
    }
}
