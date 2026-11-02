package com.pokeclip.clip.render;

import com.pokeclip.clip.render.RenderErrors.InvalidJobEventException;
import tools.jackson.databind.JsonNode;

import java.util.Set;
import java.util.UUID;

/**
 * 일꾼의 보고 한 통(계약1 4절). {@code JsonNode}를 손으로 읽는다 — 봉투는 관용(모르는 칸 무시)이 계약이고,
 * 400을 줄 자리를 칸 이름으로 좁혀 던지려는 것이다({@code ChatEventsRequest}와 같은 자세).
 *
 * @param executionToken STARTED와 preflight 실패에는 없다. 나머지는 필수(계약1)
 * @param result SUCCEEDED의 산출물 목록. 검증은 {@link JobEventService}
 */
public record JobEventRequest(UUID eventId, String eventType, String occurredAt, UUID executionToken,
                              Integer progressPercent, String progressStage, JsonNode result,
                              String errorCode, String errorMessage) {

    static final Set<String> EVENT_TYPES = Set.of("STARTED", "PROGRESS", "RETRY_SCHEDULED", "SUCCEEDED", "TERMINAL_FAILED");

    public static JobEventRequest parse(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw new InvalidJobEventException("body");
        }
        UUID eventId = uuid(body.get("eventId"), "eventId");
        JsonNode type = body.get("eventType");
        if (type == null || !type.isString() || !EVENT_TYPES.contains(type.asString())) {
            throw new InvalidJobEventException("eventType");
        }
        JsonNode occurredAt = body.get("occurredAt");
        if (occurredAt == null || !occurredAt.isString() || occurredAt.asString().isBlank()) {
            throw new InvalidJobEventException("occurredAt");
        }
        UUID token = body.hasNonNull("executionToken") ? uuid(body.get("executionToken"), "executionToken") : null;

        Integer percent = null;
        String stage = null;
        JsonNode progress = body.get("progress");
        if (progress != null && progress.isObject()) {
            JsonNode p = progress.get("percent");
            if (p != null && !p.isNull()) {
                if (!p.isIntegralNumber() || p.asInt() < 0 || p.asInt() > 100) {
                    throw new InvalidJobEventException("progress.percent");
                }
                percent = p.asInt();
            }
            JsonNode s = progress.get("stage");
            stage = s != null && s.isString() ? s.asString() : null;
        }

        JsonNode result = body.hasNonNull("result") ? body.get("result") : null;

        String errorCode = null;
        String errorMessage = null;
        JsonNode error = body.get("error");
        if (error != null && error.isObject()) {
            JsonNode code = error.get("code");
            if (code == null || !code.isString() || code.asString().isBlank()) {
                throw new InvalidJobEventException("error.code");
            }
            errorCode = code.asString();
            JsonNode message = error.get("message");
            errorMessage = message != null && message.isString() ? message.asString() : null;
        }
        return new JobEventRequest(eventId, type.asString(), occurredAt.asString(), token, percent, stage, result,
                errorCode, errorMessage);
    }

    private static UUID uuid(JsonNode node, String field) {
        if (node == null || !node.isString()) {
            throw new InvalidJobEventException(field);
        }
        try {
            return UUID.fromString(node.asString());
        } catch (IllegalArgumentException e) {
            throw new InvalidJobEventException(field);
        }
    }
}
