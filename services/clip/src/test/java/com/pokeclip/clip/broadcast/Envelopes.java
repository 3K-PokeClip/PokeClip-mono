package com.pokeclip.clip.broadcast;

import com.pokeclip.clip.support.TestIds;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

/** 태스크 4·5·9가 함께 쓰는 봉투 도우미. public이라 다른 패키지에서도 부른다. */
public final class Envelopes {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static LifecycleEnvelope started(String eventId, String streamId, long sequence) {
        return new LifecycleEnvelope(1, eventId, "broadcast.started",
                Instant.parse("2026-08-18T00:00:00Z"), streamId, TestIds.STREAMER, sequence,
                "trace-1", MAPPER.createObjectNode());
    }

    /** trackManifest에 값이 든 봉투. jsonb 왕복을 실제로 재려면 이것이 필요하다. */
    public static LifecycleEnvelope startedWithManifest(String eventId, String streamId, long sequence) {
        return new LifecycleEnvelope(1, eventId, "broadcast.started",
                Instant.parse("2026-08-18T00:00:00Z"), streamId, TestIds.STREAMER, sequence,
                "trace-1", MAPPER.readTree("""
                        {"trackManifest": {"manifestVersion": 3, "tracks": 6}}"""));
    }

    public static LifecycleEnvelope ended(String eventId, String streamId, long sequence) {
        return new LifecycleEnvelope(1, eventId, "broadcast.ended",
                Instant.parse("2026-08-18T01:00:00Z"), streamId, TestIds.STREAMER, sequence,
                "trace-1", MAPPER.createObjectNode());
    }

    /** streamerId가 없어 NOT NULL 제약에 걸린다 — 저장 실패 경로를 만든다. */
    public static LifecycleEnvelope startedWithoutStreamer(String eventId, String streamId, long sequence) {
        return new LifecycleEnvelope(1, eventId, "broadcast.started",
                Instant.parse("2026-08-18T00:00:00Z"), streamId, null, sequence,
                "trace-1", MAPPER.createObjectNode());
    }

    private Envelopes() {
    }
}
