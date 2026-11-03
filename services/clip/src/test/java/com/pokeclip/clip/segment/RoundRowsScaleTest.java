package com.pokeclip.clip.segment;

import com.pokeclip.clip.broadcast.Broadcast;
import com.pokeclip.clip.broadcast.BroadcastRepository;
import com.pokeclip.clip.jumpcard.CardPositionReader;
import com.pokeclip.clip.support.IntegrationTestSupport;
import com.pokeclip.clip.support.LedgerSeed;
import com.pokeclip.clip.support.TestIds;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 회차 길이에 비례하는 읽기의 규모(POK-196). 카드 장면 · 마크 위치는 호출마다 회차 행 전부를 읽고, 소리 주소 표지 갈래는 창 밖 회차
 * 행을 거르개로 버린다 — 장부에 {@code start_pts_ms} 색인이 없고 조각 길이 상한이 계약에 없어 범위를 더 좁히지 않았다. 그 비용이
 * <b>키 이력이 아니라 회차 길이</b>에 비례하고, 2만 행 회차(4초 조각 22시간)에서 상한 안인지 잰다.
 *
 * <p>상한(1초)은 판정 경계가 아니라 「회차 길이 비례가 아닌 무언가(키 이력 훑기 · 값마다 왕복)」 를 잡는 그물이다. 실측값은 README
 * 카드 장면 절에 적는다.
 */
class RoundRowsScaleTest extends IntegrationTestSupport {

    private static final String KEY = "s-roundscale";
    private static final String HEAD = "S-scale";
    private static final Instant START = Instant.parse("2026-09-01T00:00:00Z");
    private static final int HISTORY_ROWS = 20_000;
    private static final int ROUND_ROWS = 20_000;
    private static final Duration BOUND = Duration.ofSeconds(1);
    private static final Pattern REMOVED = Pattern.compile("Rows Removed by Filter: (\\d+)");

    private final RoundRows rounds;
    private final ServerClockPosition positions;
    private final CardPositionReader scenes;
    private final StreamSegmentReader segments;
    private final BroadcastRepository broadcasts;
    private final JdbcTemplate jdbc;

    RoundRowsScaleTest(RoundRows rounds, ServerClockPosition positions, CardPositionReader scenes,
                       StreamSegmentReader segments, BroadcastRepository broadcasts, JdbcTemplate jdbc) {
        this.rounds = rounds;
        this.positions = positions;
        this.scenes = scenes;
        this.segments = segments;
        this.broadcasts = broadcasts;
        this.jdbc = jdbc;
    }

    @BeforeEach
    void 이력과_긴_회차를_심는다() {
        방송과_카드를_비운다(jdbc);
        jdbc.execute("CREATE INDEX IF NOT EXISTS stream_segments_session_idx "
                + "ON public.stream_segments (stream_id, session_id, seq)");
        new LedgerSeed(jdbc).session(HEAD, KEY, HEAD, null);
        // 지난 세션 스무 개(각 1,000조각) 뒤 이번 회차(2만 조각)
        jdbc.update("""
                INSERT INTO public.stream_segments (stream_id, seq, start_pts_ms, start_wall_utc, playback_pdt, duration_ms,
                                                    s3_key, upload_state, session_id)
                SELECT ?, g, g * 4000, ?::timestamptz + g * 4000 * interval '1 millisecond',
                       ?::timestamptz + g * 4000 * interval '1 millisecond', 4000, 'k' || g, 'uploaded',
                       CASE WHEN g < ? THEN 'old-' || (g / 1000) ELSE ? END
                  FROM generate_series(0, ? - 1) g""", KEY, Timestamp.from(START), Timestamp.from(START), HISTORY_ROWS,
                HEAD, HISTORY_ROWS + ROUND_ROWS);
        jdbc.execute("ANALYZE public.stream_segments");
        broadcasts.save(Broadcast.startedNow(HEAD, TestIds.STREAMER, KEY, 1L, at(HISTORY_ROWS), null));
    }

    @AfterEach
    void 치운다() {
        jdbc.update("DELETE FROM public.stream_segments WHERE stream_id = ?", KEY);
        jdbc.execute("DROP INDEX IF EXISTS public.stream_segments_session_idx");
    }

    @Test
    void 회차_행은_회차_길이만큼만_읽는다() {
        RoundRows.Round round = rounds.roundOf(방송()).orElseThrow();

        assertThat(rounds.rowsOf(round)).hasSize(ROUND_ROWS);
    }

    /** 목록 한 장(카드 50장 · 값 150개)의 장면과 마크 위치 하나 — 회차 행을 한 번 읽어 메모리에서 푼다. */
    @Test
    void 긴_회차에서도_목록_장면과_마크_위치가_상한_안이다() {
        Broadcast broadcast = 방송();
        List<Long> values = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            values.add(4_000L * HISTORY_ROWS + 500_000L * i);
        }

        long sceneNanos = timed(() -> assertThat(scenes.scenesOf(broadcast, values)).hasSize(150));
        long markNanos = timed(() -> assertThat(positions.ofServerClock(broadcast,
                at(HISTORY_ROWS + ROUND_ROWS - 10), ServerClockPosition.Source.OBS)).isPresent());

        assertThat(Duration.ofNanos(sceneNanos)).isLessThan(BOUND);
        assertThat(Duration.ofNanos(markNanos)).isLessThan(BOUND);
    }

    /**
     * 소리 주소 표지 갈래는 창 밖 회차 행을 거르개로 버린다 — 세션 색인 경로에서 버리는 수가 회차 길이(창 밖 회차 행)이고 키 이력이
     * 아니다. 이 시드는 회차가 키의 절반이라 플래너가 순차 훑기를 고를 수 있어 색인 경로를 강제해 잰다(플래너가 색인을 고르는지는
     * {@code WallAxisReadPlanTest} 가 이력이 긴 시드로 잰다).
     */
    @Test
    void 소리_주소_창_읽기는_창_밖_회차_행만큼_버리고_키_이력은_안_훑는다() {
        RoundRows.Round round = rounds.roundOf(방송()).orElseThrow();
        long fromRow = HISTORY_ROWS + ROUND_ROWS - 50L;
        Timestamp from = Timestamp.from(at(fromRow));
        Timestamp to = Timestamp.from(at(fromRow + 15));

        String text = plan(StreamSegmentReader.WALL_MEMBER_ROWS, from, to);
        long nanos = timed(() -> assertThat(segments.findRoundRowsByWallTime(round, at(fromRow).toEpochMilli(),
                at(fromRow + 15).toEpochMilli())).hasSize(15));

        assertThat(text).as(text).contains("stream_segments_session_idx");
        assertThat(removed(text)).as(text).isBetween((long) ROUND_ROWS - 100, (long) ROUND_ROWS);
        assertThat(Duration.ofNanos(nanos)).isLessThan(BOUND);
    }

    private Broadcast 방송() {
        return broadcasts.findByStreamId(HEAD).orElseThrow();
    }

    /** 이력 · 회차 조각 번호 g 의 파일명 시각. */
    private static Instant at(long g) {
        return START.plusMillis(4_000L * g);
    }

    private static long timed(Runnable work) {
        long started = System.nanoTime();
        work.run();
        return System.nanoTime() - started;
    }

    private static long removed(String plan) {
        Matcher m = REMOVED.matcher(plan);
        long total = 0;
        while (m.find()) {
            total += Long.parseLong(m.group(1));
        }
        return total;
    }

    /** 한 연결에서 순차 훑기를 끄고 실행 계획을 받는다(끝나면 되돌린다). */
    private String plan(String sql, Timestamp from, Timestamp to) {
        return jdbc.execute((ConnectionCallback<String>) con -> {
            List<String> plan = new ArrayList<>();
            try (var off = con.createStatement()) {
                off.execute("SET enable_seqscan = off");
            }
            try (var ps = con.prepareStatement("EXPLAIN (ANALYZE, BUFFERS) " + sql)) {
                ps.setString(1, KEY);
                ps.setArray(2, con.createArrayOf("text", new String[] {HEAD}));
                ps.setTimestamp(3, to);
                ps.setTimestamp(4, from);
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        plan.add(rs.getString(1));
                    }
                }
            } finally {
                try (var reset = con.createStatement()) {
                    reset.execute("RESET enable_seqscan");
                }
            }
            return String.join("\n", plan);
        });
    }
}
