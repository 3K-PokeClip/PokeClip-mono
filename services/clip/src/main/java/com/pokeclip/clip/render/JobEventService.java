package com.pokeclip.clip.render;

import com.pokeclip.clip.render.RenderErrors.JobNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 일꾼의 보고를 상태로 옮기는 <b>유일한 자리</b>(계약1 4절 상태머신). 처리 우선순위가 계약이다 —
 * ① jobId 없음 → 404 ② {@code (jobId, eventId)} 기수신 → 저장한 응답 그대로 ③ 전이 판정.
 *
 * <p>잡 줄을 {@code FOR UPDATE}로 잠근 채 한 통씩 처리한다. 겹친 STARTED 둘이 같은 순번을 받거나, SUCCEEDED와
 * 늦은 PROGRESS가 서로를 덮는 것을 그 락이 막는다. 응답은 <b>보고 장부에 적힌 뒤</b> 나간다 — 재전송이 같은 답을 받는다.
 *
 * <p>계약1의 {@code error.code} 닫힌 목록은 여기서 검사하지 않는다 — 모르는 코드가 와도 그대로 적는다. 일꾼이 우리 것이라
 * 목록 추가가 배포 순서 문제일 뿐이고, 모르는 코드를 거절하면 실패가 「실패했다는 사실」조차 못 남긴다.
 */
@Service
public class JobEventService {

    private static final Logger log = LoggerFactory.getLogger(JobEventService.class);

    /** 컨트롤러가 그대로 내보내는 응답. {@code body}는 JSON 문자열이다. */
    public record Reply(int status, String body) {
    }

    private final RenderJobRepository jobs;
    private final ClipRepository clips;
    private final RenderJobEventStore events;
    private final RenderProperties properties;
    private final ObjectMapper mapper;

    JobEventService(RenderJobRepository jobs, ClipRepository clips, RenderJobEventStore events,
                    RenderProperties properties, ObjectMapper mapper) {
        this.jobs = jobs;
        this.clips = clips;
        this.events = events;
        this.properties = properties;
        this.mapper = mapper;
    }

    /** @throws JobNotFoundException 모르는 jobId (404) — 이것만 장부에 안 남는다(적을 줄이 없다) */
    @Transactional
    public Reply handle(UUID jobId, JobEventRequest event) {
        RenderJob job = jobs.findByIdForUpdate(jobId).orElseThrow(() -> new JobNotFoundException(jobId.toString()));

        var stored = events.find(jobId, event.eventId());
        if (stored.isPresent()) {
            return replay(job, event, stored.get());
        }

        Instant at = Instant.now();
        Reply reply = switch (event.eventType()) {
            case "STARTED" -> started(job, at);
            case "PROGRESS", "RETRY_SCHEDULED" -> gated(job, event, () -> {
                job.progress(event.progressPercent(), event.progressStage(), at);
                return ok(Map.of());
            });
            case "SUCCEEDED" -> gated(job, event, () -> succeeded(job, event, at));
            case "TERMINAL_FAILED" -> terminalFailed(job, event, at);
            default -> bad("INVALID_EVENT");
        };
        events.save(jobId, event.eventId(), event.eventType(), reply.status(), reply.body());
        log.info("clip.render.event jobId={} type={} status={} jobStatus={}", jobId, event.eventType(), reply.status(),
                job.getStatus());
        return reply;
    }

    /**
     * 기수신은 저장 응답 그대로 — 단 STARTED replay는 토큰이 아직 유효한지 다시 본다(계약1 ②). 그 사이 새 STARTED가
     * 토큰을 갈았거나 잡이 끝났으면 {@code proceed:false}로 바꿔 준다 — 안 그러면 죽었던 일꾼이 옛 답으로 다시 일한다.
     */
    private Reply replay(RenderJob job, JobEventRequest event, RenderJobEventStore.Stored stored) {
        if (!"STARTED".equals(event.eventType()) || stored.status() != 200) {
            return new Reply(stored.status(), stored.body());
        }
        JsonNode body = mapper.readTree(stored.body());
        String token = body.path("executionToken").asString(null);
        boolean stillActive = !job.getStatus().terminal() && token != null
                && job.getExecutionToken() != null && token.equals(job.getExecutionToken().toString());
        return stillActive ? new Reply(200, stored.body()) : ok(Map.of("proceed", false));
    }

    private Reply started(RenderJob job, Instant at) {
        if (job.getStatus().terminal()) {
            return ok(Map.of("proceed", false));
        }
        // 마지막 시도가 이미 나갔다 — 중복 배달된 STARTED가 그 실행을 무효로 하면 상한이 뜻을 잃는다(1판 codex).
        // 새 토큰 없이 「하지 마」. 마지막 일꾼이 죽었다면 큐가 실패 큐로 보내고 정리기가 SWEPT로 닫는다.
        if (job.getAttemptOrdinal() >= properties.maxAttempts()) {
            log.warn("clip.render.attempts_exhausted jobId={} attempts={}", job.getId(), job.getAttemptOrdinal());
            return ok(Map.of("proceed", false));
        }
        UUID token = job.start(at);
        clips.findByIdForUpdate(job.getClipId()).ifPresent(Clip::rendering);
        Map<String, Object> body = new HashMap<>();
        body.put("proceed", true);
        body.put("executionToken", token.toString());
        body.put("attemptOrdinal", job.getAttemptOrdinal());
        body.put("isFinalAttempt", job.getAttemptOrdinal() >= properties.maxAttempts());
        return ok(body);
    }

    /** 토큰이 필요한 보고의 공통 문 — 종결이면 409 TERMINAL, 토큰이 다르면 409 SUPERSEDED, 아직 시작 전이면 400. */
    private Reply gated(RenderJob job, JobEventRequest event, java.util.function.Supplier<Reply> apply) {
        if (job.getStatus().terminal()) {
            return conflict("TERMINAL");
        }
        if (job.getStatus() != RenderJobStatus.STARTED || job.getExecutionToken() == null) {
            return bad("INVALID_EVENT");
        }
        if (event.executionToken() == null || !event.executionToken().equals(job.getExecutionToken())) {
            return conflict("SUPERSEDED");
        }
        return apply.get();
    }

    /** result 검증이 상태 전이보다 먼저다(계약1 rev9) — 실패하면 상태 불변, 400을 저장해 replay. */
    private Reply succeeded(RenderJob job, JobEventRequest event, Instant at) {
        String problem = validateResult(job, event.result());
        if (problem != null) {
            log.warn("clip.render.invalid_result jobId={} reason={}", job.getId(), problem);
            return bad("INVALID_RESULT");
        }
        job.succeeded(at);
        clips.findByIdForUpdate(job.getClipId()).ifPresent(c -> c.rendered(mapper.writeValueAsString(event.result())));
        return ok(Map.of());
    }

    private Reply terminalFailed(RenderJob job, JobEventRequest event, Instant at) {
        if (job.getStatus().terminal()) {
            return conflict("TERMINAL");
        }
        if (event.errorCode() == null) {
            return bad("INVALID_EVENT");
        }
        // preflight(무토큰)는 아직 시작 전(QUEUED)에만 온다. 시작한 뒤라면 토큰이 맞아야 한다.
        if (event.executionToken() == null) {
            if (job.getStatus() != RenderJobStatus.QUEUED) {
                return bad("INVALID_EVENT");
            }
        } else if (job.getStatus() != RenderJobStatus.STARTED || !event.executionToken().equals(job.getExecutionToken())) {
            return conflict("SUPERSEDED");
        }
        job.failed(at);
        clips.findByIdForUpdate(job.getClipId()).ifPresent(c -> c.failed(event.errorCode(), event.errorMessage()));
        return ok(Map.of());
    }

    /**
     * 계약1 4절·3절 성공 조건: outputId 집합 = 주문서의 outputs 정확 일치 · 각 output에 video 정확히 1 ·
     * 모든 s3Key가 {@code clips/{clipId}/{활성 토큰}/} 아래 · kind는 video|srt. srt 개수 규칙(mode·컷 안 자막)은
     * 일꾼 카드에서 함께 잰다 — 여기서는 「있으면 그 output의 것」까지만 본다.
     *
     * @return 문제 없으면 {@code null}, 있으면 이유(로그용 고정 문자열)
     */
    private String validateResult(RenderJob job, JsonNode result) {
        if (result == null || !result.isArray() || result.isEmpty()) {
            return "result_missing";
        }
        JsonNode payload = mapper.readTree(job.getPayload());
        Set<String> expected = new HashSet<>();
        for (JsonNode output : payload.path("recipe").path("outputs")) {
            expected.add(output.path("outputId").asString());
        }
        String prefix = RenderEnvelope.outputPrefixKey(job.getClipId()) + "/" + job.getExecutionToken() + "/";
        Set<String> videos = new HashSet<>();
        for (JsonNode item : result) {
            String outputId = item.path("outputId").asString(null);
            String kind = item.path("kind").asString(null);
            String s3Key = item.path("s3Key").asString(null);
            if (outputId == null || !expected.contains(outputId)) {
                return "unknown_output";
            }
            if (s3Key == null || !s3Key.startsWith(prefix)) {
                return "key_outside_prefix";
            }
            if ("video".equals(kind)) {
                if (!videos.add(outputId)) {
                    return "duplicate_video";
                }
            } else if (!"srt".equals(kind)) {
                return "unknown_kind";
            }
        }
        return videos.equals(expected) ? null : "video_missing";
    }

    private Reply ok(Map<String, Object> body) {
        return new Reply(200, mapper.writeValueAsString(body));
    }

    private Reply conflict(String reason) {
        return new Reply(409, reasonJson(reason));
    }

    private Reply bad(String reason) {
        return new Reply(400, reasonJson(reason));
    }

    private String reasonJson(String reason) {
        ObjectNode node = mapper.createObjectNode();
        node.put("reason", reason);
        return mapper.writeValueAsString(node);
    }
}
