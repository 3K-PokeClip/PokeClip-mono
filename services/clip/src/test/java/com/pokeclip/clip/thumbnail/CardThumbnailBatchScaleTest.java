package com.pokeclip.clip.thumbnail;

import com.pokeclip.clip.broadcast.Broadcast;
import com.pokeclip.clip.broadcast.BroadcastRepository;
import com.pokeclip.clip.jumpcard.CardPositionReader;
import com.pokeclip.clip.segment.RoundRows;
import com.pokeclip.clip.segment.RoundRows.Round;
import com.pokeclip.clip.segment.RoundRows.Row;
import com.pokeclip.clip.support.IntegrationTestSupport;
import com.pokeclip.clip.support.LedgerSeed;
import com.pokeclip.clip.support.TestIds;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 카드 사진 순회 한 묶음의 회차 행 읽기 규모(POK-196 · c2 지적 2 · 종결 r1 cc 1). 순회는 후보 카드 50장의 방송마다 회차 행을
 * <b>전부</b> 읽는다({@code RoundRows.BATCH_ROWS}) — 카드 장면이 카드 장면 함수의 메모리 F 라서다. 방송 50개 × 회차 1만 행(4초 조각
 * 11시간)이면 한 번에 50만 행이다. 그 행 수와 경과를 1분 순회({@code cards}) · 2초 새 카드 순회({@code cardsIn}) 둘 다 잰다.
 *
 * <p>행 수는 정확히 단언한다(회차 길이 × 방송 수 — 줄이면 최적화가 들어온 것이다). 경과 상한은 판정 경계가 아니라 성긴 그물이다 —
 * 로컬 실측보다 넉넉히 둬 공유 러너 흔들림에 깨지지 않게 했다 — 한가할 때 0.7–2.6초, 같은 기계에서 다른 부하 시험이 돌 때(부하
 * 평균 100–190) 12–16.6초를 쟀다(2026-11-13). 상한 15초는 그 부하에서 깨져 60초로 올렸다. 최적화는 이 PR 밖이다(넘치면 보고만).
 */
class CardThumbnailBatchScaleTest extends IntegrationTestSupport {

    private static final Logger log = LoggerFactory.getLogger(CardThumbnailBatchScaleTest.class);
    private static final Instant START = Instant.parse("2026-09-01T00:00:00Z");
    private static final int BROADCASTS = ThumbnailSweeper.BATCH_LIMIT;
    private static final int ROUND_ROWS = 10_000;
    private static final Duration BOUND = Duration.ofSeconds(60);

    private final JdbcTemplate jdbc;
    private final BroadcastRepository broadcasts;
    private final AtomicLong rowsRead = new AtomicLong();
    private final ThumbnailTargets targets;
    private final List<Long> cardIds = new ArrayList<>();

    CardThumbnailBatchScaleTest(JdbcTemplate jdbc, BroadcastRepository broadcasts) {
        this.jdbc = jdbc;
        this.broadcasts = broadcasts;
        RoundRows measured = new RoundRows(jdbc) {
            @Override
            public Map<String, List<Row>> rowsOf(Map<String, Round> rounds) {
                Map<String, List<Row>> read = super.rowsOf(rounds);
                read.values().forEach(rows -> rowsRead.addAndGet(rows.size()));
                return read;
            }
        };
        this.targets = new ThumbnailTargets(jdbc, broadcasts, measured, new CardPositionReader(measured));
    }

    @BeforeEach
    void 긴_회차_방송_여럿을_심는다() {
        방송과_카드를_비운다(jdbc);
        jdbc.execute("CREATE INDEX IF NOT EXISTS stream_segments_session_idx "
                + "ON public.stream_segments (stream_id, session_id, seq)");
        LedgerSeed seed = new LedgerSeed(jdbc);
        for (int b = 0; b < BROADCASTS; b++) {
            seed.session(head(b), key(b), head(b), null);
            broadcasts.save(Broadcast.startedNow(head(b), TestIds.STREAMER, key(b), 1L, START, null));
            // 방송마다 한 문장 — 50만 행을 한 문장에 넣으면 공유 러너에서 연결 읽기 시한(socketTimeout 10초)을 넘었다
            jdbc.update("""
                    INSERT INTO public.stream_segments (stream_id, seq, start_pts_ms, start_wall_utc, playback_pdt,
                                                        duration_ms, s3_key, upload_state, session_id)
                    SELECT ?, g, g * 4000, ?::timestamptz + g * 4000 * interval '1 millisecond',
                           ?::timestamptz + g * 4000 * interval '1 millisecond', 4000, ? || '-' || g, 'uploaded', ?
                      FROM generate_series(0, ? - 1) g""",
                    key(b), Timestamp.from(START), Timestamp.from(START), "k" + b, head(b), ROUND_ROWS);
        }
        jdbc.execute("ANALYZE public.stream_segments");
        for (int b = 0; b < BROADCASTS; b++) {
            cardIds.add(카드(head(b), (ROUND_ROWS / 2) * 4_000L + 1_000));
        }
    }

    @AfterEach
    void 치운다() {
        jdbc.update("DELETE FROM public.stream_segments WHERE stream_id LIKE 'SC-%'");
        jdbc.execute("DROP INDEX IF EXISTS public.stream_segments_session_idx");
    }

    @Test
    void 일분_순회_한_묶음은_방송마다_회차_행_전부를_읽는다() {
        Duration took = measure("card", () -> targets.cards(ThumbnailSweeper.MAX_ATTEMPTS, Instant.now(),
                ThumbnailSweeper.BATCH_LIMIT));

        assertThat(rowsRead.get()).isEqualTo((long) BROADCASTS * ROUND_ROWS);
        assertThat(took).isLessThan(BOUND);
    }

    @Test
    void 새_카드_순회_한_묶음도_방송마다_회차_행_전부를_읽는다() {
        Duration took = measure("new_card", () -> targets.cardsIn(cardIds, ThumbnailSweeper.MAX_ATTEMPTS,
                Instant.now(), ThumbnailSweeper.BATCH_LIMIT));

        assertThat(rowsRead.get()).isEqualTo((long) BROADCASTS * ROUND_ROWS);
        assertThat(took).isLessThan(BOUND);
    }

    /** 한 번 데우고(첫 실행 계획 · 연결) 다시 재며, 둘째 실행의 행 수와 두 경과를 남긴다. 주문 수가 방송 수인지도 본다. */
    private Duration measure(String kind, Supplier<ThumbnailTargets.CardBatch> run) {
        long first = timed(run);
        rowsRead.set(0);
        long started = System.nanoTime();
        ThumbnailTargets.CardBatch batch = run.get();
        Duration took = Duration.ofNanos(System.nanoTime() - started);
        assertThat(batch.orders()).as("양성 대조 — 방송마다 주문을 냈다").hasSize(BROADCASTS);
        log.info("thumbnail.scale kind={} broadcasts={} roundRows={} rowsRead={} firstMs={} secondMs={}", kind,
                BROADCASTS, ROUND_ROWS, rowsRead.get(), first, took.toMillis());
        return took;
    }

    private static long timed(Supplier<ThumbnailTargets.CardBatch> run) {
        long started = System.nanoTime();
        run.get();
        return Duration.ofNanos(System.nanoTime() - started).toMillis();
    }

    private static String head(int b) {
        return "S-sc-" + b;
    }

    private static String key(int b) {
        return "SC-" + b;
    }

    private long 카드(String streamId, long ts) {
        return jdbc.queryForObject("""
                        INSERT INTO jump_cards (stream_id, source, event_id, stream_timestamp_ms, window_start_ms,
                                                window_end_ms, score, event_seq)
                        VALUES (?, 'auto', ?, ?, ?, ?, 50, 0)
                        RETURNING id""",
                Long.class, streamId, "evt-" + streamId + "-" + ts, ts, ts - 500, ts + 500);
    }
}
