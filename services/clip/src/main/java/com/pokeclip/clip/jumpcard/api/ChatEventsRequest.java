package com.pokeclip.clip.jumpcard.api;

import com.pokeclip.clip.jumpcard.JumpCardErrors.InvalidHighlightException;
import com.pokeclip.clip.jumpcard.stream.ChatEvent;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code POST /internal/broadcasts/{streamId}/chat-events} 본문. 계약 정본은 수집기의 {@code ClipRelayClient}다.
 *
 * <p><b>record 바인딩이 아니라 {@code JsonNode}를 손으로 읽는다.</b> 이벤트마다 칸이 종류별로 다르고
 * clip은 그 칸을 해석하지 않고 통과시킨다(F8) — 바인딩하면 모르는 칸이 버려지거나 새 칸마다 clip을 고쳐야 한다.
 *
 * <p><b>400은 구조 결함만이다</b>: {@code events}가 없거나 배열이 아니거나 비었거나, 원소가 객체가 아니거나,
 * {@code seq}가 1 이상의 정수가 아니거나, {@code kind}가 비었다. <b>모르는 {@code kind}는 400이 아니다</b> —
 * 수집기가 먼저 배포되는 날 같은 묶음의 채팅이 전부 죽는다. 그것은 전송하는 쪽이 건너뛰고 센다.
 */
public record ChatEventsRequest(List<ChatEvent> events) {

    /** @throws InvalidHighlightException 구조가 깨졌으면 — 칸 이름을 싣는다 */
    public static ChatEventsRequest parse(JsonNode body) {
        JsonNode events = body == null ? null : body.get("events");
        if (events == null || !events.isArray() || events.isEmpty()) {
            throw new InvalidHighlightException("events");
        }
        List<ChatEvent> out = new ArrayList<>(events.size());
        for (JsonNode event : events) {
            if (!event.isObject()) {
                throw new InvalidHighlightException("events");
            }
            JsonNode seq = event.get("seq");
            // 문자열 "1"은 받지 않는다 — 수집기는 숫자로 보내고, 느슨하게 받으면 계약이 조용히 두 갈래가 된다.
            if (seq == null || !seq.isIntegralNumber() || !seq.canConvertToLong() || seq.asLong() < 1) {
                throw new InvalidHighlightException("seq");
            }
            JsonNode kind = event.get("kind");
            if (kind == null || !kind.isString() || kind.asString().isBlank()) {
                throw new InvalidHighlightException("kind");
            }
            out.add(new ChatEvent(seq.asLong(), kind.asString(), event));
        }
        return new ChatEventsRequest(List.copyOf(out));
    }
}
