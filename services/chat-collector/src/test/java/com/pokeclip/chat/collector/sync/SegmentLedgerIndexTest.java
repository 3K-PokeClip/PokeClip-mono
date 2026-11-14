package com.pokeclip.chat.collector.sync;

import com.pokeclip.chat.collector.support.IntegrationTestSupport;
import com.pokeclip.chat.collector.support.StreamSegmentsFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.Statement;
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
 * <p>판 2 후보 조회를 첫 판 2 PDT보다 이른 시각으로 부르는 경우(보안 L2)도 꼬리 몇 행이다 — 조회가 먼저 「PDT ≤ t 인 판 2
 * 행이 있나」 를 판 2 PDT 색인({@code stream_segments_v2_pdt_idx} — 생산자 정의 · 시험에서만 만든다)으로 묻고,
 * 없으면 seq 역순 훑기를 하지 않는다. 그 색인이 없던 조회는 그 스트림의 판 2 행을 끝까지 훑었다(2026-11-13 실측 · 판 2
 * 30만 행에서 7,246 버퍼). 색인이 없으면 답은 같고 느리다(아래 「없음」 기록).
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
     * 판 2 PDT 색인 — <b>생산자 정의</b>(생산자 PR 510 · develop 머지 커밋 fc7dd91c(2026-11-13) · prod 적용은 판 2 쓰기 스위치
     * 전 · POK-551 운영 SQL 과 한 재실행 · kty 2026-11-13 L2 (가)). 제품 DDL 은 {@code media/internal/index/ddl.go}(생산자
     * PR 510 · develop 머지 fc7dd91c)에 있고, 이 시험은 시험 DB 에 스스로 만든다. 생산자 꼴(이름이 없을 때만 만드는
     * {@code DO} 블록 — {@code ddl.go} 의 PDT 부분 인덱스 블록)
     * 그대로 옮겼고, 시험 연결의 스키마 경로 때문에 이름 · 표에만 {@code public.} 을 붙였다. 이 카드는 제품 DDL 을 만들지
     * 않는다.
     */
    private static final String V2_PDT_IDX = """
            DO $do$
            BEGIN
                IF pg_catalog.to_regclass('public.stream_segments_v2_pdt_idx') IS NULL THEN
                    CREATE INDEX stream_segments_v2_pdt_idx ON public.stream_segments (stream_id, playback_pdt DESC)
                        WHERE timing_version = 2;
                END IF;
            END $do$
            """;

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
        jdbc.execute(V2_PDT_IDX);
        jdbc.execute("ANALYZE public.stream_segments");
    }

    @AfterEach
    void 장부를_지우고_부분_색인을_되살린다() {
        List.of(V2_ONLY, LEGACY_ONLY, ROLLBACK).forEach(streamId -> StreamSegmentsFixture.clear(jdbc, streamId));
        jdbc.execute(V2_SEQ_IDX);
        jdbc.execute(LEGACY_WALL_IDX);
        jdbc.execute(V2_PDT_IDX);
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
        // 보안 L2 — 첫 판 2 PDT 보다 이른 시각이면 판 2 후보가 없다. 판 2 PDT 색인으로 「없음」 을 꼬리 몇 행에 안다.
        long v2Early = v2Floor(V2_ONLY, T0.minusSeconds(1), "있음 · 첫 PDT − 1초");
        // 앱의 이 조회는 서버 준비 문장을 쓰지 않는다(kty 2026-11-13 L3 (나) · SegmentLedgerPrepareTest). 아래 일반 계획(t 값을
        // 모르는 계획) 측정은 그 문턱이 빠지거나 바뀔 때를 대비한 대조다 — 앞세운 존재 확인 덕에 일반 계획이어도 이른 시각 ·
        // 꼬리는 꼬리 몇 행이어야 한다(확인이 없으면 이른 시각에 seq 역순으로 끝까지 훑는다).
        long v2EarlyGeneric = v2FloorGeneric(V2_ONLY, T0.minusSeconds(1), "있음 · 일반 계획 · 첫 PDT − 1초");
        long v2TailGeneric = v2FloorGeneric(V2_ONLY, tail, "있음 · 일반 계획");
        // 기록만 — 중간 시각(답이 있고 그 뒤 판 2 행이 15만). 본 조회가 seq 역순으로 그 행들을 지난다 — kty 2026-11-13 중간 t
        // 결정으로 수용(호출자는 카드당 1회 · 시각은 보통 라이브 머리). 생산자 리뷰 실측 30만 행 5,923 버퍼 · 약 32ms 와 견준다.
        v2Floor(V2_ONLY, T0.plusMillis((long) (ROWS / 2) * SEGMENT_MS), "있음 · 중간 시각");
        // 기록만(보안 L3) — 첫 판 2 PDT 바로 뒤. 앱 경로는 서버 준비 문장을 쓰지 않아(kty 2026-11-13 L3 (나)) 이 사용자 지정
        // 계획이다(8 버퍼).
        v2Floor(V2_ONLY, T0.plusMillis(1), "있음 · 첫 PDT + 1ms");
        // 일반 계획 대조 — 답이 첫 행이라 seq 역순 본 조회가 그 스트림 판 2 행 거의 전부를 지난다(7,249 버퍼 · 51–141ms(5회)).
        v2FloorGeneric(V2_ONLY, T0.plusMillis(1), "있음 · 일반 계획 · 첫 PDT + 1ms");
        // 기록만 — 꼬리에서 먼 시각(첫 행 + 1초)의 옛 후보 조회는 그 시각 뒤 판 NULL 행 수만큼 읽는다.
        legacyFloor(LEGACY_ONLY, T0.plusSeconds(1), "있음 · 첫 행 + 1초");

        // 기록만 — 판 2 PDT 색인만 없을 때(생산자 적용 전)의 이른 시각. 답은 같고 느리다.
        jdbc.execute("DROP INDEX public.stream_segments_v2_pdt_idx");
        v2Floor(V2_ONLY, T0.minusSeconds(1), "판 2 PDT 색인만 없음 · 첫 PDT − 1초");
        v2Floor(V2_ONLY, tail, "판 2 PDT 색인만 없음");
        v2Floor(LEGACY_ONLY, tail, "판 2 PDT 색인만 없음");

        jdbc.execute("DROP INDEX public.stream_segments_v2_seq_idx");
        jdbc.execute("DROP INDEX public.stream_segments_legacy_wall_idx");
        long v2OnLegacyWithout = v2Floor(LEGACY_ONLY, tail, "없음");
        long legacyOnV2Without = legacyFloor(V2_ONLY, tail, "없음");
        v2Floor(V2_ONLY, tail, "없음");
        v2Floor(ROLLBACK, tail, "없음");
        legacyFloor(LEGACY_ONLY, tail, "없음");
        legacyFloor(ROLLBACK, tail, "없음");

        assertThat(List.of(v2OnV2, v2OnLegacy, v2OnRollback, legacyOnV2, legacyOnLegacy, legacyOnRollback, v2Early,
                v2EarlyGeneric, v2TailGeneric))
                .as("색인 있음 — 판 2 후보 · 옛 후보 세 장부와 이른 시각 판 2 후보(일반 계획 포함) 모두 꼬리 몇 행")
                .allMatch(buffers -> buffers <= TAIL_BUFFERS);
        assertThat(List.of(v2OnLegacyWithout, legacyOnV2Without))
                .as("색인 없음 대조 — 다른 판 행을 끝까지 훑는다")
                .allMatch(buffers -> buffers >= FULL_SCAN_BUFFERS);

        // 기록만 — P2 쓰기 스위치 전 prod 꼴(표에 판 2 행이 없고 판 2 PDT 색인도 아직 없음)의 판 2 후보 조회.
        jdbc.execute(V2_SEQ_IDX);
        jdbc.execute(LEGACY_WALL_IDX);
        List.of(V2_ONLY, ROLLBACK).forEach(streamId -> StreamSegmentsFixture.clear(jdbc, streamId));
        jdbc.execute("ANALYZE public.stream_segments");
        v2Floor(LEGACY_ONLY, tail, "판 2 행 없는 표 · 판 2 PDT 색인만 없음");
        v2FloorGeneric(LEGACY_ONLY, tail, "판 2 행 없는 표 · 판 2 PDT 색인만 없음 · 일반 계획");
    }

    /** 판 2 후보 조회의 자리 표시는 (방송 번호, 시각) 짝의 되풀이다. */
    private long v2Floor(String streamId, Instant t, String index) {
        return explain("판 2 후보", streamId, index, SegmentLedger.FLOOR_BY_PLAYBACK_PDT, v2Args(streamId, t));
    }

    private static Object[] v2Args(String streamId, Instant t) {
        int pairs = (int) SegmentLedger.FLOOR_BY_PLAYBACK_PDT.chars().filter(c -> c == '?').count() / 2;
        Object[] args = new Object[pairs * 2];
        for (int i = 0; i < pairs; i++) {
            args[2 * i] = streamId;
            args[2 * i + 1] = Timestamp.from(t);
        }
        return args;
    }

    /**
     * 같은 조회를 <b>일반 계획</b>으로 잰다 — 한 연결에서 {@code plan_cache_mode = force_generic_plan} 으로 {@code PREPARE} 한 뒤
     * {@code EXPLAIN EXECUTE}. 자리 표시 {@code ?} 는 차례로 {@code $1, $2, …} 로 바꾸고, 방송 번호는 {@code text} · 시각은
     * {@code timestamptz} 로 선언한다(시험 값은 고정 문자열이라 따옴표만 감싼다).
     */
    private long v2FloorGeneric(String streamId, Instant t, String index) {
        Object[] args = v2Args(streamId, t);
        StringBuilder sql = new StringBuilder();
        int n = 0;
        for (char c : SegmentLedger.FLOOR_BY_PLAYBACK_PDT.toCharArray()) {
            sql.append(c == '?' ? "$" + ++n : String.valueOf(c));
        }
        StringBuilder types = new StringBuilder();
        StringBuilder values = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            types.append(i == 0 ? "" : ", ").append(args[i] instanceof String ? "text" : "timestamptz");
            Object value = args[i] instanceof Timestamp ts ? ts.toInstant().toString() : args[i];
            values.append(i == 0 ? "" : ", ").append('\'').append(value).append('\'');
        }
        // 연결은 풀로 돌아간다 — 실패해도 일반 계획 강제 · 이 준비 문장이 남지 않게 finally 에서 늘 되돌린다. DEALLOCATE ALL 은
        // 쓰지 않는다 — 드라이버가 그 연결에 둔 서버 준비 문장까지 지운다. DEALLOCATE 가 실패해도 RESET 은 따로 시도하고, RESET
        // 까지 실패하면 예외를 그대로 던져 시험 실패로 드러낸다(시험 DB 안 일이라 연결 폐기 처리는 두지 않는다).
        String plan = jdbc.execute((ConnectionCallback<String>) con -> {
            try (Statement st = con.createStatement()) {
                boolean prepared = false;
                try {
                    st.execute("SET plan_cache_mode = force_generic_plan");
                    st.execute("PREPARE v2_floor_generic(" + types + ") AS " + sql);
                    prepared = true;
                    try (ResultSet rs = st.executeQuery(
                            "EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) EXECUTE v2_floor_generic(" + values + ")")) {
                        rs.next();
                        return rs.getString(1);
                    }
                } finally {
                    try {
                        if (prepared) {
                            st.execute("DEALLOCATE v2_floor_generic");
                        }
                    } finally {
                        st.execute("RESET plan_cache_mode");
                    }
                }
            }
        });
        return record("판 2 후보", streamId, index, plan);
    }

    private long legacyFloor(String streamId, Instant t, String index) {
        Timestamp at = Timestamp.from(t);
        return explain("옛 후보", streamId, index, SegmentLedger.FLOOR_BY_WALL_CLOCK, at, streamId, at);
    }

    /** 운영 SQL 글자 그대로를 {@code EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)}으로 돌려 맨 위 마디의 공유 버퍼 수를 낸다. */
    private long explain(String query, String streamId, String index, String sql, Object... args) {
        String plan = jdbc.queryForObject("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + sql, String.class, args);
        return record(query, streamId, index, plan);
    }

    private long record(String query, String streamId, String index, String plan) {
        JsonNode top = json.readTree(plan).get(0);
        JsonNode root = top.get("Plan");
        long buffers = root.get("Shared Hit Blocks").asLong() + root.get("Shared Read Blocks").asLong();
        log.info("chat.sync.index_record query={} stream={} partialIndex={} buffers={} executionMs={} plan={}",
                query, streamId, index, buffers, top.get("Execution Time").asDouble(), plan.replaceAll("\\s+", " "));
        return buffers;
    }
}
