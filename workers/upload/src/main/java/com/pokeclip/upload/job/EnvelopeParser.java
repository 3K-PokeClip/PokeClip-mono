package com.pokeclip.upload.job;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 주문서 해석. 모양이 틀리면 {@link Unreadable}: 다시 받아도 같으니 일꾼은 지운다. */
public class EnvelopeParser {

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
            return new UploadEnvelope(
                    Long.parseLong(required(root, "uploadId")),
                    Long.parseLong(required(root, "channelOwnerUserId")),
                    required(source, "bucket"), required(source, "s3Key"),
                    required(video, "title"), video.path("description").asString(""),
                    video.path("privacyStatus").asString("private"));
        } catch (Unreadable e) {
            throw e;
        } catch (RuntimeException e) {
            throw new Unreadable(e.getClass().getSimpleName());
        }
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
