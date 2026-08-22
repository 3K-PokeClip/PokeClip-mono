package com.pokeclip.clip.jumpcard;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;

/**
 * SSE와 HTTP 응답이 같이 쓰는 카드 모양. 2번(web)과의 계약이라 칸 이름을 바꾸지 않는다.
 *
 * <p>편집자 <b>이름</b>을 담지 않는다 — 이름표는 auth가 갖고 있고 물어볼 창구가 아직 없다(POK-175).
 * {@code claimedBy}는 사용자 번호다.
 */
public record JumpCardSnapshot(long id,
                               String streamId,
                               JumpCardSource source,
                               long streamTimestampMs,
                               Window window,
                               Integer score,
                               JsonNode evidence,
                               String claimedBy,
                               Instant claimedAt,
                               Instant claimExpiresAt,
                               boolean hidden,
                               String hiddenBy,
                               long eventSeq,
                               Instant createdAt) {

    public record Window(long startMs, long endMs) {
    }

    /**
     * {@code claimExpiresAt}은 표에 없는 계산값이다 — 만료를 치우는 배경 작업이 없고
     * 집을 때 판정하므로, 저장해 두면 TTL 설정을 바꿨을 때 낡은 값이 남는다.
     */
    public static JumpCardSnapshot of(JumpCard card, Duration claimTtl, ObjectMapper mapper) {
        JsonNode evidence = card.getEvidence() == null ? null : mapper.readTree(card.getEvidence());
        Instant claimExpiresAt = card.getClaimedAt() == null ? null : card.getClaimedAt().plus(claimTtl);
        return new JumpCardSnapshot(
                card.getId(),
                card.getStreamId(),
                card.getSource(),
                card.getStreamTimestampMs(),
                new Window(card.getWindowStartMs(), card.getWindowEndMs()),
                card.getScore(),
                evidence,
                card.getClaimedBy(),
                card.getClaimedAt(),
                claimExpiresAt,
                card.getHiddenAt() != null,
                card.getHiddenBy(),
                card.getEventSeq(),
                card.getCreatedAt());
    }
}
