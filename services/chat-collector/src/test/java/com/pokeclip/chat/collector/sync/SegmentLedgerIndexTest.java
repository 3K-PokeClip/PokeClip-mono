package com.pokeclip.chat.collector.sync;

import com.pokeclip.chat.collector.support.IntegrationTestSupport;
import com.pokeclip.chat.collector.support.StreamSegmentsFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 색인 기록(설계 r6 7.4 · r7 10절) — 두 floor 조회가 한쪽 판만 있는 스트림에서 <b>다른 판 행을 끝까지 훑지 않는지</b>를
 * {@code EXPLAIN (ANALYZE, BUFFERS)}의 버퍼 수로 잰다. 장부 셋(판 2 만 30만 행 · 판 NULL 만 30만 행 · 판 2 30만 행 뒤 판
 * NULL 7,200 행 — 롤백 8시간)에서 두 조회를 부분 색인 있음 · 없음으로 돌려 시험 로그에 남긴다.
 *
 * <p><b>색인은 이 카드가 만들지 않는다</b> — 운영 색인은 생산자 DDL({@code media/internal/index/ddl.go})이 깐다(처분 P-3).
 * 그래서 시험이 그 정의를 옮겨 스스로 만든다. 정의가 바뀌면 여기도 바꾼다.
 *
 * <p>판정(설계 r6 7.4 그대로): 색인 있음에서 꼬리 시각의 두 조회가 세 장부 모두 꼬리 몇 행 수준이다(여섯 경우). 대조로
 * 색인 없음에서는 판 2 후보 · 옛 후보 조회가 그 판이 아닌 행을 끝까지 훑어 버퍼가 크다(색인이 실제로 일을 하는지).
 *
 * <p>옛 후보 조회의 역행 신호 {@code earlier_is_future}는 옛 꼴({@code EXISTS(y.seq < s.seq AND y.start_wall_utc >
 * s.start_wall_utc)})이면 플래너가 표 전체 Seq Scan으로 풀었다(2026-11-13 실측 · 표 907,200행 · 12,676 버퍼 · 약 35ms).
 * 지금 꼴은 같은 뜻을 「seq 가 앞선 판 NULL 행 가운데 파일명 시각 최대 하나」 로 고쳐 써 판 NULL 부분 색인을 거꾸로 탄다 —
 * 꼬리 시각이면 꼬리 몇 행이다. 꼬리에서 먼 시각이면 그 시각 뒤 판 NULL 행 수만큼 읽는다(표 전체가 아니라 그 스트림 안).
 *
 * <p>판 2 후보 조회를 첫 판 2 PDT보다 이른 시각으로 부르는 경우(보안 L2)도 기록만 한다 — 판 2 후보가 없어 그 스트림의 판 2
 * 행을 끝까지 훑는다(2026-11-13 실측 · 판 2 30만 행에서 7,246 버퍼 · 약 37ms).
 *
 * <p><b>방송 번호 접두는 {@code idx-}다.</b> 행 수가 커서 끝나면 지운다.
 */
@SpringBootTest
@ActiveProfiles("test")
class SegmentLedgerIndexTest extends IntegrationTestSupport {

    private static final Logger log = LoggerFactory.getLogger(SegmentLedgerIndexTest.class);

    private static final Instant T0 = Instant.parse("2026-11-13T00:00:00Z");
    private static final int ROWS = 300_000;
    private static final int ROLLBACK_ROWS = 7_200;
    private static final int SEGMENT_MS = 4_000;

    /** 「꼬리 몇 행」 의 상한. 색인 뿌리 · 가지 · 잎 몇 장과 힙 몇 장이다. */
    private static final long TAIL_BUFFERS = 30;

    /** 「끝까지 훑음」 의 하한. 30만 행이면 수천 장이다. */
    private static final long FULL_SCAN_BUFFERS = 1_000;

    private static final String V2_ONLY = "idx-v2-only";
    private static final String LEGACY_ONLY = "idx-legacy-only";
    private static final String ROLLBACK = "idx-rollback";

    /**
     * 생산자 DDL 의 색인 셋({@code ddl.go} 6-1 (b) 블록과 PDT 부분 인덱스 블록). 시험 연결의 스키마 경로 때문에 표 이름에만
     * {@code public.} 을 붙였다.
     */
    private static final String WALL_IDX = "CREATE INDEX IF NOT EXISTS stream_segments_wall_idx "
            + "ON public.stream_segments (stream_id, start_wall_utc DESC)";
    private static final String V2_SEQ_IDX = "CREATE INDEX IF NOT EXISTS stream_segments_v2_seq_idx "
            + "ON public.stream_segments (stream_id, seq) WHERE timing_version = 2";
    private static final String LEGACY_WALL_IDX = "CREATE INDEX IF NOT EXISTS stream_segments_legacy_wall_idx "
            + "ON public.stream_segments (stream_id, start_wall_utc DESC) WHERE timing_version IS NULL";

    /**
     * 조각 {@code i}(1부터)는 위치 {@code (i−1)·4000} · PDT {@code T0 + (i−1)·4초} · 파일명 시각은 PDT 200ms 앞이다.
     * {@code ?} 는 방송 번호 · 첫 seq · 마지막 seq · 판(NULL 이면 판 NULL).
     */
    private static final String SEED_ROWS = """
            INSERT INTO public.stream_segments
                   (stream_id, seq, start_pts_ms, start_wall_utc, duration_ms, s3_key, is_discontinuity,
                    playback_pdt, timing_version)
            SELECT ?, i, (i - 1) * 4000,
                   timestamptz '2026-11-13 00:00:00+00' + make_interval(secs => (i - 1) * 4.0 - 0.2),
                   4000, 'idx/' || i, false,
                   timestamptz '2026-11-13 00:00:00+00' + make_interval(secs => (i - 1) * 4.0),
                   ?::smallint
              FROM generate_series(?::bigint, ?::bigint) AS i
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();

    SegmentLedgerIndexTest(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @BeforeEach
    void 장부_셋을_세운다() {
        StreamSegmentsFixture.ensureTable(jdbc);
        List.of(V2_ONLY, LEGACY_ONLY, ROLLBACK).forEach(streamId -> StreamSegmentsFixture.clear(jdbc, streamId));
        jdbc.update(SEED_ROWS, V2_ONLY, 2, 1, ROWS);
        jdbc.update(SEED_ROWS, LEGACY_ONLY, null, 1, ROWS);
        jdbc.update(SEED_ROWS, ROLLBACK, 2, 1, ROWS);
        jdbc.update(SEED_ROWS, ROLLBACK, null, ROWS + 1, ROWS + ROLLBACK_ROWS);
        jdbc.execute(WALL_IDX);
        jdbc.execute(V2_SEQ_IDX);
        jdbc.execute(LEGACY_WALL_IDX);
        jdbc.execute("ANALYZE public.stream_segments");
    }

    @AfterEach
    void 장부를_지우고_부분_색인을_되살린다() {
        List.of(V2_ONLY, LEGACY_ONLY, ROLLBACK).forEach(streamId -> StreamSegmentsFixture.clear(jdbc, streamId));
        jdbc.execute(V2_SEQ_IDX);
        jdbc.execute(LEGACY_WALL_IDX);
    }

    @Test
    void 부분_색인이_있으면_다른_판_행을_훑지_않는다() {
        Instant tail = T0.plusMillis((long) (ROWS + ROLLBACK_ROWS) * SEGMENT_MS);

        long v2OnV2 = v2Floor(V2_ONLY, tail, "있음");
        long v2OnLegacy = v2Floor(LEGACY_ONLY, tail, "있음");
        long v2OnRollback = v2Floor(ROLLBACK, tail, "있음");
        long legacyOnV2 = legacyFloor(V2_ONLY, tail, "있음");
        long legacyOnLegacy = legacyFloor(LEGACY_ONLY, tail, "있음");
        long legacyOnRollback = legacyFloor(ROLLBACK, tail, "있음");
        // 기록만(보안 L2) — 첫 판 2 PDT 보다 이른 시각이면 판 2 후보가 없어 그 스트림의 판 2 행을 어디까지 훑는지 본다.
        v2Floor(V2_ONLY, T0.minusSeconds(1), "있음 · 첫 PDT − 1초");
        // 기록만 — 꼬리에서 먼 시각(첫 행 + 1초)의 옛 후보 조회는 그 시각 뒤 판 NULL 행 수만큼 읽는다.
        legacyFloor(LEGACY_ONLY, T0.plusSeconds(1), "있음 · 첫 행 + 1초");

        jdbc.execute("DROP INDEX public.stream_segments_v2_seq_idx");
        jdbc.execute("DROP INDEX public.stream_segments_legacy_wall_idx");
        long v2OnLegacyWithout = v2Floor(LEGACY_ONLY, tail, "없음");
        long legacyOnV2Without = legacyFloor(V2_ONLY, tail, "없음");
        v2Floor(V2_ONLY, tail, "없음");
        v2Floor(ROLLBACK, tail, "없음");
        legacyFloor(LEGACY_ONLY, tail, "없음");
        legacyFloor(ROLLBACK, tail, "없음");

        assertThat(List.of(v2OnV2, v2OnLegacy, v2OnRollback, legacyOnV2, legacyOnLegacy, legacyOnRollback))
                .as("색인 있음 — 판 2 후보 · 옛 후보 세 장부 모두 꼬리 몇 행")
                .allMatch(buffers -> buffers <= TAIL_BUFFERS);
        assertThat(List.of(v2OnLegacyWithout, legacyOnV2Without))
                .as("색인 없음 대조 — 다른 판 행을 끝까지 훑는다")
                .allMatch(buffers -> buffers >= FULL_SCAN_BUFFERS);
    }

    private long v2Floor(String streamId, Instant t, String index) {
        return explain("판 2 후보", streamId, index, SegmentLedger.FLOOR_BY_PLAYBACK_PDT, streamId, Timestamp.from(t));
    }

    private long legacyFloor(String streamId, Instant t, String index) {
        Timestamp at = Timestamp.from(t);
        return explain("옛 후보", streamId, index, SegmentLedger.FLOOR_BY_WALL_CLOCK, at, streamId, at);
    }

    /** 운영 SQL 글자 그대로를 {@code EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)}으로 돌려 맨 위 마디의 공유 버퍼 수를 낸다. */
    private long explain(String query, String streamId, String index, String sql, Object... args) {
        String plan = jdbc.queryForObject("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + sql, String.class, args);
        JsonNode top = json.readTree(plan).get(0);
        JsonNode root = top.get("Plan");
        long buffers = root.get("Shared Hit Blocks").asLong() + root.get("Shared Read Blocks").asLong();
        log.info("chat.sync.index_record query={} stream={} partialIndex={} buffers={} executionMs={} plan={}",
                query, streamId, index, buffers, top.get("Execution Time").asDouble(), plan.replaceAll("\\s+", " "));
        return buffers;
    }
}
