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
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 회차 길이에 비례하는 읽기의 규모(POK-196). 카드 장면 · 마크 위치는 호출마다 회차 행 전부를 읽는다 — 장부에 {@code start_pts_ms}
 * 색인이 없어 범위를 더 좁히지 않았다. 그 비용이 <b>키 이력이 아니라 회차 길이</b>에 비례하고, 2만 행 회차(4초 조각 22시간)에서 상한
 * 안인지 잰다.
 *
 * <p>비례성은 경과 시간이 아니라 행 수({@code hasSize})가 잡는다. 경과 상한(5초)은 판정 경계가 아니라 「회차 길이 비례가 아닌
 * 무언가(키 이력 훑기 · 값마다 왕복)」 를 잡는 성긴 그물이다 — 로컬 실측(수십–백수십 ms)보다 한 자릿수 넘게 넉넉히 둬 공유 CI 러너의
 * 흔들림에 깨지지 않게 했다. 실측값은 README 카드 장면 절에 적는다.
 */
class RoundRowsScaleTest extends IntegrationTestSupport {

    private static final String KEY = "s-roundscale";
    private static final String HEAD = "S-scale";
    private static final Instant START = Instant.parse("2026-09-01T00:00:00Z");
    private static final int HISTORY_ROWS = 20_000;
    private static final int ROUND_ROWS = 20_000;
    private static final Duration BOUND = Duration.ofSeconds(5);

    private final RoundRows rounds;
    private final ServerClockPosition positions;
    private final CardPositionReader scenes;
    private final BroadcastRepository broadcasts;
    private final JdbcTemplate jdbc;

    RoundRowsScaleTest(RoundRows rounds, ServerClockPosition positions, CardPositionReader scenes,
                       BroadcastRepository broadcasts, JdbcTemplate jdbc) {
        this.rounds = rounds;
        this.positions = positions;
        this.scenes = scenes;
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
}
