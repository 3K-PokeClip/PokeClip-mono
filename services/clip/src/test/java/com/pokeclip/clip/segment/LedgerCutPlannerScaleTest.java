package com.pokeclip.clip.segment;

import com.pokeclip.clip.broadcast.Broadcast;
import com.pokeclip.clip.broadcast.BroadcastRepository;
import com.pokeclip.clip.segment.CutDecision.Ready;
import com.pokeclip.clip.support.IntegrationTestSupport;
import com.pokeclip.clip.support.LedgerSeed;
import com.pokeclip.clip.support.TestIds;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 렌더 주문 컷 판정의 장부 읽기 규모 · 왕복(POK-196 · 착수 체크리스트 C-B4 · 설계 r7 9.1). 회차 행은 회차 전부를 세션 색인으로 한 번에
 * 읽어 가장자리를 메모리 F 로 고른다 — 그 비용이 <b>키 이력이 아니라 회차 길이</b>에 비례하고, 왕복 수는 회차 길이와 무관한지 잰다.
 *
 * <p>왕복은 {@code JdbcTemplate} 문장을 세는 {@code DataSource} 감싸개로 센다(Hibernate 통계는 이 문장을 세지 않는다 · C5).
 * 경과 상한(5초)은 판정 경계가 아니라 「회차 길이 비례가 아닌 무언가」 를 잡는 성긴 그물이다({@code RoundRowsScaleTest} 와 같은 자세).
 */
class LedgerCutPlannerScaleTest extends IntegrationTestSupport {

    private static final String KEY = "s-cutscale";
    private static final String HEAD = "S-cutscale";
    private static final Instant START = Instant.parse("2026-09-01T00:00:00Z");
    private static final int HISTORY_ROWS = 20_000;
    private static final int ROUND_ROWS = 20_000;
    private static final int SHORT_ROUND_ROWS = 450;
    private static final Duration BOUND = Duration.ofSeconds(5);
    private static final Set<String> STATEMENT_MAKERS = Set.of("prepareStatement", "createStatement", "prepareCall");

    private final BroadcastRepository broadcasts;
    private final JdbcTemplate jdbc;
    private final AtomicInteger statements = new AtomicInteger();
    private final LedgerCutPlanner planner;

    LedgerCutPlannerScaleTest(BroadcastRepository broadcasts, JdbcTemplate jdbc, DataSource dataSource) {
        this.broadcasts = broadcasts;
        this.jdbc = jdbc;
        JdbcTemplate counted = new JdbcTemplate(counting(dataSource, statements));
        this.planner = new LedgerCutPlanner(new RoundRows(counted), new StreamSegmentReader(counted));
    }

    @BeforeEach
    void 색인을_만든다() {
        방송과_카드를_비운다(jdbc);
        jdbc.execute("CREATE INDEX IF NOT EXISTS stream_segments_session_idx "
                + "ON public.stream_segments (stream_id, session_id, seq)");
        new LedgerSeed(jdbc).session(HEAD, KEY, HEAD, null);
    }

    @AfterEach
    void 치운다() {
        jdbc.update("DELETE FROM public.stream_segments WHERE stream_id = ?", KEY);
        jdbc.execute("DROP INDEX IF EXISTS public.stream_segments_session_idx");
        // 시험 시드 표에는 PK 뿐이다 — 여기서 만든 색인이 남으면 뒤 시험의 실행 계획이 달라진다.
        jdbc.execute("DROP INDEX IF EXISTS public.stream_segments_wall_idx");
    }

    /**
     * 판 NULL · 방송 중 · 안 닫힌 회차의 컷 하나 — 회차 고르기 1 · 회차 행 1 · 회차 완결 1 · 확보 후보 1 · 준비 후보(구간 시작 포함) 1 의
     * 다섯 왕복이 회차 길이(30 행 · 2만 행)와 무관하다.
     */
    @Test
    void 왕복_수는_회차_길이와_무관하다() {
        심는다(0, 30);
        int small = 왕복(30);
        jdbc.update("DELETE FROM public.stream_segments WHERE stream_id = ?", KEY);
        심는다(HISTORY_ROWS, ROUND_ROWS);
        int large = 왕복(HISTORY_ROWS + ROUND_ROWS);

        assertThat(small).isEqualTo(5);
        assertThat(large).isEqualTo(small);
    }

    /** 2만 행 회차(4초 조각 22시간)에 이력 2만 행 — 회차 행 읽기는 회차 행만 돌려주고 상한 안이다. */
    @Test
    void 긴_회차에서도_회차_행만_읽고_상한_안이다() {
        심는다(HISTORY_ROWS, ROUND_ROWS);
        RoundRows.Round round = new RoundRows(jdbc).roundOf(방송()).orElseThrow();

        long started = System.nanoTime();
        List<RoundRow> rows = new StreamSegmentReader(jdbc).findRoundRows(round);
        Duration read = Duration.ofNanos(System.nanoTime() - started);

        assertThat(rows).hasSize(ROUND_ROWS);
        assertThat(read).isLessThan(BOUND);
    }

    /**
     * 회차 행 읽기는 세션 색인으로 범위를 자르고, 장부 바로 앞 행 찾기는 행마다 PK 역순 한 행이다 — 순차 훑기가 없다. 이력이 회차보다
     * 훨씬 긴 시드(운영 꼴)로 잰다 — 회차가 키의 절반이면 플래너가 순차 훑기를 고르는 것이 맞다.
     */
    @Test
    void 회차_행_읽기는_세션_색인과_PK_로_읽는다() {
        심는다(HISTORY_ROWS, SHORT_ROUND_ROWS);

        String text = plan(StreamSegmentReader.ROUND_MEMBER_ROWS, ps -> {
            ps.setString(1, KEY);
            ps.setArray(2, ps.getConnection().createArrayOf("text", new String[] {HEAD}));
        });

        assertThat(text).as(text).contains("stream_segments_session_idx").contains("stream_segments_pkey");
        assertThat(text).as(text).doesNotContain("Seq Scan on stream_segments");
    }

    /**
     * 머리 세션 행이 없는 옛 방송의 회차 행(방송 시간창)은 벽시각 색인 범위로 자르고, 장부 바로 앞 행 찾기는 행마다 PK 역순 한
     * 행이다 — 키 이력을 훑지 않는다(c5 지적 3 · 지운 {@code WallAxisReadPlanTest} 의 시간창 몫을 잇는다). 색인은 운영 장부와 같은
     * 모양(media {@code ddl.go} {@code stream_segments_wall_idx})이다.
     */
    @Test
    void 옛_방송_시간창_행_읽기는_벽시각_색인_범위와_PK_로_읽는다() {
        심는다(HISTORY_ROWS, SHORT_ROUND_ROWS);
        jdbc.execute("CREATE INDEX IF NOT EXISTS stream_segments_wall_idx "
                + "ON public.stream_segments (stream_id, start_wall_utc DESC)");
        jdbc.execute("ANALYZE public.stream_segments");

        String text = plan(StreamSegmentReader.ROUND_WINDOW_ROWS, ps -> {
            ps.setString(1, KEY);
            ps.setTimestamp(2, Timestamp.from(at(HISTORY_ROWS).minus(Duration.ofMinutes(2))));
            ps.setTimestamp(3, null);
        });

        assertThat(text).as(text).contains("stream_segments_wall_idx").contains("Index Cond")
                .contains("stream_segments_pkey");
        assertThat(text).as(text).doesNotContain("Seq Scan on stream_segments");
        assertThat(text).as(text).doesNotContain("Rows Removed by Filter: 2");
    }

    /** 준비 후보 읽기는 머리마다 PK 로 한 행을 고른다 — 키 이력을 훑지 않는다. */
    @Test
    void 준비_후보_읽기는_PK_로_한_행씩_읽는다() {
        심는다(HISTORY_ROWS, SHORT_ROUND_ROWS);
        방송();

        String text = plan(StreamSegmentReader.ROWS_BEFORE, ps -> {
            ps.setString(1, HEAD);
            ps.setArray(2, ps.getConnection().createArrayOf("bigint", new Long[] {HISTORY_ROWS + 10L}));
            ps.setString(3, KEY);
            ps.setString(4, KEY);
        });

        assertThat(text).as(text).contains("stream_segments_pkey");
        assertThat(text).as(text).doesNotContain("Seq Scan on stream_segments");
    }

    /** 끝에서 열 번째 회차 행 안 3초 컷의 왕복 수. 방송은 매번 새로 넣는다. */
    private int 왕복(long lastSeq) {
        jdbc.update("DELETE FROM broadcasts WHERE stream_id = ?", HEAD);
        Broadcast broadcast = 방송();
        long in = at(lastSeq - 10).toEpochMilli() + 500;
        statements.set(0);

        long started = System.nanoTime();
        CutDecision decision = planner.plan(broadcast, 1L, in, in + 3_000, List.of(), Instant.now());
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        assertThat(decision).as("양성 대조 — 실제로 계획을 냈다").isInstanceOf(Ready.class);
        assertThat(took).isLessThan(BOUND);
        return statements.get();
    }

    /**
     * 지난 세션 이력 {@code history} 행(각 1,000행) 뒤 이번 회차 {@code round} 행. 조각 g 의 두 시각은 {@link #at}. 통계를 바로 갱신한다
     * — 운영 장부는 자동 분석이 돌고, 막 채운 표의 빈 통계로 잰 계획은 운영 꼴이 아니다.
     */
    private void 심는다(int history, int round) {
        jdbc.update("""
                INSERT INTO public.stream_segments (stream_id, seq, start_pts_ms, start_wall_utc, playback_pdt, duration_ms,
                                                    s3_key, upload_state, session_id)
                SELECT ?, g, g * 4000, ?::timestamptz + g * 4000 * interval '1 millisecond',
                       ?::timestamptz + g * 4000 * interval '1 millisecond', 4000, 'k' || g, 'uploaded',
                       CASE WHEN g < ? THEN 'old-' || (g / 1000) ELSE ? END
                  FROM generate_series(0, ? - 1) g""", KEY, Timestamp.from(START), Timestamp.from(START), history,
                HEAD, history + round);
        jdbc.execute("ANALYZE public.stream_segments");
    }

    private Broadcast 방송() {
        return broadcasts.findByStreamId(HEAD).orElseGet(() -> broadcasts.save(
                Broadcast.startedNow(HEAD, TestIds.STREAMER, KEY, 1L, START, null)));
    }

    private static Instant at(long g) {
        return START.plusMillis(4_000L * g);
    }

    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private String plan(String sql, Binder binder) {
        List<String> plan = new ArrayList<>();
        jdbc.query(con -> {
            var ps = con.prepareStatement("EXPLAIN (ANALYZE, BUFFERS) " + sql);
            binder.bind(ps);
            return ps;
        }, rs -> {
            plan.add(rs.getString(1));
        });
        return String.join("\n", plan);
    }

    /** 연결이 문장을 만들 때마다 센다. */
    private static DataSource counting(DataSource real, AtomicInteger counter) {
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class},
                (proxy, method, args) -> {
                    Object result = invoke(real, method, args);
                    if (result instanceof Connection connection) {
                        return countingConnection(connection, counter);
                    }
                    return result;
                });
    }

    private static Connection countingConnection(Connection real, AtomicInteger counter) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                    if (STATEMENT_MAKERS.contains(method.getName())) {
                        counter.incrementAndGet();
                    }
                    return invoke(real, method, args);
                });
    }

    private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
