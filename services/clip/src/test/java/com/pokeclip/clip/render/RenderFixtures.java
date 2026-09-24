package com.pokeclip.clip.render;

import com.pokeclip.clip.support.TestIds;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * 렌더 시험 둘이 나눠 쓰는 씨앗 — 방송·편집본·조각·완성 영상·주문을 표에 직접 심는다. 컨트롤러를 거치지 않는 이유는
 * 이 시험들이 재는 것이 주문·보고 경로지 편집본 저장이 아니어서다.
 */
public final class RenderFixtures {

    /** 컷 45초. 재생 축 UTC epoch ms — 조각도 같은 축으로 심는다. */
    public static final long CUT_IN = 1_757_900_000_000L;
    public static final long CUT_OUT = CUT_IN + 45_000;
    public static final int SEGMENT_MS = 4_000;

    private RenderFixtures() {
    }

    public static void 방송을_넣는다(JdbcTemplate jdbc, String streamId) {
        jdbc.update("""
                        INSERT INTO broadcasts (stream_id, streamer_id, status, started_at, last_sequence, track_manifest)
                        VALUES (?, ?, 'live', ?, 1, CAST(? AS jsonb))""",
                streamId, TestIds.STREAMER, OffsetDateTime.ofInstant(Instant.ofEpochMilli(CUT_IN - 60_000), ZoneOffset.UTC),
                "{\"manifestVersion\":3,\"tracks\":[{\"trackId\":1},{\"trackId\":3}]}");
    }

    /** @return 편집본 번호. {@code cutIn}이 {@code null}이면 템플릿 */
    public static long 편집본을_넣는다(JdbcTemplate jdbc, String streamId, Long cutIn, Long cutOut) {
        return jdbc.queryForObject("""
                        INSERT INTO recipes (stream_id, creator_id, schema_version, recipe_version, cut_in_at_ms, cut_out_at_ms,
                                             outputs, audio, subtitles, updated_at)
                        VALUES (?, '4180', 1, 1, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), CAST(? AS jsonb), now())
                        RETURNING id""", Long.class,
                streamId, cutIn, cutOut,
                "[{\"outputId\":\"o1\",\"aspect\":\"VERT_9_16\",\"crop\":{\"x\":0.21,\"y\":0.0,\"w\":0.316,\"h\":1.0}}]",
                "{\"tracks\":[{\"trackId\":1,\"gain\":1.0}]}",
                "{\"mode\":\"BURN_AND_CC\",\"segments\":[{\"startAtMs\":" + (CUT_IN + 1000) + ",\"endAtMs\":" + (CUT_IN + 3000)
                        + ",\"text\":\"자막\"}]}");
    }

    /**
     * 컷을 넉넉히 덮는 조각들(앞뒤 한 조각 여유). {@code pendingSeq}가 0보다 크면 그 조각만 {@code pending}이다.
     * 재생 축은 {@code playback_pdt}에, 녹화 축은 일부러 <b>다른 값</b>(1시간 뒤)으로 심는다 — 조회가 어느 축을 보는지 드러나게.
     */
    public static void 조각을_넣는다(JdbcTemplate jdbc, String streamId, long pendingSeq) {
        long seq = 1;
        for (long start = CUT_IN - SEGMENT_MS; start < CUT_OUT + SEGMENT_MS; start += SEGMENT_MS, seq++) {
            jdbc.update("""
                            INSERT INTO stream_segments
                                (stream_id, seq, start_pts_ms, start_wall_utc, playback_pdt, duration_ms, s3_key, upload_state)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                    streamId, seq, (seq - 1) * SEGMENT_MS,
                    OffsetDateTime.ofInstant(Instant.ofEpochMilli(start + 3_600_000), ZoneOffset.UTC),
                    OffsetDateTime.ofInstant(Instant.ofEpochMilli(start), ZoneOffset.UTC),
                    SEGMENT_MS, "streams/" + streamId + "/seg_" + seq + ".m4s", seq == pendingSeq ? "pending" : "uploaded");
        }
    }

    /** 완성 영상 + 주문 한 벌을 「주문됨」으로. @return jobId */
    public static UUID 주문을_넣는다(JdbcTemplate jdbc, String streamId, long recipeId, long clipIdOut[]) {
        long clipId = jdbc.queryForObject("""
                        INSERT INTO clips (stream_id, recipe_id, recipe_version, requested_by, status, updated_at)
                        VALUES (?, ?, 1, '4180', 'queued', now()) RETURNING id""", Long.class, streamId, recipeId);
        UUID jobId = UUID.randomUUID();
        String payload = "{\"schemaVersion\":1,\"jobId\":\"" + jobId + "\",\"clipId\":\"" + clipId
                + "\",\"outputPrefix\":\"s3://clips-test/clips/" + clipId + "\","
                + "\"recipe\":{\"outputs\":[{\"outputId\":\"o1\"}]}}";
        jdbc.update("""
                        INSERT INTO render_jobs (id, clip_id, status, payload, published_at, updated_at)
                        VALUES (?, ?, 'queued', CAST(? AS jsonb), now(), now())""", jobId, clipId, payload);
        clipIdOut[0] = clipId;
        return jobId;
    }
}
