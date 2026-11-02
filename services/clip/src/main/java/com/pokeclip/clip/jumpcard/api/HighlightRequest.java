package com.pokeclip.clip.jumpcard.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

/**
 * 계약 2A의 본문. {@code @Valid} 검증은 컨트롤러가 건다 — 서비스는 이미 검증된 값을 받는다.
 */
public record HighlightRequest(@NotBlank @Size(max = EVENT_ID_MAX) String eventId,
                               @NotBlank String source,
                               @PositiveOrZero long streamTimestampMs,
                               @NotNull @Valid Window window,
                               Integer score,
                               JsonNode evidence) {

    /**
     * {@code jump_cards.event_id}가 {@code VARCHAR(128)}이다. 이 검증이 없으면 긴 값이 DB까지
     * 가서 {@code value too long for type character varying(128)}(SQLState 22001)로 터지고
     * <b>500</b>이 나간다 — 계약은 400 {@code invalid_request}다.
     *
     * <p>500이면 판별기가 재시도하는데 같은 payload라 <b>영영 성공하지 못한다</b>(실측).
     * 400이어야 「이 요청이 잘못됐다」가 전달돼 재시도가 멈춘다.
     */
    static final int EVENT_ID_MAX = 128;

    public record Window(@PositiveOrZero long startMs, @Positive long endMs) {
    }

    /**
     * 창이 뒤집혔거나 지점이 창 밖이면 false.
     *
     * <p>DB CHECK가 최종 방어이고 이것은 400을 주기 위한 앞 검사다 — 제약 위반은
     * 500으로 나가는데, 요청이 잘못된 것이라면 판별기가 그것을 알아야 한다.
     */
    public boolean consistent() {
        return window.startMs() < window.endMs()
                && window.startMs() <= streamTimestampMs
                && streamTimestampMs <= window.endMs();
    }
}
