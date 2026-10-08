package com.pokeclip.clip.purge;

import com.pokeclip.clip.broadcast.BroadcastEventProcessor;
import com.pokeclip.clip.broadcast.LifecycleEnvelope;
import com.pokeclip.clip.broadcast.ProcessResult;
import com.pokeclip.clip.support.IntegrationTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 탈퇴 정리(POK-256). 스트리머 둘을 똑같이 심고 한 명만 지운다. 남의 것이 남는지를 같이 재야
 * 「표를 통째로 비웠다」는 결함이 초록이 되지 않는다.
 */
class StreamerPurgerTest extends IntegrationTestSupport {

    private static final String 탈퇴자 = "90001";
    private static final String 이웃 = "90002";
    private static final Instant 시작 = Instant.parse("2026-10-01T10:00:00Z");

    private final JdbcTemplate jdbc;
    private final StreamerPurgeStore store;
    private final TransactionTemplate tx;
    private final BroadcastEventProcessor processor;

    StreamerPurgerTest(JdbcTemplate jdbc, StreamerPurgeStore store, TransactionTemplate tx,
                       BroadcastEventProcessor processor) {
        this.jdbc = jdbc;
        this.store = store;
        this.tx = tx;
        this.processor = processor;
    }

    @BeforeEach
    @AfterEach
    void 비운다() {
        방송과_카드를_비운다(jdbc);
    }

    @Test
    void 탈퇴자의_방송과_딸린_줄을_전부_지우고_이웃_것은_남긴다() {
        Seeded gone = 심는다(탈퇴자, "S-gone", "K-gone");
        Seeded kept = 심는다(이웃, "S-kept", "K-kept");
        FakeStorage files = new FakeStorage();

        store.request(탈퇴자, Instant.now());
        purger(files).purge(탈퇴자);

        for (String table : List.of("broadcasts", "jump_cards", "recipes", "clips", "broadcast_events")) {
            assertThat(count(table + " WHERE stream_id = 'S-gone'")).as(table).isZero();
            assertThat(count(table + " WHERE stream_id = 'S-kept'")).as(table).isOne();
        }
        assertThat(count("render_jobs WHERE clip_id = " + gone.clipId)).isZero();
        assertThat(count("render_jobs WHERE clip_id = " + kept.clipId)).isOne();
        assertThat(count("render_job_events")).isOne();
        assertThat(count("clip_uploads WHERE clip_id = " + gone.clipId)).isZero();
        assertThat(count("clip_uploads WHERE clip_id = " + kept.clipId)).isOne();
        assertThat(count("thumbnails")).isEqualTo(3);
        assertThat(count("thumbnails WHERE target_id IN ('S-kept', '" + kept.clipId + "', '" + kept.cardId + "')")).isEqualTo(3);
        assertThat(count("stream_segments WHERE stream_id = 'K-gone'")).isZero();
        assertThat(count("stream_segments WHERE stream_id = 'K-kept'")).isEqualTo(2);

        assertThat(files.prefixes).containsExactlyInAnyOrder(
                "clips/" + gone.clipId + "/", "thumbnails/clip/" + gone.clipId + ".jpg",
                "thumbnails/card/" + gone.cardId + ".jpg", "thumbnails/live/S-gone.jpg");
        assertThat(files.segmentKeys).containsExactlyInAnyOrder(
                "streams/K-gone/1.m4s", "dvr/K-gone/1.m4s", "streams/K-gone/2.m4s", "dvr/K-gone/2.m4s",
                "dvr/K-gone/init/a.mp4");
        assertThat(jdbc.queryForObject("SELECT completed_at IS NOT NULL AND cardinality(pending_prefixes) = 0 "
                + "FROM purged_streamers WHERE streamer_id = ?", Boolean.class, 탈퇴자)).isTrue();
    }

    @Test
    void 창고_지우기가_실패하면_명부에_남고_다음_순회가_이어서_지운다() {
        Seeded gone = 심는다(탈퇴자, "S-gone", "K-gone");
        FakeStorage broken = new FakeStorage();
        broken.failOutput = true;

        store.request(탈퇴자, Instant.now());
        assertThatThrownBy(() -> purger(broken).purge(탈퇴자)).isInstanceOf(IllegalStateException.class);

        // 표는 이미 지워졌다. 지울 주소는 명부에만 남아 있다.
        assertThat(count("broadcasts WHERE stream_id = 'S-gone'")).isZero();
        assertThat(store.pendingPrefixes(탈퇴자)).contains("clips/" + gone.clipId + "/");
        assertThat(store.due(10)).containsExactly(탈퇴자);
        // 파일이 먼저라 녹화 조각 줄도 그대로다.
        assertThat(count("stream_segments WHERE stream_id = 'K-gone'")).isEqualTo(2);

        FakeStorage healed = new FakeStorage();
        purger(healed).purge(탈퇴자);
        assertThat(healed.prefixes).contains("clips/" + gone.clipId + "/", "thumbnails/live/S-gone.jpg");
        assertThat(count("stream_segments WHERE stream_id = 'K-gone'")).isZero();
        assertThat(store.due(10)).isEmpty();
    }

    @Test
    void 녹화_조각_파일을_못_지우면_장부_줄을_남긴다() {
        심는다(탈퇴자, "S-gone", "K-gone");
        FakeStorage broken = new FakeStorage();
        broken.failSegments = true;

        store.request(탈퇴자, Instant.now());
        assertThatThrownBy(() -> purger(broken).purge(탈퇴자)).isInstanceOf(IllegalStateException.class);

        // 줄이 먼저 사라지면 파일 키를 다시 알 길이 없다.
        assertThat(count("stream_segments WHERE stream_id = 'K-gone'")).isEqualTo(2);
        assertThat(store.due(10)).containsExactly(탈퇴자);
    }

    @Test
    void 녹화_조각은_묶음으로_나눠_지우고_한_번에_천_개를_넘기지_않는다() {
        방송(탈퇴자, "S-many", "K-many");
        jdbc.update("""
                INSERT INTO stream_segments (stream_id, seq, start_pts_ms, start_wall_utc, duration_ms, s3_key, playback_s3_key)
                SELECT 'K-many', g, g * 2000, now(), 2000, 'streams/K-many/' || g, 'dvr/K-many/' || g
                  FROM generate_series(1, 1201) g""");
        FakeStorage files = new FakeStorage();

        store.request(탈퇴자, Instant.now());
        purger(files).purge(탈퇴자);

        assertThat(count("stream_segments")).isZero();
        assertThat(files.segmentKeys).hasSize(2402);
        assertThat(files.segmentCallSizes).allSatisfy(size -> assertThat(size).isLessThanOrEqualTo(1000));
        assertThat(files.segmentCallSizes).hasSize(3);
    }

    @Test
    void 창고가_없는_배포면_표만_지우고_녹화_조각_줄은_남긴다() {
        심는다(탈퇴자, "S-gone", "K-gone");

        store.request(탈퇴자, Instant.now());
        purger(null).purge(탈퇴자);

        assertThat(count("broadcasts WHERE streamer_id = '" + 탈퇴자 + "'")).isZero();
        assertThat(count("stream_segments WHERE stream_id = 'K-gone'")).isEqualTo(2);
        assertThat(store.due(10)).isEmpty();
    }

    @Test
    void 탈퇴_뒤_늦게_온_방송_편지는_방송을_되살리지_않는다() {
        store.request(탈퇴자, Instant.now());

        ProcessResult result = processor.process(편지(탈퇴자, "S-late", "broadcast.ended"));

        assertThat(result).isEqualTo(ProcessResult.IGNORED_PURGED);
        assertThat(count("broadcasts WHERE stream_id = 'S-late'")).isZero();
        assertThat(count("broadcast_events WHERE stream_id = 'S-late'")).isZero();
        // 버린 편지의 물리 키는 남긴다. 그 키로 이미 쌓인 녹화 조각을 정리기가 찾게 한다(codex 3판).
        assertThat(store.segmentKeys(탈퇴자)).contains("S-late");

        // 끝난 명부에 처음 보는 키의 편지가 오면 다시 연다.
        store.complete(탈퇴자, Instant.now());
        processor.process(new LifecycleEnvelope(1, "evt-" + UUID.randomUUID(), "broadcast.started", 시작, "S-late2",
                탈퇴자, "K-late2", 1, null, null));
        assertThat(store.segmentKeys(탈퇴자)).contains("K-late2");
        assertThat(store.due(10)).containsExactly(탈퇴자);

        // 이웃 편지는 그대로 받는다: 명부 확인이 번호를 안 가리면 여기서 갈린다.
        assertThat(processor.process(편지(이웃, "S-next", "broadcast.started"))).isEqualTo(ProcessResult.PROCESSED);
    }

    @Test
    void 끝난_뒤에_방송_줄이_다시_생기면_정리기가_다시_잡는다() {
        store.request(탈퇴자, Instant.now());
        purger(new FakeStorage()).purge(탈퇴자);
        assertThat(store.due(10)).isEmpty();

        방송(탈퇴자, "S-again", "S-again");

        assertThat(store.due(10)).containsExactly(탈퇴자);
        purger(new FakeStorage()).purge(탈퇴자);
        assertThat(count("broadcasts WHERE stream_id = 'S-again'")).isZero();
    }

    /** 탈퇴 순간 붙어 있던 녹화가 정리 뒤에 조각 줄을 더해도 다시 잡는다(PR #220 codex 1·2판). */
    @Test
    void 끝난_뒤_녹화_조각_줄이_다시_생기면_기한_없이_정리기가_다시_잡는다() {
        심는다(탈퇴자, "S-gone", "K-gone");
        store.request(탈퇴자, Instant.now());
        purger(new FakeStorage()).purge(탈퇴자);
        assertThat(store.due(10)).isEmpty();

        // 끝난 지 오래여도 잡는다(기한을 두면 그보다 오래 이어진 녹화의 줄이 남는다, codex 2판).
        jdbc.update("UPDATE purged_streamers SET completed_at = now() - interval '3 days' WHERE streamer_id = ?", 탈퇴자);
        jdbc.update("INSERT INTO stream_segments (stream_id, seq, start_pts_ms, start_wall_utc, duration_ms, s3_key) "
                + "VALUES ('K-gone', 99, 0, now(), 2000, 'streams/K-gone/99.m4s')");

        assertThat(store.due(10)).containsExactly(탈퇴자);
        FakeStorage files = new FakeStorage();
        purger(files).purge(탈퇴자);
        assertThat(files.segmentKeys).contains("streams/K-gone/99.m4s");
        assertThat(count("stream_segments WHERE stream_id = 'K-gone'")).isZero();
        assertThat(store.due(10)).isEmpty();
    }

    @Test
    void 다시_요청하면_끝난_줄을_다시_연다() {
        store.request(탈퇴자, Instant.now());
        purger(new FakeStorage()).purge(탈퇴자);

        store.request(탈퇴자, Instant.now());

        assertThat(store.due(10)).containsExactly(탈퇴자);
        assertThat(count("purged_streamers")).isOne();
    }

    // ── 도우미 ──────────────────────────────────────────────────────────────

    private StreamerPurger purger(PurgeStorage files) {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        if (files != null) {
            factory.registerSingleton("files", files);
        }
        return new StreamerPurger(store, tx, factory.getBeanProvider(PurgeStorage.class));
    }

    private record Seeded(long clipId, long cardId) {
    }

    /** 방송 하나에 딸린 표 아홉을 한 줄씩(렌더 보고 기록·업로드·사진 셋·녹화 조각 둘·회차 머리 하나). */
    private Seeded 심는다(String streamerId, String streamId, String ingestKey) {
        방송(streamerId, streamId, ingestKey);
        jdbc.update("INSERT INTO broadcast_events (event_id, stream_id, event_type, sequence_no, processed_at) "
                + "VALUES (?, ?, 'BROADCAST_STARTED', 1, now())", "evt-" + streamId, streamId);
        long cardId = jdbc.queryForObject("""
                INSERT INTO jump_cards (stream_id, source, event_id, stream_timestamp_ms, window_start_ms, window_end_ms, score, event_seq)
                VALUES (?, 'auto', ?, 1000, 500, 1500, 5, 0) RETURNING id""", Long.class, streamId, "card-" + streamId);
        long recipeId = jdbc.queryForObject("""
                INSERT INTO recipes (stream_id, creator_id, schema_version, recipe_version, outputs, audio, updated_at)
                VALUES (?, ?, 1, 1, '[]'::jsonb, '{}'::jsonb, now()) RETURNING id""", Long.class, streamId, streamerId);
        long clipId = jdbc.queryForObject("""
                INSERT INTO clips (stream_id, recipe_id, recipe_version, requested_by, status, outputs, updated_at)
                VALUES (?, ?, 1, ?, 'rendered', '[]'::jsonb, now()) RETURNING id""", Long.class, streamId, recipeId, streamerId);
        UUID jobId = UUID.randomUUID();
        jdbc.update("INSERT INTO render_jobs (id, clip_id, status, payload, published_at, updated_at) "
                + "VALUES (?, ?, 'succeeded', '{}'::jsonb, now(), now())", jobId, clipId);
        // 두 스트리머에 같은 모양을 심어 「표를 통째로 비움」과 갈린다.
        jdbc.update("INSERT INTO render_job_events (job_id, event_id, event_type, response_status, response_body) "
                + "VALUES (?, ?, 'SUCCEEDED', 200, '{}'::jsonb)", jobId, UUID.randomUUID());
        jdbc.update("""
                INSERT INTO clip_uploads (clip_id, output_id, requested_by, channel_owner, title, status, payload)
                VALUES (?, 'vert', ?, ?, '제목', 'queued', '{}'::jsonb)""", clipId, streamerId, streamerId);
        jdbc.update("INSERT INTO thumbnails (kind, target_id) VALUES ('live', ?), ('card', ?), ('clip', ?)",
                streamId, Long.toString(cardId), Long.toString(clipId));
        for (int seq = 1; seq <= 2; seq++) {
            jdbc.update("""
                    INSERT INTO stream_segments (stream_id, seq, start_pts_ms, start_wall_utc, duration_ms, s3_key, playback_s3_key)
                    VALUES (?, ?, ?, ?, 2000, ?, ?)""", ingestKey, seq, seq * 2000L, Timestamp.from(시작),
                    "streams/" + ingestKey + "/" + seq + ".m4s", "dvr/" + ingestKey + "/" + seq + ".m4s");
        }
        jdbc.update("INSERT INTO stream_sessions (session_id, stream_id, init_s3_key) VALUES (?, ?, ?)",
                "sess-" + ingestKey, ingestKey, "dvr/" + ingestKey + "/init/a.mp4");
        return new Seeded(clipId, cardId);
    }

    private void 방송(String streamerId, String streamId, String ingestKey) {
        jdbc.update("INSERT INTO broadcasts (stream_id, ingest_stream_id, streamer_id, status, started_at, last_sequence) "
                + "VALUES (?, ?, ?, 'ended', ?, 1)", streamId, ingestKey, streamerId, Timestamp.from(시작));
    }

    private LifecycleEnvelope 편지(String streamerId, String streamId, String type) {
        return new LifecycleEnvelope(1, "evt-" + UUID.randomUUID(), type, 시작, streamId, streamerId, null,
                1, null, null);
    }

    private int count(String fromWhere) {
        return jdbc.queryForObject("SELECT count(*) FROM " + fromWhere, Integer.class);
    }

    /** 지운 것을 적어 두는 가짜 창고. 실패 스위치 둘. */
    private static final class FakeStorage implements PurgeStorage {
        final List<String> prefixes = new ArrayList<>();
        final List<String> segmentKeys = new ArrayList<>();
        final List<Integer> segmentCallSizes = new ArrayList<>();
        boolean failOutput;
        boolean failSegments;

        @Override
        public void deleteOutputPrefix(String prefix) {
            if (failOutput) {
                throw new IllegalStateException("창고 장애");
            }
            prefixes.add(prefix);
        }

        @Override
        public void deleteSegmentObjects(List<String> keys) {
            // 조각 파일 묶음에서만 터진다. 회차 머리 파일(init)에서 먼저 터지면 조각 묶음 순서를 못 잰다.
            if (failSegments && keys.stream().anyMatch(key -> key.startsWith("streams/"))) {
                throw new IllegalStateException("창고 장애");
            }
            segmentKeys.addAll(keys);
            segmentCallSizes.add(keys.size());
        }
    }
}
