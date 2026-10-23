package com.pokeclip.clip.broadcast;

import com.pokeclip.clip.support.TestIds;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

/** 태스크 4·5·9가 함께 쓰는 봉투 도우미. public이라 다른 패키지에서도 부른다. */
public final class Envelopes {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static LifecycleEnvelope started(String eventId, String streamId, long sequence) {
        return new LifecycleEnvelope(1, eventId, "broadcast.started",
                Instant.parse("2026-08-18T00:00:00Z"), streamId, TestIds.STREAMER, null, sequence,
                "trace-1", MAPPER.createObjectNode());
    }

    /** trackManifest에 값이 든 봉투. jsonb 왕복을 실제로 재려면 이것이 필요하다. */
    public static LifecycleEnvelope startedWithManifest(String eventId, String streamId, long sequence) {
        return new LifecycleEnvelope(1, eventId, "broadcast.started",
                Instant.parse("2026-08-18T00:00:00Z"), streamId, TestIds.STREAMER, null, sequence,
                "trace-1", MAPPER.readTree("""
                        {"trackManifest": {"manifestVersion": 3, "tracks": 6}}"""));
    }

    public static LifecycleEnvelope ended(String eventId, String streamId, long sequence) {
        return new LifecycleEnvelope(1, eventId, "broadcast.ended",
                Instant.parse("2026-08-18T01:00:00Z"), streamId, TestIds.STREAMER, null, sequence,
                "trace-1", MAPPER.createObjectNode());
    }

    /** 물리 키를 실은 봉투(POK-233). streamId는 회차 번호, ingestStreamId는 조각 장부·녹화 경로의 키다. */
    public static LifecycleEnvelope startedWithIngest(String eventId, String streamId, String ingestStreamId,
                                                      long sequence) {
        return new LifecycleEnvelope(1, eventId, "broadcast.started",
                Instant.parse("2026-08-18T00:00:00Z"), streamId, TestIds.STREAMER, ingestStreamId, sequence,
                "trace-1", MAPPER.createObjectNode());
    }

    public static LifecycleEnvelope endedWithIngest(String eventId, String streamId, String ingestStreamId,
                                                    long sequence) {
        return new LifecycleEnvelope(1, eventId, "broadcast.ended",
                Instant.parse("2026-08-18T01:00:00Z"), streamId, TestIds.STREAMER, ingestStreamId, sequence,
                "trace-1", MAPPER.createObjectNode());
    }

    /** 시각을 정해 주는 시작 봉투(POK-327). 늦게 온 시작이 끝보다 앞인지 뒤인지, 목록에 어느 시각이 실리는지를 잰다. */
    public static LifecycleEnvelope started(String eventId, String streamId, String ingestStreamId, long sequence,
                                            Instant occurredAt) {
        return new LifecycleEnvelope(1, eventId, "broadcast.started",
                occurredAt, streamId, TestIds.STREAMER, ingestStreamId, sequence,
                "trace-1", MAPPER.createObjectNode());
    }

    public static LifecycleEnvelope ended(String eventId, String streamId, String ingestStreamId, long sequence,
                                          Instant occurredAt) {
        return new LifecycleEnvelope(1, eventId, "broadcast.ended",
                occurredAt, streamId, TestIds.STREAMER, ingestStreamId, sequence,
                "trace-1", MAPPER.createObjectNode());
    }

    /** 플랫폼 칸을 실은 시작 봉투(POK-398). 마지막 칸이 {@code platform}이다. */
    public static LifecycleEnvelope startedOn(String eventId, String streamId, long sequence, String platform) {
        return new LifecycleEnvelope(1, eventId, "broadcast.started",
                Instant.parse("2026-08-18T00:00:00Z"), streamId, TestIds.STREAMER, null, sequence,
                "trace-1", MAPPER.createObjectNode(), platform);
    }

    public static LifecycleEnvelope endedOn(String eventId, String streamId, long sequence, String platform) {
        return new LifecycleEnvelope(1, eventId, "broadcast.ended",
                Instant.parse("2026-08-18T01:00:00Z"), streamId, TestIds.STREAMER, null, sequence,
                "trace-1", MAPPER.createObjectNode(), platform);
    }

    /** streamerId가 없어 NOT NULL 제약에 걸린다 — 저장 실패 경로를 만든다. */
    public static LifecycleEnvelope startedWithoutStreamer(String eventId, String streamId, long sequence) {
        return new LifecycleEnvelope(1, eventId, "broadcast.started",
                Instant.parse("2026-08-18T00:00:00Z"), streamId, null, null, sequence,
                "trace-1", MAPPER.createObjectNode());
    }

    private Envelopes() {
    }
}
