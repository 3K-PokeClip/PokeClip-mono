package com.pokeclip.chat.collector.sync;

import com.pokeclip.chat.collector.broadcast.IngestKeyStore;
import com.pokeclip.chat.collector.support.IntegrationTestSupport;
import com.pokeclip.chat.collector.support.StreamSegmentsFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 성질 시험(설계 r6 7.4) — 무작위 장부와 무작위 채팅 시각에서, SQL 경로({@link VideoPositionCalculator})와 「계약 세그먼트인덱스
 * 6-6-9 문언대로 Java로 고르는 참조 구현」({@link #expected})이 같은 판정 · 같은 위치 · 같은 조각 · 같은 보정값을 낸다.
 *
 * <p>장부는 판 NULL · 판 2 · 판 2 → NULL → 2 전환 · 회차 리셋(PDT 뒤로 감) · 1초 미만 PDT 겹침 · 벽시각 역행 옛 행 · 끊긴
 * 이웃 · seq 빈 번호(비귀속 행이 빠진 자리)를 섞는다. 판 NULL 행에도 엉뚱한 {@code playback_pdt}를 넣는다 — 옛 행의 PDT를
 * 읽으면 갈린다.
 *
 * <p><b>방송 번호 접두는 {@code prop-}다.</b> 씨앗이 고정이라 결정적이다 — 실패하면 메시지의 장부 · 시각으로 그대로 재현한다.
 */
@SpringBootTest
@ActiveProfiles("test")
class VideoPositionPropertyTest extends IntegrationTestSupport {

    private static final Instant T0 = Instant.parse("2026-11-13T00:00:00Z");
    private static final long SEED = 20261113L;
    private static final int LEDGERS = 200;
    private static final int TIMES_PER_LEDGER = 15;

    private final JdbcTemplate jdbc;
    private final SegmentLedger ledger;
    private final List<String> streams = new ArrayList<>();

    VideoPositionPropertyTest(SegmentLedger ledger, JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.ledger = ledger;
    }

    @AfterEach
    void 내_방송_행을_비운다() {
        streams.forEach(streamId -> StreamSegmentsFixture.clear(jdbc, streamId));
    }

    /** 장부 한 행(시각은 T0부터의 ms). {@code version}이 {@code null}이면 판 NULL 행이다. */
    private record Row(long seq, long pts, long wall, Long pdt, Integer version, int dur, boolean disc) {

        boolean v2() {
            return version != null && version == 2;
        }
    }

    private record Expected(VideoPosition.State state, Long positionMs, Long seq, long offsetMs) {
    }

    @Test
    void SQL_경로와_계약_문언_참조_구현이_같은_답을_낸다() {
        StreamSegmentsFixture.ensureTable(jdbc);
        Random random = new Random(SEED);
        int compared = 0;
        int converted = 0;
        int v2Chosen = 0;

        for (int i = 0; i < LEDGERS; i++) {
            String streamId = "prop-" + i;
            streams.add(streamId);
            StreamSegmentsFixture.clear(jdbc, streamId);
            List<Row> rows = randomLedger(random);
            rows.forEach(row -> insert(streamId, row));
            long correction = random.nextInt(301);
            VideoPositionCalculator calculator = new VideoPositionCalculator(ledger, new IngestKeyStore(jdbc),
                    new SyncProperties(0, Map.of(), correction));
            long end = rows.stream().mapToLong(r -> Math.max(r.wall(), r.pdt() == null ? 0 : r.pdt()) + r.dur())
                    .max().orElse(0);

            for (int j = 0; j < TIMES_PER_LEDGER; j++) {
                long t = -3000 + (long) random.nextInt((int) end + 9000);
                VideoPosition actual = calculator.locate(streamId, "prop-channel", T0.plusMillis(t));
                Expected expected = expected(rows, t, correction);

                assertThat(new Expected(actual.state(), actual.positionMs(), actual.segmentSeq(),
                        actual.appliedOffsetMs()))
                        .as("장부 %s · 시각 %d · 차감 %d", rows, t, correction)
                        .isEqualTo(expected);
                compared++;
                converted += expected.state() == VideoPosition.State.CONVERTED ? 1 : 0;
                v2Chosen += expected.offsetMs() != 0 ? 1 : 0;
            }
        }

        // 생성기가 한쪽 갈래만 만들면 비교가 공짜로 초록이다 — 갈래가 고루 나왔는지 본다.
        assertThat(compared).isEqualTo(LEDGERS * TIMES_PER_LEDGER);
        assertThat(converted).as("변환된 비교 수").isGreaterThan(compared / 4);
        assertThat(v2Chosen).as("판 2 후보를 고른 비교 수").isGreaterThan(compared / 10);
    }

    private void insert(String streamId, Row row) {
        StreamSegmentsFixture.insert(jdbc, streamId, row.seq(), row.pts(), T0.plusMillis(row.wall()),
                row.pdt() == null ? null : T0.plusMillis(row.pdt()), row.version(), row.dur(), row.disc());
    }

    /**
     * 무작위 장부. 공통 시계 {@code clock}을 조각마다 길이만큼 밀고, 판 NULL 행은 파일명 시각을 그 둘레에서 흔들고(가끔 크게
     * 뒤로 — 역행), 판 2 행은 PDT를 흔든다(1초 미만 겹침 · 가끔 회차 리셋으로 뒤로). 판 2 행의 파일명 시각은 PDT보다
     * 0–400ms 이르다(파일 머리).
     */
    private static List<Row> randomLedger(Random random) {
        int count = 2 + random.nextInt(9);
        List<Row> rows = new ArrayList<>();
        long seq = 1 + random.nextInt(50);
        long pts = 0;
        long clock = random.nextInt(2000);
        Integer version = random.nextBoolean() ? null : 2;
        for (int k = 0; k < count; k++) {
            if (k > 0 && random.nextInt(5) == 0) {
                version = version == null ? Integer.valueOf(2) : null;
            }
            int dur = 3000 + random.nextInt(1501);
            boolean disc = k > 0 && random.nextInt(4) == 0;
            Row row = version == null
                    ? legacyRow(random, seq, pts, clock, dur, disc)
                    : v2Row(random, seq, pts, clock, dur, disc);
            rows.add(row);
            seq += random.nextInt(6) == 0 ? 2 : 1;
            pts += dur + (random.nextInt(5) == 0 ? random.nextInt(1500) : 0);
            clock += dur + random.nextInt(2500) - 999;
        }
        return List.copyOf(rows);
    }

    private static Row legacyRow(Random random, long seq, long pts, long clock, int dur, boolean disc) {
        long wall = random.nextInt(10) == 0 ? clock - 5000 - random.nextInt(10_000) : clock + random.nextInt(600) - 300;
        Long junkPdt = random.nextBoolean() ? null : clock - 20_000 + random.nextInt(40_000);
        return new Row(seq, pts, wall, junkPdt, null, dur, disc);
    }

    private static Row v2Row(Random random, long seq, long pts, long clock, int dur, boolean disc) {
        long pdt = random.nextInt(10) == 0 ? clock - 5000 - random.nextInt(10_000) : clock;
        return new Row(seq, pts, pdt - random.nextInt(401), pdt, 2, dur, disc);
    }

    /**
     * 계약 6-6-9 문언대로 고르는 참조. 옛 행 가운데 파일명 시각 ≤ t_NULL, 판 2 행 가운데 PDT ≤ t_2 에서 각각 후보를 찾고
     * (옛 후보는 역행 신호 둘이 참이면 버림), 둘 다 있으면 seq 큰 쪽이다. 위치는 고른 행과 seq 다음 행으로 낸다 — 판 2 는 F 의
     * 2 · 3항, 옛 행은 지금 식(이어진 다음 행이면 조각 길이로 접음).
     */
    private static Expected expected(List<Row> rows, long message, long correction) {
        long legacyT = message;
        long v2T = message + correction;
        List<Row> legacy = rows.stream().filter(r -> !r.v2()).toList();
        List<Row> legacyCandidates = legacy.stream().filter(r -> r.wall() <= legacyT).toList();
        Optional<Row> a = Optional.empty();
        boolean inverted = false;
        if (!legacyCandidates.isEmpty()) {
            Row floor = legacyCandidates.stream()
                    .max(Comparator.comparingLong(Row::wall).thenComparingLong(Row::seq)).orElseThrow();
            long maxCandidateSeq = legacyCandidates.stream().mapToLong(Row::seq).max().orElseThrow();
            boolean earlierIsFuture = legacy.stream().anyMatch(y -> y.seq() < floor.seq() && y.wall() > floor.wall());
            inverted = maxCandidateSeq != floor.seq() || earlierIsFuture;
            a = inverted ? Optional.empty() : Optional.of(floor);
        }
        Optional<Row> c = rows.stream().filter(r -> r.v2() && r.pdt() <= v2T).max(Comparator.comparingLong(Row::seq));
        Optional<Row> chosen = a.isEmpty() ? c : c.isEmpty() ? a : (a.get().seq() > c.get().seq() ? a : c);
        if (chosen.isEmpty()) {
            return new Expected(rows.isEmpty() ? VideoPosition.State.NOT_YET_INDEXED : VideoPosition.State.NO_FOOTAGE,
                    null, null, 0);
        }
        Row k = chosen.get();
        Optional<Row> next = rows.stream().filter(r -> r.seq() > k.seq()).min(Comparator.comparingLong(Row::seq));
        return k.v2() ? v2Expected(k, next, v2T, -correction) : legacyExpected(k, next, legacyT);
    }

    private static Expected v2Expected(Row k, Optional<Row> next, long t, long offset) {
        long x = t - k.pdt();
        if (x < k.dur()) {
            return converted(k.pts() + x, k.seq(), offset);
        }
        if (next.isEmpty()) {
            return new Expected(VideoPosition.State.NOT_YET_INDEXED, null, null, offset);
        }
        if (next.get().disc()) {
            return new Expected(VideoPosition.State.NO_FOOTAGE, null, null, offset);
        }
        return converted(next.get().pts(), next.get().seq(), offset);
    }

    private static Expected legacyExpected(Row k, Optional<Row> next, long t) {
        long delta = t - k.wall();
        if ((next.isPresent() && !next.get().disc()) || delta < k.dur()) {
            return converted(k.pts() + Math.min(delta, k.dur()), k.seq(), 0);
        }
        return new Expected(next.isPresent() ? VideoPosition.State.NO_FOOTAGE : VideoPosition.State.NOT_YET_INDEXED,
                null, null, 0);
    }

    private static Expected converted(long position, long seq, long offset) {
        return new Expected(VideoPosition.State.CONVERTED, position, seq, offset);
    }
}
