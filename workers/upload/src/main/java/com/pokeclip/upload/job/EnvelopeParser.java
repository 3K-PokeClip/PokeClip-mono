package com.pokeclip.upload.job;

import com.pokeclip.upload.job.UploadEnvelope.Thumbnail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 주문서 해석. 모양이 틀리면 {@link Unreadable}: 다시 받아도 같으니 일꾼은 지운다.
 *
 * <p>🔴 POK-291에서 더한 칸(태그·아동용·썸네일, 공개 범위 값)은 <b>너그럽게</b> 읽는다. 모양이 틀리면 기본값으로 떨어뜨리고 경고만
 * 남긴다. 이 칸들 때문에 못 읽는 쪽지로 지우면 clip에 아무 보고도 안 가서 그 줄이 queued로 영원히 남는다.
 */
public class EnvelopeParser {

    private static final Logger log = LoggerFactory.getLogger(EnvelopeParser.class);
    private static final Set<String> PRIVACY = Set.of("private", "unlisted", "public");
    private static final Set<String> IMAGE_TYPES = Set.of("image/jpeg", "image/png");

    private final ObjectMapper mapper;

    public EnvelopeParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public UploadEnvelope parse(String body) {
        try {
            JsonNode root = mapper.readTree(body);
            if (root.path("schemaVersion").asInt() != 1 || !"UPLOAD".equals(root.path("jobType").asString(null))) {
                throw new Unreadable("schemaVersion/jobType");
            }
            JsonNode source = root.path("source");
            JsonNode video = root.path("video");
            long uploadId = Long.parseLong(required(root, "uploadId"));
            return new UploadEnvelope(
                    uploadId,
                    Long.parseLong(required(root, "channelOwnerUserId")),
                    required(source, "bucket"), required(source, "s3Key"),
                    required(video, "title"), video.path("description").asString(""),
                    privacy(uploadId, video.path("privacyStatus")), tags(video.path("tags")),
                    video.path("madeForKids").isBoolean() && video.path("madeForKids").asBoolean(),
                    thumbnail(uploadId, root.path("thumbnail")));
        } catch (Unreadable e) {
            throw e;
        } catch (RuntimeException e) {
            throw new Unreadable(e.getClass().getSimpleName());
        }
    }

    private static String privacy(long uploadId, JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return "private";
        }
        String value = node.isString() ? node.asString() : null;
        if (value == null || !PRIVACY.contains(value)) {
            log.warn("upload.privacy_ignored uploadId={}", uploadId);
            return "private";
        }
        return value;
    }

    /** 배열이 아니면 빈 목록. 배열 안의 문자열이 아닌 것은 버린다. */
    private static List<String> tags(JsonNode node) {
        List<String> tags = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode tag : node) {
                if (tag.isString()) {
                    tags.add(tag.asString());
                }
            }
        }
        return tags;
    }

    /** 없음·{@code none}이면 null. 모양이 틀려도 null(경고). */
    private static Thumbnail thumbnail(long uploadId, JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        String source = node.path("source").asString(null);
        if ("none".equals(source)) {
            return null;
        }
        if ("scene".equals(source)) {
            JsonNode offset = node.path("offsetMs");
            if (offset.isIntegralNumber() && offset.canConvertToLong() && offset.asLong() >= 0) {
                return new Thumbnail.Scene(offset.asLong());
            }
        } else if ("file".equals(source)) {
            String bucket = text(node, "bucket");
            String key = text(node, "s3Key");
            String type = text(node, "contentType");
            // Set.of는 null을 물으면 예외를 던진다. 그러면 못 읽는 쪽지가 되니 null을 먼저 거른다.
            if (bucket != null && key != null && type != null && IMAGE_TYPES.contains(type)) {
                return new Thumbnail.File(bucket, key, type);
            }
        }
        log.warn("upload.thumbnail_ignored uploadId={} source={}", uploadId, source);
        return null;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isString() && !value.asString().isBlank() ? value.asString() : null;
    }

    private static String required(JsonNode node, String field) {
        String value = node.path(field).asString(null);
        if (value == null || value.isBlank()) {
            throw new Unreadable(field);
        }
        return value;
    }

    public static class Unreadable extends RuntimeException {
        public Unreadable(String what) {
            super("주문서를 못 읽었다: " + what);
        }
    }
}
