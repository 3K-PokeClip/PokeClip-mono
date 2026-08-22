package com.pokeclip.clip.jumpcard.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import tools.jackson.databind.JsonNode;

/**
 * 계약 2A의 본문. {@code @Valid} 검증은 컨트롤러가 건다 — 서비스는 이미 검증된 값을 받는다.
 */
public record HighlightRequest(@NotBlank String eventId,
                               @NotBlank String source,
                               @PositiveOrZero long streamTimestampMs,
                               @NotNull @Valid Window window,
                               Integer score,
                               JsonNode evidence) {

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
