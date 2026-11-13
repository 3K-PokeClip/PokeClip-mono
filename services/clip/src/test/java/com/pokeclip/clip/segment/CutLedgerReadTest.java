package com.pokeclip.clip.segment;

import com.pokeclip.clip.broadcast.Broadcast;
import com.pokeclip.clip.broadcast.BroadcastRepository;
import com.pokeclip.clip.broadcast.ExpiredBroadcastReader;
import com.pokeclip.clip.broadcast.ExpiredBroadcastRow;
import com.pokeclip.clip.segment.CutDecision.LedgerNeighbor;
import com.pokeclip.clip.support.IntegrationTestSupport;
import com.pokeclip.clip.support.LedgerSeed;
import com.pokeclip.clip.support.TestIds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static com.pokeclip.clip.support.LedgerSeed.at;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 렌더 주문 컷 판정의 장부 읽기(POK-196 · 설계 r7 9.1 · kty 2026-11-12 D1 · K4). 회차 귀속 행은 회차 전부를 PDT 축 판정 꼴
 * ({@link RoundRow})로 읽고, 준비 후보는 머리마다 장부 바로 앞 행을 이 방송 장부 구간 안에서만 읽는다 — 구간 앞(앞 방송 몫) 행은 결과에
 * 아예 없다.
 */
class CutLedgerReadTest extends IntegrationTestSupport {

    private static final String KEY = "K-cutread";

    private final StreamSegmentReader segments;
    private final RoundRows rounds;
    private final BroadcastRepository broadcasts;
    private final ExpiredBroadcastReader expired;
    private final JdbcTemplate jdbc;
    private final LedgerSeed seed;

    CutLedgerReadTest(StreamSegmentReader segments, RoundRows rounds, BroadcastRepository broadcasts,
                      ExpiredBroadcastReader expired, JdbcTemplate jdbc) {
        this.segments = segments;
        this.rounds = rounds;
        this.broadcasts = broadcasts;
        this.expired = expired;
        this.jdbc = jdbc;
        this.seed = new LedgerSeed(jdbc);
    }

    @BeforeEach
    void 비운다() {
        방송과_카드를_비운다(jdbc);
    }

    /**
     * 회차 귀속 행만 seq 순으로, 판정 칸(파일명 시각 · PDT · 판 · 새 녹화 · 업로드 · 키)을 그대로 싣는다. 장부 이웃 표시는 「목록 바로 앞
     * 행이 장부 바로 앞 행인가」 다 — 사이에 비귀속 행(4)이 끼면 거짓 · 첫 행 거짓.
     */
    @Test
    void 회차_귀속_행을_PDT_축_판정_꼴로_읽는다() {
        seed.session("S-cutread", KEY, "S-cutread", null);
        seed.row(KEY, 1).wall(0).pdt(0).insert();
        seed.row(KEY, 2).wall(4_000).pdt(4_100).v2().session("S-cutread").insert();
        seed.row(KEY, 3).wall(8_000).pdt(8_100).v2().session("S-cutread").discontinuity().pending().insert();
        seed.row(KEY, 4).wall(12_000).pdt(12_100).insert();
        seed.row(KEY, 5).wall(16_000).pdt(16_100).v2().session("S-cutread").insert();

        List<RoundRow> rows = segments.findRoundRows(rounds.roundOf(방송("S-cutread")).orElseThrow());

        assertThat(rows).containsExactly(
                new RoundRow(2, at(4_000).toEpochMilli(), at(4_100).toEpochMilli(), 4_000, false, false, "uploaded", 2,
                        "seg/" + KEY + "/2"),
                new RoundRow(3, at(8_000).toEpochMilli(), at(8_100).toEpochMilli(), 4_000, true, true, "pending", 2,
                        "seg/" + KEY + "/3"),
                new RoundRow(5, at(16_000).toEpochMilli(), at(16_100).toEpochMilli(), 4_000, false, false, "uploaded",
                        2, "seg/" + KEY + "/5"));
    }

    /** 머리 세션 행이 없는 옛 방송은 시간창 행이다(귀속과 무관) — 같은 꼴로 읽는다. */
    @Test
    void 옛_방송은_시간창_행을_같은_꼴로_읽는다() {
        seed.row(KEY, 1).wallAndPdt(0).insert();
        seed.row(KEY, 2).wallAndPdt(4_000).insert();

        List<RoundRow> rows = segments.findRoundRows(rounds.roundOf(방송("S-cutread-old")).orElseThrow());

        assertThat(rows).extracting(RoundRow::seq).containsExactly(1L, 2L);
        assertThat(rows).extracting(RoundRow::followsLedgerPrevious).containsExactly(false, true);
        assertThat(rows).extracting(RoundRow::timingVersion).containsOnlyNulls();
    }

    /**
     * 준비 후보 — 머리마다 장부 바로 앞 행(회차 · 세션 · 판과 무관 · seq 비조밀). 앞 방송이 없으면 구간에 시작이 없다. 머리 2 의 앞 행
     * 1 은 비귀속 · 머리 7 의 앞 행은 4 다(5 · 6 없음).
     */
    @Test
    void 준비_후보는_장부_바로_앞_행이고_비귀속이어도_seq_가_비조밀해도_그_행이다() {
        seed.row(KEY, 1).wallAndPdt(0).insert();
        seed.row(KEY, 2).wallAndPdt(4_000).session("S-x").insert();
        seed.row(KEY, 4).wallAndPdt(8_000).pending().insert();
        seed.row(KEY, 7).wallAndPdt(12_000).session("S-x").insert();
        방송("S-x");

        CutPlanner.Preceding preceding = segments.findRowsBefore(KEY, "S-x", List.of(2L, 7L));

        assertThat(preceding.previousBroadcast()).isFalse();
        assertThat(preceding.rows()).containsOnlyKeys(2L, 7L);
        assertThat(preceding.rows().get(2L)).isEqualTo(new LedgerNeighbor(1, 4_000, "uploaded", "seg/" + KEY + "/1"));
        assertThat(preceding.rows().get(7L)).isEqualTo(new LedgerNeighbor(4, 4_000, "pending", "seg/" + KEY + "/4"));
    }

    /**
     * K4 — 같은 물리 키의 앞 방송 몫(이 방송 장부 구간 시작 앞) 행은 결과에 아예 없다(계약 5-2 「만료 방송 조각은 조회하지 않는다」).
     * 앞 방송이 있다는 표시는 남아, 그 행이 지워진 뒤에도 판정이 같다(K2).
     */
    @Test
    void 앞_방송_몫_행은_읽지_않고_지워진_뒤에도_앞_방송_표시가_같다() {
        Broadcast previous = 방송("S-prev", at(0));
        jdbc.update("UPDATE broadcasts SET status = 'ended', ended_at = ? WHERE id = ?", Timestamp.from(at(60_000)),
                previous.getId());
        방송("S-this", at(600_000));
        seed.row(KEY, 1).wallAndPdt(30_000).insert();
        seed.row(KEY, 2).wallAndPdt(600_000).session("S-this").insert();
        seed.row(KEY, 3).wallAndPdt(604_000).session("S-this").insert();

        CutPlanner.Preceding before = segments.findRowsBefore(KEY, "S-this", List.of(2L, 3L));
        jdbc.update("DELETE FROM public.stream_segments WHERE stream_id = ? AND seq = 1", KEY);
        CutPlanner.Preceding after = segments.findRowsBefore(KEY, "S-this", List.of(2L, 3L));

        assertThat(before.rows()).containsOnlyKeys(3L);
        assertThat(before.rows().get(3L).seq()).isEqualTo(2);
        assertThat(before.previousBroadcast()).isTrue();
        assertThat(after).isEqualTo(before);
    }

    /** 한 방송 구간 시작과 만료 목록의 구간 시작은 같은 식이다 — 두 자리가 갈리면 만료 정리와 준비 판정이 다른 경계를 쓴다. */
    @Test
    void 한_방송_구간_시작은_만료_목록의_구간_시작과_같다() {
        Broadcast previous = 방송("S-prev", at(0));
        jdbc.update("UPDATE broadcasts SET status = 'ended', ended_at = ? WHERE id = ?", Timestamp.from(at(500_000)),
                previous.getId());
        Broadcast mine = 방송("S-this", at(600_000));
        jdbc.update("UPDATE broadcasts SET vod_expires_at = ? WHERE id = ?", Timestamp.from(at(700_000)), mine.getId());

        Timestamp one = jdbc.queryForObject(ExpiredBroadcastReader.SECTION_START, Timestamp.class, "S-this");
        List<ExpiredBroadcastRow> page = expired.read(at(800_000), Long.MIN_VALUE, 0, 10);

        assertThat(page).hasSize(1);
        assertThat(one.toInstant()).isEqualTo(page.get(0).segmentsFrom()).isEqualTo(at(500_000));
    }

    private Broadcast 방송(String streamId) {
        return 방송(streamId, at(0));
    }

    private Broadcast 방송(String streamId, Instant startedAt) {
        return broadcasts.save(Broadcast.startedNow(streamId, TestIds.STREAMER, KEY, 1L, startedAt, null));
    }
}
