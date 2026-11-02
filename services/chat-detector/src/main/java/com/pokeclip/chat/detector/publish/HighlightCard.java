package com.pokeclip.chat.detector.publish;

/**
 * clip에 보낼 카드 하나. 칸 이름은 clip의 {@code HighlightRequest} 그대로다 —
 * 틀리면 {@code @Valid}가 400을 준다.
 *
 * @param eventId 추적용. clip의 {@code jump_cards.event_id}가 {@code VARCHAR(128)}이라
 *                넘치면 400이다. 우리는 {@code detect-<집계 줄 번호>}라 넘칠 일이 없다
 */
public record HighlightCard(String streamId,
                            String eventId,
                            long streamTimestampMs,
                            long windowStartMs,
                            long windowEndMs,
                            String evidenceJson) {

    /**
     * clip이 {@code startMs < endMs && startMs <= streamTimestampMs <= endMs}를 검사하고
     * 어기면 400이다. 표의 CHECK 제약도 같은 것을 본다.
     *
     * <p>음수도 여기서 걸러야 한다 — clip은 {@code @PositiveOrZero}다. 방송 아주 초반의
     * 창이 이 경우가 될 수 있다(보정값 약 4초를 빼면 녹화 시작 전이 된다).
     */
    public boolean valid() {
        return windowStartMs >= 0
                && windowStartMs < windowEndMs
                && windowStartMs <= streamTimestampMs
                && streamTimestampMs <= windowEndMs;
    }

    /** {@code source}는 늘 {@code auto}다. {@code hotkey}는 POK-119의 몫이다. */
    public String toJson() {
        return "{\"eventId\":\"" + eventId + "\""
                + ",\"source\":\"auto\""
                + ",\"streamTimestampMs\":" + streamTimestampMs
                + ",\"window\":{\"startMs\":" + windowStartMs + ",\"endMs\":" + windowEndMs + "}"
                + ",\"evidence\":" + evidenceJson + "}";
    }
}
