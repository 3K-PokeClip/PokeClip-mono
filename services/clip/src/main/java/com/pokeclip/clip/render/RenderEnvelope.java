package com.pokeclip.clip.render;

import com.pokeclip.clip.recipe.RecipeDocument;
import com.pokeclip.clip.segment.SegmentSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 주문서(계약1 2절 봉투)를 만든다. 칸 이름이 계약이다 — 일꾼(렌더 워커)이 이 이름으로 읽는다.
 *
 * <p><b>{@code sourceKeys}는 계약1 3절 초안과 다르다.</b> 초안은 1번이 만들 「중간본 파일」(video 1 + audio N)을
 * 전제했는데 그 파일은 아직 없고 렌더 일꾼도 우리 것이 됐다(2026-09-14). 그래서 조각 장부의 <b>조각 파일 목록을
 * 그대로</b> 싣는다 — 조각 하나가 영상+소리 트랙 전부를 담은 완전한 fMP4라(실측 2026-09-15: {@code ftyp+moov+moof…})
 * 일꾼이 이어붙이면 된다. 트랙 번호 ↔ 소리 스트림 대응은 방송의 {@code trackManifest}를 같이 실어 일꾼이 푼다.
 * 이 결정은 계약1 갱신(위키)으로 남긴다.
 */
final class RenderEnvelope {

    static final int SCHEMA_VERSION = 1;

    private RenderEnvelope() {
    }

    static String build(ObjectMapper mapper, UUID jobId, long clipId, String streamId, int recipeVersion,
                        RecipeDocument recipe, List<SegmentSource> segments, String segmentBucket,
                        String outputBucket, String trackManifestJson, Instant requestedAt) {
        ObjectNode root = mapper.createObjectNode();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("jobId", jobId.toString());
        root.put("jobType", "RENDER");
        root.put("clipId", String.valueOf(clipId));
        root.put("correlationId", String.valueOf(clipId));
        root.put("idempotencyKey", clipId + ":RENDER:" + recipeVersion);
        root.put("requestedAt", requestedAt.toString());
        root.put("streamId", streamId);
        root.put("recipeVersion", recipeVersion);
        root.put("outputPrefix", "s3://" + outputBucket + "/" + outputPrefixKey(clipId));

        ArrayNode sourceKeys = root.putArray("sourceKeys");
        for (SegmentSource segment : segments) {
            ObjectNode node = sourceKeys.addObject();
            node.put("role", "segment");
            node.put("bucket", segmentBucket);
            node.put("s3Key", segment.s3Key());
            node.put("seq", segment.seq());
            node.put("sourceStartAtMs", segment.startAtMs());
            node.put("durationMs", segment.durationMs());
        }
        root.set("trackManifest", trackManifestJson == null ? mapper.nullNode() : mapper.readTree(trackManifestJson));
        root.set("recipe", mapper.valueToTree(recipe));
        return mapper.writeValueAsString(root);
    }

    /** 완성 영상이 놓이는 자리(버킷 안 키). 실행마다 그 아래 {@code {executionToken}/}이 붙는다. */
    static String outputPrefixKey(long clipId) {
        return "clips/" + clipId;
    }
}
