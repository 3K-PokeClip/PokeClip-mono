package com.pokeclip.render.thumbnail;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/**
 * clip이 낸 사진 주문서(POK-277, {@code services/README.md}「썸네일」 절). 키는 clip이 정한다: 일꾼은 받은 키에서 받아 받은 키에 올리기만 한다.
 * 모양이 틀리면 {@link Optional#empty()}다(다시 해도 같으니 메시지를 지운다).
 */
record ThumbnailJob(String kind, String targetId, Instant capturedAt,
                    String sourceBucket, String sourceKey, long offsetMs,
                    String outputBucket, String outputKey) {

    private static final Set<String> KINDS = Set.of("live", "card", "clip");

    static Optional<ThumbnailJob> parse(ObjectMapper mapper, String body) {
        try {
            JsonNode root = mapper.readTree(body);
            // 정수 1만 받는다. asInt는 "1"·1.9도 1로 바꿔 틀린 모양이 지나간다(렌더 EnvelopeParser와 같은 검사, PR #212 codex)
            JsonNode version = root.get("schemaVersion");
            if (version == null || !version.isIntegralNumber() || version.asInt() != 1) {
                return Optional.empty();
            }
            String kind = root.path("kind").asString("");
            JsonNode source = root.path("source");
            JsonNode output = root.path("output");
            ThumbnailJob job = new ThumbnailJob(kind, root.path("targetId").asString(""),
                    Instant.parse(root.path("capturedAt").asString("")),
                    source.path("bucket").asString(""), source.path("s3Key").asString(""),
                    source.path("offsetMs").asLong(-1),
                    output.path("bucket").asString(""), output.path("s3Key").asString(""));
            boolean valid = KINDS.contains(kind) && !job.targetId.isBlank() && job.offsetMs >= 0
                    && !job.sourceBucket.isBlank() && !job.sourceKey.isBlank()
                    && !job.outputBucket.isBlank() && job.outputKey.startsWith("thumbnails/") && job.outputKey.endsWith(".jpg");
            return valid ? Optional.of(job) : Optional.empty();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
