package com.pokeclip.render.support;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.UUID;

/**
 * 시험 주문서. 조각 셋은 로컬 MediaMTX에 실제로 송출해 받은 녹화 파일이다(src/test/resources/segments: 
 * 영상 640x360 30fps + 소리 6트랙, 트랙마다 다른 높이의 사인파). 길이는 그 조각의 장부 {@code duration_ms} 실측값이다.
 */
public final class Fixtures {

    public static final ObjectMapper MAPPER = JsonMapper.builder().build();

    /** 조각 0의 방송 절대 시각. 값 자체는 아무래도 좋다. 레시피 시각이 이것을 기준으로 적힌다. */
    public static final long BASE = 1_790_000_000_000L;
    public static final long[] DURATIONS = {3999, 4053, 4063};
    public static final String BUCKET = "segments";
    public static final String OUTPUT_BUCKET = "clips";

    private Fixtures() {
    }

    public static long start(int i) {
        long at = BASE;
        for (int k = 0; k < i; k++) {
            at += DURATIONS[k];
        }
        return at;
    }

    public static String key(int i) {
        return "streams/rendertest/seg_00000" + i + ".m4s";
    }

    /** 잘 된 레시피. 세로 한 벌, 트랙 1·3, 자막 둘(하나는 컷에 걸친다). */
    public static ObjectNode recipe(String streamId) {
        ObjectNode r = MAPPER.createObjectNode();
        r.put("schemaVersion", 1);
        r.put("streamId", streamId);
        ObjectNode cut = r.putObject("cut");
        cut.put("inAtMs", BASE + 1_000);
        cut.put("outAtMs", BASE + 11_000);
        ArrayNode outputs = r.putArray("outputs");
        ObjectNode o = outputs.addObject();
        o.put("outputId", "o1");
        o.put("aspect", "VERT_9_16");
        ObjectNode crop = o.putObject("crop");
        // 640x360에서 9:16: 높이 전부(360) → 폭 202.5px = 0.31640625
        crop.put("x", 0.341796875);
        crop.put("y", 0.0);
        crop.put("w", 0.31640625);
        crop.put("h", 1.0);
        ArrayNode tracks = r.putObject("audio").putArray("tracks");
        tracks.addObject().put("trackId", 1).put("gain", 1.0);
        tracks.addObject().put("trackId", 3).put("gain", 0.6);
        ObjectNode subtitles = r.putObject("subtitles");
        subtitles.put("mode", "BURN_AND_CC");
        ArrayNode segments = subtitles.putArray("segments");
        segments.addObject().put("startAtMs", BASE + 500).put("endAtMs", BASE + 2_500).put("text", "컷에 걸친 자막");
        segments.addObject().put("startAtMs", BASE + 4_000).put("endAtMs", BASE + 6_000).put("text", "두 번째 자막");
        return r;
    }

    public static ObjectNode envelope(UUID jobId, ObjectNode recipe) {
        ObjectNode e = MAPPER.createObjectNode();
        e.put("schemaVersion", 1);
        e.put("jobId", jobId.toString());
        e.put("jobType", "RENDER");
        e.put("clipId", "42");
        e.put("correlationId", "42");
        e.put("idempotencyKey", "42:RENDER:1");
        e.put("requestedAt", "2026-09-24T00:00:00Z");
        e.put("streamId", recipe.path("streamId").asString());
        e.put("recipeVersion", 1);
        e.put("outputPrefix", "s3://" + OUTPUT_BUCKET + "/clips/42");
        ArrayNode sources = e.putArray("sourceKeys");
        for (int i = 0; i < DURATIONS.length; i++) {
            ObjectNode s = sources.addObject();
            s.put("role", "segment");
            s.put("bucket", BUCKET);
            s.put("s3Key", key(i));
            s.put("seq", i);
            s.put("sourceStartAtMs", start(i));
            s.put("durationMs", DURATIONS[i]);
        }
        e.putNull("trackManifest");
        e.set("recipe", recipe);
        return e;
    }
}
