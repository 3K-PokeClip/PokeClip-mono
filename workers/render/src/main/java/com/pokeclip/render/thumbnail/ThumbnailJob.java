package com.pokeclip.render.thumbnail;

import com.pokeclip.render.job.OffsetOrigin;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/**
 * clip이 낸 사진 주문서(POK-277, {@code services/README.md}「썸네일」 절). 키는 clip이 정한다: 일꾼은 받은 키에서 받아 받은 키에 올리기만 한다.
 * 모양이 틀리면 {@link Optional#empty()}다(다시 해도 같으니 메시지를 지운다).
 *
 * <p>{@code source.offsetOrigin}(PDT 설계 r7 3.1 · 8.1)은 카드 · 라이브의 {@code offsetMs} 를 조각 파일 안 어디서부터 재는가다. 칸이
 * 없으면 {@link OffsetOrigin#FILE_HEAD} — 지금 clip 이 내는 주문이 이 뜻이다. 칸이 있으면 두 이름 가운데 하나여야 한다. 완성 영상
 * ({@code clip})은 결과 파일 안 자리라 이 칸을 쓰지 않는다.
 */
record ThumbnailJob(String kind, String targetId, Instant capturedAt,
                    String sourceBucket, String sourceKey, long offsetMs, OffsetOrigin offsetOrigin,
                    String outputBucket, String outputKey) {

    private static final Set<String> KINDS = Set.of("live", "card", "clip");

    static Optional<ThumbnailJob> parse(ObjectMapper mapper, String body) {
        try {
            JsonNode root = mapper.readTree(body);
            // 정수 1만 받는다. asInt는 "1"·1.9도 1로 바꿔 틀린 모양이 지나간다(렌더 EnvelopeParser와 같은 검사, PR #212 codex)
            JsonNode version = root.get("schemaVersion");
            if (!isInt(version) || version.asInt() != 1) {
                return Optional.empty();
            }
            String kind = root.path("kind").asString("");
            JsonNode source = root.path("source");
            JsonNode output = root.path("output");
            JsonNode offset = source.get("offsetMs");
            Optional<OffsetOrigin> origin = origin(source.get("offsetOrigin"));
            if (!isLong(offset) || origin.isEmpty()) {
                return Optional.empty();
            }
            ThumbnailJob job = new ThumbnailJob(kind, root.path("targetId").asString(""),
                    Instant.parse(root.path("capturedAt").asString("")),
                    source.path("bucket").asString(""), source.path("s3Key").asString(""),
                    offset.asLong(), origin.get(),
                    output.path("bucket").asString(""), output.path("s3Key").asString(""));
            boolean valid = KINDS.contains(kind) && !job.targetId.isBlank() && job.offsetMs >= 0
                    && !job.sourceBucket.isBlank() && !job.sourceKey.isBlank()
                    && !job.outputBucket.isBlank() && job.outputKey.startsWith("thumbnails/") && job.outputKey.endsWith(".jpg");
            return valid ? Optional.of(job) : Optional.empty();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** 칸이 없으면 파일 머리. 있으면 이름 그대로여야 한다 — null · 숫자 · 모르는 이름은 틀린 모양(빈 값). */
    private static Optional<OffsetOrigin> origin(JsonNode node) {
        if (node == null) {
            return Optional.of(OffsetOrigin.FILE_HEAD);
        }
        if (!node.isString()) {
            return Optional.empty();
        }
        for (OffsetOrigin value : OffsetOrigin.values()) {
            if (value.name().equals(node.asString())) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }

    // 정수 칸은 「정수이고 담기는가」 를 먼저 보고 변환한다. 렌더 주문서 job.JsonIntegers 와 같은 확인이다(그 클래스는 job 패키지
    // 안에서만 보인다)

    private static boolean isLong(JsonNode node) {
        return node != null && node.isIntegralNumber() && node.canConvertToLong();
    }

    private static boolean isInt(JsonNode node) {
        return node != null && node.isIntegralNumber() && node.canConvertToInt();
    }
}
