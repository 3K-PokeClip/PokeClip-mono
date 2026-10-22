package com.pokeclip.clip.jumpcard;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

/**
 * SSE와 HTTP 응답이 같이 쓰는 카드 모양. 2번(web)과의 계약이라 칸 이름을 바꾸지 않는다.
 *
 * <p>점유·숨김 칸(옛 {@code claimedBy}·{@code hidden} 등)은 POK-393에서 뺐다. 표에는 POK-394가 지울 때까지 남는다.
 *
 * <p>{@code thumbnailUrl}은 카드 시점 장면의 사진 주소다(POK-277, 수명 60분 미리서명). <b>목록 문만 싣는다</b>: 통로(SSE)로 나가는
 * 카드는 막 생긴 카드라 사진이 아직 없고(조각이 올라온 뒤 1분 순회가 찍는다), 통로로 주소를 보내면 수명이 지난 뒤에도 화면에 남는다.
 * 화면은 목록을 다시 받을 때 사진을 얻는다.
 */
public record JumpCardSnapshot(long id,
                               String streamId,
                               JumpCardSource source,
                               long streamTimestampMs,
                               Window window,
                               Integer score,
                               JsonNode evidence,
                               long eventSeq,
                               Instant createdAt,
                               String thumbnailUrl) {

    public record Window(long startMs, long endMs) {
    }

    public static JumpCardSnapshot of(JumpCard card, ObjectMapper mapper) {
        JsonNode evidence = card.getEvidence() == null ? null : mapper.readTree(card.getEvidence());
        return new JumpCardSnapshot(
                card.getId(),
                card.getStreamId(),
                card.getSource(),
                card.getStreamTimestampMs(),
                new Window(card.getWindowStartMs(), card.getWindowEndMs()),
                card.getScore(),
                evidence,
                card.getEventSeq(),
                card.getCreatedAt(),
                null);
    }

    public JumpCardSnapshot withThumbnailUrl(String url) {
        return new JumpCardSnapshot(id, streamId, source, streamTimestampMs, window, score, evidence, eventSeq, createdAt, url);
    }
}
