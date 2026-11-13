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
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 판 2 후보 조회에 「PDT ≤ t 인 판 2 행이 있나」 확인을 앞세운 것이 <b>같은 답</b>인지 잰다(보안 L2 · kty 2026-11-13 (가)). 같은
 * 장부 · 같은 시각에서 제품 조회({@link SegmentLedger#floorByPlaybackPdt})와 앞세우기 전 조회({@link #BEFORE_PRECHECK})가
 * 같은 행(seq · 위치)을 고르거나 둘 다 빈손이어야 한다.
 *
 * <p>장부는 판 2 PDT 가 단조가 아니게 만든다 — 회차 리셋(PDT 가 크게 뒤로) · 1초 미만 겹침 · 판 NULL 행 끼임(엉뚱한
 * {@code playback_pdt} 를 실어 판 조건이 빠지면 갈리게) · R17-4 꼴(앞 판 2 회차 PDT 가 뒤 판 2 회차보다 늦음). 시각은 넷에
 * 하나꼴로 어느 판 2 행의 PDT 와 정확히 같게, 넷에 하나꼴로 모든 행보다 이르게 고른다. 씨앗 고정 · 장부 250개 × 시각 16개 =
 * 4,000번이다. 판 2 PDT 색인은 이 시험이 만들지 않는다 — 색인과 무관하게 답이 같아야 하므로 색인 없는 표에서 잰다.
 *
 * <p><b>방송 번호 접두는 {@code pdteqv-}다.</b>
 */
@SpringBootTest
@ActiveProfiles("test")
class SegmentLedgerPdtFloorEquivalenceTest extends IntegrationTestSupport {

    /** 앞세우기 전 조회(커밋 6edbed3b 의 {@code FLOOR_BY_PLAYBACK_PDT}) — 글자 그대로. 대조 전용이다. */
    private static final String BEFORE_PRECHECK = """
            SELECT seq, start_pts_ms, start_wall_utc, playback_pdt, timing_version, duration_ms, is_discontinuity
              FROM public.stream_segments
             WHERE stream_id = ? AND timing_version = 2 AND playback_pdt <= ?
             ORDER BY seq DESC
             LIMIT 1
            """;

    private static final Instant T0 = Instant.parse("2026-11-13T00:00:00Z");
    private static final long SEED = 20261115L;
    private static final int LEDGERS = 250;
    private static final int TIMES_PER_LEDGER = 16;

    private final JdbcTemplate jdbc;
    private final SegmentLedger ledger;
    private final List<String> streams = new ArrayList<>();

    SegmentLedgerPdtFloorEquivalenceTest(SegmentLedger ledger, JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.ledger = ledger;
    }

    @AfterEach
    void 내_방송_행을_비운다() {
        streams.forEach(streamId -> StreamSegmentsFixture.clear(jdbc, streamId));
    }

    /** 비교하는 값 — 고른 행의 seq · 위치. 빈손이면 {@code null}. */
    private record Pick(long seq, long startPtsMs) {
    }

    @Test
    void 존재_확인을_앞세운_판_2_후보_조회가_앞세우기_전과_같은_답을_낸다() {
        StreamSegmentsFixture.ensureTable(jdbc);
        Random random = new Random(SEED);
        int compared = 0;
        int found = 0;
        int empty = 0;
        int exactPdt = 0;

        for (int i = 0; i < LEDGERS; i++) {
            String streamId = "pdteqv-" + i;
            streams.add(streamId);
            StreamSegmentsFixture.clear(jdbc, streamId);
            List<Long> v2Pdts = seedLedger(random, streamId);
            long end = v2Pdts.stream().mapToLong(Long::longValue).max().orElse(0) + 5000;
            for (int j = 0; j < TIMES_PER_LEDGER; j++) {
                long t = pickTime(random, v2Pdts, end);
                exactPdt += v2Pdts.contains(t) ? 1 : 0;
                Instant at = T0.plusMillis(t);
                Pick actual = ledger.floorByPlaybackPdt(streamId, at)
                        .map(s -> new Pick(s.seq(), s.startPtsMs())).orElse(null);

                assertThat(actual).as("방송 %s · 시각 %d", streamId, t).isEqualTo(beforePrecheck(streamId, at));
                compared++;
                found += actual != null ? 1 : 0;
                empty += actual == null ? 1 : 0;
            }
        }

        // 한쪽 갈래만 나오면 비교가 공짜로 초록이다 — 찾음 · 빈손 · PDT 와 같은 시각이 고루 나왔는지 본다.
        assertThat(compared).isEqualTo(LEDGERS * TIMES_PER_LEDGER);
        assertThat(found).as("찾은 비교 수").isGreaterThan(compared / 4);
        assertThat(empty).as("빈손 비교 수").isGreaterThan(compared / 10);
        assertThat(exactPdt).as("시각이 어느 판 2 PDT 와 같은 비교 수").isGreaterThan(compared / 10);
    }

    private Pick beforePrecheck(String streamId, Instant t) {
        List<Pick> found = jdbc.query(BEFORE_PRECHECK,
                (row, n) -> new Pick(row.getLong("seq"), row.getLong("start_pts_ms")), streamId, Timestamp.from(t));
        return found.stream().findFirst().orElse(null);
    }

    /** 넷에 하나는 어느 판 2 PDT 그대로, 넷에 하나는 모든 행보다 이르게, 나머지는 범위 안 아무 시각. */
    private static long pickTime(Random random, List<Long> v2Pdts, long end) {
        int kind = random.nextInt(4);
        if (kind == 0 && !v2Pdts.isEmpty()) {
            return v2Pdts.get(random.nextInt(v2Pdts.size()));
        }
        if (kind == 1) {
            return -60_000 - random.nextInt(10_000);
        }
        return -20_000 + random.nextInt((int) end + 25_000);
    }

    /**
     * 판 2 · 판 NULL 이 섞인 행 열. 판 2 PDT 는 공통 시계를 따라가되 가끔 1초 미만 겹치고(−999ms 안), 가끔 회차가 바뀌며 크게
     * 뒤로 간다(R17-4 꼴 — 앞 회차 PDT 가 뒤 회차보다 늦다). 판 NULL 행에는 엉뚱한 {@code playback_pdt} 를 싣는다. 판 2 PDT
     * 목록을 돌려준다.
     */
    private List<Long> seedLedger(Random random, String streamId) {
        int count = 3 + random.nextInt(14);
        long seq = 1 + random.nextInt(20);
        long clock = random.nextInt(3000);
        List<Long> v2Pdts = new ArrayList<>();
        for (int k = 0; k < count; k++) {
            int dur = 3000 + random.nextInt(1501);
            boolean v2 = random.nextInt(4) != 0;
            long pdt = random.nextInt(6) == 0 ? clock - 5000 - random.nextInt(15_000)
                    : random.nextInt(4) == 0 ? clock - random.nextInt(1000) : clock;
            Instant wall = T0.plusMillis(pdt - random.nextInt(400));
            if (v2) {
                StreamSegmentsFixture.insertV2(jdbc, streamId, seq, k * 4000L, wall, T0.plusMillis(pdt), dur,
                        random.nextInt(4) == 0);
                v2Pdts.add(pdt);
            } else {
                Instant junk = random.nextBoolean() ? null : T0.plusMillis(pdt - 30_000 + random.nextInt(60_000));
                StreamSegmentsFixture.insert(jdbc, streamId, seq, k * 4000L, wall, junk, null, dur,
                        random.nextInt(4) == 0);
            }
            seq += random.nextInt(6) == 0 ? 2 : 1;
            clock += dur;
        }
        return List.copyOf(v2Pdts);
    }
}
