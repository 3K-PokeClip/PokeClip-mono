package com.pokeclip.render.report;

import com.pokeclip.render.RenderProperties;
import com.pokeclip.render.job.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * clip 보고 문 {@code POST /internal/jobs/{jobId}/events}(계약1 4절)에 한 통씩 보낸다.
 *
 * <p><b>재전송은 5xx·시한 초과·연결 오류에만</b>, 같은 eventId로 한다. 4xx는 확정 응답이라 그대로 돌려준다. 
 * 무엇을 할지는 부른 쪽이 reason으로 가른다.
 *
 * <p>나가는 HTTP는 JDK 클라이언트로 못박는다. SDK가 끌고 오는 Apache HC5가 뽑히면 DEBUG에서 헤더(내부 토큰)를
 * 통째로 찍고, 저절로 되걸기·리다이렉트 따라가기를 한다(clip {@code HttpClientRetryConfig}의 실측). JDK 클라이언트는
 * 되걸지 않고, 리다이렉트는 여기서 끈다. 토큰이 다른 출처로 따라가지 않게.
 */
public class ClipReporter {

    private static final Logger log = LoggerFactory.getLogger(ClipReporter.class);

    /** clip {@code clips.error_message} 칸 폭(VARCHAR(512)). 보고 문장은 이 안에서 자른다. */
    static final int MAX_ERROR_MESSAGE = 512;

    private final RestClient client;
    private final ObjectMapper mapper;
    private final List<Duration> retryDelays;
    private final Sleeper sleeper;

    public ClipReporter(RenderProperties properties, ObjectMapper mapper, Sleeper sleeper) {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.client = RestClient.builder()
                .requestFactory(factory)
                .baseUrl(properties.clipBaseUrl())
                .defaultHeader("X-Internal-Token", properties.internalToken())
                .build();
        this.mapper = mapper;
        this.retryDelays = properties.reportRetryDelays() == null ? List.of() : properties.reportRetryDelays();
        this.sleeper = sleeper;
    }

    public Reply started(UUID jobId) {
        return send(jobId, "STARTED", null, body -> { });
    }

    public Reply progress(UUID jobId, String token, int percent, String stage) {
        return send(jobId, "PROGRESS", token, body -> {
            ObjectNode progress = body.putObject("progress");
            progress.put("percent", percent);
            progress.put("stage", stage);
        });
    }

    public Reply retryScheduled(UUID jobId, String token, ErrorCode code, String message) {
        return send(jobId, "RETRY_SCHEDULED", token, body -> error(body, code, message, true));
    }

    public Reply succeeded(UUID jobId, String token, List<ResultItem> result) {
        return succeeded(jobId, token, result, null);
    }

    /**
     * @param layout 새 꼴 주문의 결과 배치(PDT 설계 r7 3.3 · 7.6). {@code result} 형제 칸 {@code placements} · {@code lengthUs}
     *               로 싣는다. 옛 꼴은 null — 본문이 예전과 같다
     */
    public Reply succeeded(UUID jobId, String token, List<ResultItem> result, ResultLayout layout) {
        return send(jobId, "SUCCEEDED", token, body -> {
            ArrayNode items = body.putArray("result");
            for (ResultItem item : result) {
                ObjectNode node = items.addObject();
                node.put("outputId", item.outputId());
                node.put("kind", item.kind());
                node.put("s3Key", item.s3Key());
            }
            if (layout != null) {
                placements(body, layout);
            }
        });
    }

    private static void placements(ObjectNode body, ResultLayout layout) {
        ArrayNode placements = body.putArray("placements");
        for (PlacementItem p : layout.placements()) {
            ObjectNode node = placements.addObject();
            node.put("seq", p.seq());
            node.put("zeroAtUs", p.zeroAtUs());
            node.put("fromUs", p.fromUs());
            node.put("toUs", p.toUs());
            node.put("headLeadUs", p.headLeadUs());
        }
        body.put("lengthUs", layout.lengthUs());
    }

    /** @param token null이면 preflight 실패(무토큰)다 */
    public Reply terminalFailed(UUID jobId, String token, ErrorCode code, String message, boolean retryable) {
        return send(jobId, "TERMINAL_FAILED", token, body -> error(body, code, message, retryable));
    }

    private void error(ObjectNode body, ErrorCode code, String message, boolean retryable) {
        ObjectNode error = body.putObject("error");
        error.put("code", code.name());
        error.put("message", safeMessage(message));
        error.put("retryable", retryable);
    }

    private Reply send(UUID jobId, String type, String token, Consumer<ObjectNode> fill) {
        String eventId = UUID.randomUUID().toString();
        ObjectNode body = mapper.createObjectNode();
        body.put("eventId", eventId);
        body.put("eventType", type);
        body.put("occurredAt", Instant.now().toString());
        if (token != null) {
            body.put("executionToken", token);
        }
        fill.accept(body);
        String json = mapper.writeValueAsString(body);

        RuntimeException last = null;
        for (int attempt = 0; attempt <= retryDelays.size(); attempt++) {
            if (attempt > 0) {
                sleeper.sleep(retryDelays.get(attempt - 1));
            }
            try {
                Reply reply = client.post()
                        .uri("/internal/jobs/{jobId}/events", jobId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", eventId)
                        .body(json)
                        .exchange((request, response) -> {
                            int status = response.getStatusCode().value();
                            String text = new String(response.getBody().readAllBytes());
                            JsonNode parsed = text.isBlank() ? null : mapper.readTree(text);
                            return new Reply(status, parsed);
                        });
                if (reply.status() / 100 != 5) {
                    log.info("render.report jobId={} type={} status={} reason={}", jobId, type, reply.status(),
                            reply.reason());
                    return reply;
                }
                last = new IllegalStateException("clip " + reply.status());
            } catch (RuntimeException e) {
                last = e;
            }
            log.warn("render.report_retry jobId={} type={} attempt={} err={}", jobId, type, attempt + 1,
                    last.getMessage());
        }
        throw new ReportUnavailable("clip 보고 실패 type=" + type, last);
    }

    /**
     * 보고 · 로그에 싣는 실패 문장. 거절 문장에는 입력에서 온 글자가 섞일 수 있다(레시피 값 되울림 등). 제어 문자 · 줄 나눔
     * 문자는 빈칸으로 바꿔 로그 한 줄을 위조하지 못하게 하고, {@value #MAX_ERROR_MESSAGE}자(코드 포인트)에서 자른다.
     *
     * @return null 이면 null
     */
    public static String safeMessage(String message) {
        if (message == null) {
            return null;
        }
        StringBuilder safe = new StringBuilder();
        message.codePoints().limit(MAX_ERROR_MESSAGE).forEach(c -> safe.appendCodePoint(lineSafe(c) ? c : ' '));
        return safe.toString();
    }

    private static boolean lineSafe(int codePoint) {
        int type = Character.getType(codePoint);
        return !Character.isISOControl(codePoint) && type != Character.LINE_SEPARATOR
                && type != Character.PARAGRAPH_SEPARATOR;
    }

    /** SUCCEEDED의 산출물 한 줄(계약1 4절 result). */
    public record ResultItem(String outputId, String kind, String s3Key) {
    }

    /**
     * 결과 배치표 한 줄(PDT 설계 r7 7.6). 조각 {@code seq} 의 x′ = 0 이 결과 {@code zeroAtUs} 에 놓이고, 결과에 든 그 조각 몫이
     * x′ 로 {@code [fromUs, toUs]} 다. {@code headLeadUs} = 그 조각 m(파일 머리 → 영상 첫 DTS).
     */
    public record PlacementItem(long seq, long zeroAtUs, long fromUs, long toUs, long headLeadUs) {
    }

    /** SUCCEEDED 의 결과 배치 — 배치표와 결과 길이(µs). */
    public record ResultLayout(List<PlacementItem> placements, long lengthUs) {

        public ResultLayout {
            placements = List.copyOf(placements);
        }
    }

    /** 재전송 대기. 시험은 바로 넘긴다. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration duration);

        static Sleeper real() {
            return duration -> {
                try {
                    Thread.sleep(duration);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("보고 재전송 대기 중 인터럽트", e);
                }
            };
        }
    }
}
