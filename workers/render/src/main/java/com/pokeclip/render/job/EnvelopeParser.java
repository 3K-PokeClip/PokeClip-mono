package com.pokeclip.render.job;

import com.pokeclip.render.recipe.Recipe;
import com.pokeclip.render.recipe.RecipeParser;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 주문서를 읽는다. <b>봉투는 관용(모르는 칸 무시), 안에 든 레시피는 fail-closed</b>다(계약1 2절).
 *
 * <p>실패는 셋으로 갈린다.
 * <ul>
 *   <li>{@link Unreadable}. jobId조차 못 읽었다. 보고할 곳이 없어 부른 쪽이 메시지를 치운다.</li>
 *   <li>{@link Rejected}. jobId는 읽었고 무토큰 preflight 실패로 보고한다(SCHEMA_VERSION·ENVELOPE_VALIDATION·VALIDATION).</li>
 * </ul>
 */
public final class EnvelopeParser {

    static final int SCHEMA_VERSION = 1;

    private final ObjectMapper mapper;

    public EnvelopeParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public JobEnvelope parse(String body) {
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (JacksonException e) {
            throw new Unreadable("JSON이 아니다");
        }
        if (root == null || !root.isObject()) {
            throw new Unreadable("객체가 아니다");
        }
        UUID jobId = uuid(root.get("jobId"));
        try {
            return parse(root, jobId);
        } catch (RenderFailure failure) {
            throw new Rejected(jobId, failure);
        }
    }

    private JobEnvelope parse(JsonNode root, UUID jobId) {
        JsonNode version = root.get("schemaVersion");
        if (version == null || !version.isIntegralNumber() || version.asInt() != SCHEMA_VERSION) {
            throw RenderFailure.permanent(ErrorCode.SCHEMA_VERSION, "주문서 버전을 지원하지 않는다");
        }
        if (!"RENDER".equals(text(root, "jobType"))) {
            throw envelope("jobType이 RENDER가 아니다");
        }
        String clipId = text(root, "clipId");
        String streamId = text(root, "streamId");
        JsonNode recipeVersion = root.get("recipeVersion");
        if (recipeVersion == null || !recipeVersion.isIntegralNumber()) {
            throw envelope("recipeVersion이 없다");
        }
        String[] output = s3Uri(text(root, "outputPrefix"));
        List<SourceSegment> sources = sources(root.get("sourceKeys"));
        Recipe recipe = RecipeParser.parse(root.get("recipe"));
        if (!recipe.streamId().equals(streamId)) {
            throw RenderFailure.permanent(ErrorCode.VALIDATION, "recipe.streamId가 주문서와 다르다");
        }
        return new JobEnvelope(jobId, clipId, streamId, recipeVersion.asInt(), output[0], output[1], sources, recipe);
    }

    /** 조각 목록. 비어 있어도 여기서는 거절하지 않는다. 그것은 소스 검사의 {@code SOURCE_MISSING}이다(STARTED 뒤). */
    private List<SourceSegment> sources(JsonNode node) {
        if (node == null || !node.isArray()) {
            throw envelope("sourceKeys가 배열이 아니다");
        }
        List<SourceSegment> sources = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isObject()) {
                throw envelope("sourceKeys[]가 객체가 아니다");
            }
            String role = item.path("role").asString("");
            if (!"segment".equals(role)) {
                // ADR-071: 지금 주문서는 조각 목록만 싣는다. 중간본(video/audio 항목)은 아직 없다.
                throw envelope("sourceKeys[].role=" + role + "은 지원하지 않는다");
            }
            sources.add(new SourceSegment(text(item, "bucket"), text(item, "s3Key"), whole(item, "seq"),
                    whole(item, "sourceStartAtMs"), whole(item, "durationMs")));
        }
        for (int i = 1; i < sources.size(); i++) {
            if (sources.get(i).seq() <= sources.get(i - 1).seq()) {
                throw envelope("sourceKeys[]가 seq 오름차순이 아니다");
            }
        }
        return List.copyOf(sources);
    }

    /** {@code s3://bucket/key/prefix} → [bucket, key/prefix]. 끝의 슬래시는 떼어 둔다. */
    static String[] s3Uri(String uri) {
        if (!uri.startsWith("s3://")) {
            throw envelope("outputPrefix가 s3:// 주소가 아니다");
        }
        String rest = uri.substring("s3://".length());
        int slash = rest.indexOf('/');
        if (slash <= 0 || slash == rest.length() - 1) {
            throw envelope("outputPrefix에 버킷과 경로가 다 있어야 한다");
        }
        String key = rest.substring(slash + 1);
        while (key.endsWith("/")) {
            key = key.substring(0, key.length() - 1);
        }
        return new String[] {rest.substring(0, slash), key};
    }

    private static UUID uuid(JsonNode node) {
        if (node == null || !node.isString()) {
            throw new Unreadable("jobId가 없다");
        }
        try {
            return UUID.fromString(node.asString());
        } catch (IllegalArgumentException e) {
            throw new Unreadable("jobId가 UUID가 아니다");
        }
    }

    private static String text(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || !node.isString() || node.asString().isBlank()) {
            throw envelope(field + "가 없다");
        }
        return node.asString();
    }

    private static long whole(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || !node.isIntegralNumber()) {
            throw envelope("sourceKeys[]." + field + "가 정수가 아니다");
        }
        return node.asLong();
    }

    private static RenderFailure envelope(String message) {
        return RenderFailure.permanent(ErrorCode.ENVELOPE_VALIDATION, message);
    }

    /** jobId조차 못 읽은 주문서. 보고할 곳이 없다. */
    public static final class Unreadable extends RuntimeException {
        Unreadable(String message) {
            super(message);
        }
    }

    /** jobId는 읽었고 preflight에서 떨어졌다. 무토큰 TERMINAL_FAILED로 보고한다. */
    public static final class Rejected extends RuntimeException {
        private final UUID jobId;
        private final RenderFailure failure;

        Rejected(UUID jobId, RenderFailure failure) {
            super(failure.getMessage());
            this.jobId = jobId;
            this.failure = failure;
        }

        public UUID jobId() {
            return jobId;
        }

        public RenderFailure failure() {
            return failure;
        }
    }
}
