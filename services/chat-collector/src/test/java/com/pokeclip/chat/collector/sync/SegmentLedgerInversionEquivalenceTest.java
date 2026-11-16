package com.pokeclip.chat.collector.sync;

import com.pokeclip.chat.collector.support.IntegrationTestSupport;
import com.pokeclip.chat.collector.support.StreamSegmentsFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 옛 후보 조회의 역행 신호를 색인을 타는 꼴로 고쳐 쓴 것이 <b>같은 뜻</b>인지 잰다 — 같은 장부 · 같은 시각에서 제품 조회
 * ({@link SegmentLedger#floorByWallClock})와 고치기 전 조회({@link #BEFORE_REWRITE} — {@code earlier_is_future}가
 * {@code EXISTS(y.seq < s.seq AND y.start_wall_utc > s.start_wall_utc)} 꼴)가 floor 행 · {@code max_candidate_seq} ·
 * {@code earlier_is_future} 셋 모두 같아야 한다.
 *
 * <p>장부는 판 NULL 행의 절반 가까이를 크게 앞뒤로 튀게 만들어 두 신호가 자주 켜지게 하고, 판 2 행을 섞어 판 조건이 두 조회에
 * 같이 걸리는지도 본다. 씨앗 고정 · 장부 250개 × 시각 16개 = 4,000번이다.
 *
 * <p><b>방송 번호 접두는 {@code eqv-}다.</b>
 */
@SpringBootTest
@ActiveProfiles("test")
class SegmentLedgerInversionEquivalenceTest extends IntegrationTestSupport {

    /** 고치기 전 조회(커밋 50981b8a 의 {@code FLOOR_BY_WALL_CLOCK}) — 글자 그대로. 대조 전용이다. */
    private static final String BEFORE_REWRITE = """
            SELECT s.seq, s.start_pts_ms, s.start_wall_utc, s.playback_pdt, s.timing_version,
                   s.duration_ms, s.is_discontinuity,
                   (SELECT max(x.seq) FROM public.stream_segments x
                     WHERE x.stream_id = s.stream_id AND x.timing_version IS NULL
                       AND x.start_wall_utc <= ?) AS max_candidate_seq,
                   EXISTS(SELECT 1 FROM public.stream_segments y
                           WHERE y.stream_id = s.stream_id AND y.timing_version IS NULL
                             AND y.seq < s.seq AND y.start_wall_utc > s.start_wall_utc) AS earlier_is_future
              FROM public.stream_segments s
             WHERE s.stream_id = ? AND s.timing_version IS NULL AND s.start_wall_utc <= ?
             ORDER BY s.start_wall_utc DESC, s.seq DESC
             LIMIT 1
            """;

    private static final Instant T0 = Instant.parse("2026-11-13T00:00:00Z");
    private static final long SEED = 20261114L;
    private static final int LEDGERS = 250;
    private static final int TIMES_PER_LEDGER = 16;

    private final JdbcTemplate jdbc;
    private final SegmentLedger ledger;
    private final List<String> streams = new ArrayList<>();

    SegmentLedgerInversionEquivalenceTest(SegmentLedger ledger, JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.ledger = ledger;
    }

    @AfterEach
    void 내_방송_행을_비운다() {
        streams.forEach(streamId -> StreamSegmentsFixture.clear(jdbc, streamId));
    }

    /** 비교하는 셋 — floor 행 seq · 같은 후보 집합의 최대 seq · 앞 조각이 미래인가. floor 가 없으면 {@code null}. */
    private record Signals(long floorSeq, long maxCandidateSeq, boolean earlierIsFuture) {
    }

    @Test
    void 고쳐_쓴_역행_신호가_고치기_전_조회와_같은_답을_낸다() {
        StreamSegmentsFixture.ensureTable(jdbc);
        Random random = new Random(SEED);
        int compared = 0;
        int earlierIsFuture = 0;
        int maxCandidateDiffers = 0;

        for (int i = 0; i < LEDGERS; i++) {
            String streamId = "eqv-" + i;
            streams.add(streamId);
            StreamSegmentsFixture.clear(jdbc, streamId);
            long end = seedLedger(random, streamId);
            for (int j = 0; j < TIMES_PER_LEDGER; j++) {
                Instant t = T0.plusMillis(-20_000 + random.nextInt((int) end + 40_000));
                Signals actual = ledger.floorByWallClock(streamId, t)
                        .map(f -> new Signals(f.segment().seq(), f.maxCandidateSeq(), f.earlierIsFuture()))
                        .orElse(null);

                assertThat(actual).as("방송 %s · 시각 %s", streamId, t).isEqualTo(beforeRewrite(streamId, t));
                compared++;
                if (actual != null) {
                    earlierIsFuture += actual.earlierIsFuture() ? 1 : 0;
                    maxCandidateDiffers += actual.maxCandidateSeq() != actual.floorSeq() ? 1 : 0;
                }
            }
        }

        // 신호가 거의 안 켜지면 비교가 공짜로 초록이다 — 두 신호가 실제로 켜졌는지 본다.
        assertThat(compared).isEqualTo(LEDGERS * TIMES_PER_LEDGER);
        assertThat(earlierIsFuture).as("earlier_is_future 가 참인 비교 수").isGreaterThan(compared / 20);
        assertThat(maxCandidateDiffers).as("max_candidate_seq ≠ floor 인 비교 수").isGreaterThan(compared / 10);
    }

    private Signals beforeRewrite(String streamId, Instant t) {
        Timestamp at = Timestamp.from(t);
        List<Signals> found = jdbc.query(BEFORE_REWRITE, (row, n) -> new Signals(row.getLong("seq"),
                row.getLong("max_candidate_seq"), row.getBoolean("earlier_is_future")), at, streamId, at);
        return found.stream().findFirst().orElse(null);
    }

    /**
     * 판 NULL 행 열(가끔 판 2 행이 끼고 seq 빈 번호가 있다). 판 NULL 행의 파일명 시각은 공통 시계에서 둘에 하나꼴로 크게
     * 앞뒤(−15초 · +15초 안)로 튄다. 같은 시각 두 행(동률)도 나온다. 끝 시각을 돌려준다.
     */
    private long seedLedger(Random random, String streamId) {
        int count = 3 + random.nextInt(14);
        long seq = 1 + random.nextInt(20);
        long clock = 0;
        long lastWall = 0;
        long end = 0;
        for (int k = 0; k < count; k++) {
            int dur = 3000 + random.nextInt(1501);
            boolean v2 = random.nextInt(5) == 0;
            long wall = random.nextInt(10) == 0 ? lastWall
                    : random.nextBoolean() ? clock + random.nextInt(30_001) - 15_000 : clock;
            Optional<Instant> pdt = v2 ? Optional.of(T0.plusMillis(wall + random.nextInt(400))) : Optional.empty();
            StreamSegmentsFixture.insert(jdbc, streamId, seq, k * 4000L, T0.plusMillis(wall), pdt.orElse(null),
                    v2 ? 2 : null, dur, random.nextInt(4) == 0);
            lastWall = wall;
            end = Math.max(end, wall + dur);
            seq += random.nextInt(6) == 0 ? 2 : 1;
            clock += dur;
        }
        return end;
    }
}
