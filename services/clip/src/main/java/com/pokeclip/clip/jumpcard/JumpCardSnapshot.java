package com.pokeclip.clip.jumpcard;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * SSE와 HTTP 응답이 같이 쓰는 카드 모양. 2번(web)과의 계약이라 칸 이름을 바꾸지 않는다.
 *
 * <p>{@code hidden}은 가린 카드다(POK-463): 화면이 이것으로 「숨김」 칸과 나머지 칸을 가른다. 가린 사람은 싣지 않는다
 * (화면이 안 쓴다). 점유 칸(옛 {@code claimedBy} 등)은 POK-393에서 뺐다.
 *
 * <p>{@code thumbnailUrl}은 카드 시점 장면의 사진 주소다(POK-277, 수명 60분 미리서명). <b>목록 문만 싣는다</b>: 통로(SSE)로 나가는
 * 카드는 막 생긴 카드라 사진이 아직 없고(조각이 올라온 뒤 1분 순회가 찍는다), 통로로 주소를 보내면 수명이 지난 뒤에도 화면에 남는다.
 * 화면은 목록을 다시 받을 때 사진을 얻는다.
 *
 * <p>{@code fileHead} · {@code playerAtMs}는 카드 장면(POK-196 · {@link CardPositionReader})이다 — 편집기는 {@code fileHead}
 * (파일명 시각 축)로, 플레이어 카드 클릭은 {@code playerAtMs}(목록 PDT 축)로 간다. 둘 다 같은 장면에서 나오고, 같은 장면에서
 * <b>계산된 썸네일 주문 값</b>({@code CardPositionReader.Scene.thumbnailOrder()})과 같은 조각이다. 🔴 실제 사진 순회는
 * PR-B2(S-c4) 전환 전까지 지금 SQL({@code ThumbnailTargets} — 시간창 안 덮는 행 · seq 최소)이라 틈 · 겹침 · 비귀속 자리에서는
 * 사진이 다른 조각이거나 아직 사진이 없을 수 있다(틈 · 비귀속 자리는 지금 SQL 에 덮는 행이 없어 사진 주문이 나가지 않을 수 있다.
 * 겹침 예: 귀속 행 A {@code [0,4000)} · B {@code [3999,7999)} · 카드 3999 → 장면 B · 사진 A). 이미 찍힌 사진은 전환 뒤 다시
 * 찍지 않는다. 목록 · 통로 · 마크 응답이 모두 싣고, 장면이 없으면 {@code null}(웹은 기준점 + 카드 ms 의 지금 식으로 떨어진다).
 * 「기준점 + 카드 ms」 는 출시 이미지가 쓴 판 NULL 행에서 다른 조각을 가리켜 이 칸이 따로 있다(설계 r7 8.2).
 */
public record JumpCardSnapshot(long id,
                               String streamId,
                               JumpCardSource source,
                               long streamTimestampMs,
                               Window window,
                               Integer score,
                               JsonNode evidence,
                               boolean hidden,
                               long eventSeq,
                               Instant createdAt,
                               String thumbnailUrl,
                               FileHead fileHead,
                               Long playerAtMs) {

    public record Window(long startMs, long endMs) {
    }

    /** 카드 ms · 창 두 끝의 파일명 시각 축 절대 시각(epoch ms) — 편집기 {@code /get} 축. */
    public record FileHead(long atMs, long startMs, long endMs) {
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
                card.getHiddenAt() != null,
                card.getEventSeq(),
                card.getCreatedAt(),
                null,
                null,
                null);
    }

    public JumpCardSnapshot withThumbnailUrl(String url) {
        return new JumpCardSnapshot(id, streamId, source, streamTimestampMs, window, score, evidence, hidden, eventSeq, createdAt, url,
                fileHead, playerAtMs);
    }

    /** 장면을 찾을 값 셋 — 카드 ms · 창 두 끝({@link #withScenes}이 이 값으로 꺼낸다). */
    public List<Long> sceneValues() {
        return List.of(streamTimestampMs, window.startMs(), window.endMs());
    }

    /**
     * {@link CardPositionReader}가 낸 장면을 두 칸에 입힌다. 카드 ms 장면이 없으면(라이브 머리) 두 칸 모두 비고, 창 끝 하나라도
     * 장면이 없으면 {@code fileHead} 만 빈다 — 셋을 함께 내는 칸이라 반쪽을 싣지 않는다. 창 두 끝은 같은 사상이라 단조지만 판
     * NULL 벽시각 역행(E17)으로 끝이 시작보다 이를 수 있어 그때 끝을 시작에 묶는다.
     *
     * @param scenes 값 → 장면({@link #sceneValues()}의 값으로 찾는다)
     */
    public JumpCardSnapshot withScenes(Map<Long, CardPositionReader.Scene> scenes) {
        CardPositionReader.Scene at = scenes.get(streamTimestampMs);
        if (at == null) {
            return withHeads(null, null);
        }
        CardPositionReader.Scene start = scenes.get(window.startMs());
        CardPositionReader.Scene end = scenes.get(window.endMs());
        FileHead head = start == null || end == null
                ? null
                : new FileHead(at.fileHeadMs(), start.fileHeadMs(), Math.max(start.fileHeadMs(), end.fileHeadMs()));
        return withHeads(head, at.playerAtMs());
    }

    private JumpCardSnapshot withHeads(FileHead head, Long player) {
        return new JumpCardSnapshot(id, streamId, source, streamTimestampMs, window, score, evidence, hidden, eventSeq, createdAt,
                thumbnailUrl, head, player);
    }
}
