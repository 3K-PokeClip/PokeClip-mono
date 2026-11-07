package com.pokeclip.clip.segment;

import com.pokeclip.clip.broadcast.Broadcast;
import com.pokeclip.clip.broadcast.BroadcastRepository;
import com.pokeclip.clip.segment.RoundRows.Round;
import com.pokeclip.clip.support.IntegrationTestSupport;
import com.pokeclip.clip.support.LedgerSeed;
import com.pokeclip.clip.support.TestIds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static com.pokeclip.clip.support.LedgerSeed.at;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 회차 고르기(POK-196 · 설계 r7 8.2 「회차 고르기」 · r6 S-c1). 귀속 행은 그 방송 회차의 세션에 귀속된 행뿐이다 — 비귀속 행 ·
 * 다른 회차 · 앞 방송 꼬리는 빠진다(계약 6-6-5). 머리 세션 행이 없는 옛 방송만 시간창으로 대신한다.
 */
class RoundRowsTest extends IntegrationTestSupport {

    private static final String KEY = "ROUNDKEY";

    private final RoundRows rounds;
    private final BroadcastRepository broadcasts;
    private final JdbcTemplate jdbc;
    private final LedgerSeed seed;

    RoundRowsTest(RoundRows rounds, BroadcastRepository broadcasts, JdbcTemplate jdbc) {
        this.rounds = rounds;
        this.broadcasts = broadcasts;
        this.jdbc = jdbc;
        this.seed = new LedgerSeed(jdbc);
    }

    @BeforeEach
    void 비운다() {
        방송과_카드를_비운다(jdbc);
    }

    @Test
    void 회차가_있는_머리면_같은_회차의_세션_전부가_귀속이고_다른_회차는_빠진다() {
        seed.session("S-a", KEY, "S-a", null);
        seed.session("S-a2", KEY, "S-a", "S-a");
        seed.session("S-x", KEY, "S-x", null);
        Broadcast broadcast = 방송("S-a");

        Round round = rounds.roundOf(broadcast).orElseThrow();

        assertThat(round).isInstanceOfSatisfying(RoundRows.Members.class, m -> {
            assertThat(m.ingestKey()).isEqualTo(KEY);
            assertThat(m.sessionIds()).containsExactlyInAnyOrder("S-a", "S-a2");
        });
    }

    @Test
    void 회차_칸이_빈_옛_머리는_계승_사슬을_따른다() {
        seed.session("S-n", KEY, null, null);
        seed.session("S-n2", KEY, null, "S-n");
        seed.session("S-n3", KEY, null, "S-n2");
        seed.session("S-other", KEY, null, null);

        Round round = rounds.roundOf(방송("S-n")).orElseThrow();

        assertThat(((RoundRows.Members) round).sessionIds()).containsExactlyInAnyOrder("S-n", "S-n2", "S-n3");
    }

    /** 계승 사슬이 고리를 이뤄도(장부 오류) 같은 세션을 두 번 따라가지 않아 끝난다. */
    @Test
    void 계승_사슬이_고리여도_끝나고_고리의_세션이_귀속이다() {
        seed.session("S-c", KEY, null, null);
        seed.session("S-c2", KEY, null, "S-c");
        jdbc.update("UPDATE public.stream_sessions SET inherits_session = 'S-c2' WHERE session_id = 'S-c'");

        Round round = rounds.roundOf(방송("S-c")).orElseThrow();

        assertThat(((RoundRows.Members) round).sessionIds()).containsExactlyInAnyOrder("S-c", "S-c2");
    }

    /** 사슬 깊이 — 재접속 수만큼 이어진 긴 사슬도 끝까지 따른다(깊이 상한을 두지 않는다 · 끝남은 위 고리 시험이 보장). */
    @Test
    void 긴_계승_사슬도_끝까지_따른다() {
        seed.session("S-d0", KEY, null, null);
        for (int i = 1; i < 60; i++) {
            seed.session("S-d" + i, KEY, null, "S-d" + (i - 1));
        }

        Round round = rounds.roundOf(방송("S-d0")).orElseThrow();

        assertThat(((RoundRows.Members) round).sessionIds()).hasSize(60).contains("S-d59");
    }

    /** 사슬은 같은 물리 키 안에서만 잇는다 — 다른 키의 세션이 내 세션을 계승한다고 적혀도 회차에 들지 않는다. */
    @Test
    void 계승_사슬은_다른_물리_키의_세션으로_넘어가지_않는다() {
        seed.session("S-k", KEY, null, null);
        seed.session("S-k2", KEY, null, "S-k");
        seed.session("S-foreign", "OTHERKEY", null, "S-k2");

        Round round = rounds.roundOf(방송("S-k")).orElseThrow();

        assertThat(((RoundRows.Members) round).sessionIds()).containsExactlyInAnyOrder("S-k", "S-k2");
    }

    @Test
    void 머리_세션_행이_없는_옛_방송은_시작_앞_2분부터_종료_뒤_10분까지의_시간창이다() {
        Broadcast broadcast = 방송("S-old");
        jdbc.update("UPDATE broadcasts SET status = 'ended', ended_at = ? WHERE stream_id = 'S-old'",
                Timestamp.from(at(3_600_000)));
        broadcast = broadcasts.findByStreamId("S-old").orElseThrow();

        Round round = rounds.roundOf(broadcast).orElseThrow();

        assertThat(round).isEqualTo(new RoundRows.TimeWindow(KEY,
                at(0).minus(Duration.ofMinutes(2)), at(3_600_000).plus(Duration.ofMinutes(10))));
    }

    @Test
    void 머리_행도_시작_시각도_없으면_회차가_없다() {
        broadcasts.save(Broadcast.endedPlaceholder("S-ph", TestIds.STREAMER, KEY, 1L, at(10_000)));

        assertThat(rounds.roundOf(broadcasts.findByStreamId("S-ph").orElseThrow())).isEmpty();
    }

    @Test
    void 귀속_행은_회차_세션에_귀속된_행만_seq_순이다() {
        seed.session("S-a", KEY, "S-a", null);
        seed.session("S-a2", KEY, "S-a", "S-a");
        seed.session("S-x", KEY, "S-x", null);
        seed.row(KEY, 1).pts(0).wallAndPdt(0).session("S-a").insert();
        seed.row(KEY, 2).pts(4_000).wall(4_000).insert();                     // 비귀속(회차 머리 지연 · E28)
        seed.row(KEY, 3).pts(8_000).wallAndPdt(8_000).session("S-a2").insert();
        seed.row(KEY, 4).pts(12_000).wallAndPdt(12_000).session("S-x").insert(); // 다른 회차

        List<RoundRows.Row> rows = rounds.rowsOf(rounds.roundOf(방송("S-a")).orElseThrow());

        assertThat(rows).extracting(RoundRows.Row::seq).containsExactly(1L, 3L);
    }

    @Test
    void 시간창_회차는_창_안의_행을_귀속과_무관하게_읽는다() {
        seed.row(KEY, 1).pts(0).wall(-200_000).insert();   // 시작 앞 2분 밖(앞 방송 꼬리)
        seed.row(KEY, 2).pts(4_000).wall(-100_000).insert();
        seed.row(KEY, 3).pts(8_000).wall(10_000).session("S-whatever").insert();

        List<RoundRows.Row> rows = rounds.rowsOf(rounds.roundOf(방송("S-old")).orElseThrow());

        assertThat(rows).extracting(RoundRows.Row::seq).containsExactly(2L, 3L);
    }

    /** 판 NULL 벽시각은 µs라 ms로 내린다(설계 r7 3.2 「start_wall_utc 는 µs 라 ms 로 내린다」). */
    @Test
    void 행은_벽시각_µs를_ms로_내리고_판_PDT_올림_상태를_싣는다() {
        seed.session("S-a", KEY, "S-a", null);
        seed.row(KEY, 1).pts(0).wallMicros(1_999_999).pdt(5_000).duration(3_000).session("S-a").v2().insert();
        seed.row(KEY, 2).pts(3_000).wallMicros(4_000_500).session("S-a").pending().insert();

        List<RoundRows.Row> rows = rounds.rowsOf(rounds.roundOf(방송("S-a")).orElseThrow());

        long base = LedgerSeed.BASE.toEpochMilli();
        assertThat(rows).containsExactly(
                new RoundRows.Row(1, 0, 3_000, base + 1_999, base + 5_000L, 2, true),
                new RoundRows.Row(2, 3_000, 4_000, base + 4_000, null, null, false));
    }

    @Test
    void 페이지의_방송_여럿을_한_번에_고른다() {
        seed.session("S-a", KEY, "S-a", null);
        seed.session("S-b", "OTHERKEY", "S-b", null);

        Map<String, Round> found = rounds.roundsOf(List.of(방송("S-a"), 방송("S-b", "OTHERKEY"), 방송("S-old")));

        assertThat(found.get("S-a")).isEqualTo(new RoundRows.Members(KEY, List.of("S-a")));
        assertThat(found.get("S-b")).isEqualTo(new RoundRows.Members("OTHERKEY", List.of("S-b")));
        assertThat(found.get("S-old")).isInstanceOf(RoundRows.TimeWindow.class);
    }

    // ── 회차 완결 술어(r6 S-c1 C6) ─────────────────────────────

    @Test
    void 회차_세션_둘_중_하나만_끝났으면_완결이_아니다() {
        seed.session("S-a", KEY, "S-a", null);
        seed.session("S-a2", KEY, "S-a", "S-a");
        seed.endSession("S-a", true);

        assertThat(rounds.roundComplete("S-a")).isFalse();
    }

    @Test
    void 회차_세션이_모두_끝나고_닫혔으면_완결이다() {
        seed.session("S-a", KEY, "S-a", null);
        seed.session("S-a2", KEY, "S-a", "S-a");
        seed.endSession("S-a", true);
        seed.endSession("S-a2", true);

        assertThat(rounds.roundComplete("S-a")).isTrue();
        assertThat(rounds.roundComplete("S-a2")).as("회차의 어느 세션으로 물어도 같다").isTrue();
    }

    @Test
    void 끝났어도_회차가_안_닫혔으면_완결이_아니다() {
        seed.session("S-a", KEY, "S-a", null);
        seed.endSession("S-a", false);

        assertThat(rounds.roundComplete("S-a")).isFalse();
    }

    @Test
    void 회차_칸이_비었거나_세션이_없으면_술어가_없다() {
        seed.session("S-n", KEY, null, null);
        seed.endSession("S-n", true);

        assertThat(rounds.roundComplete("S-n")).isFalse();
        assertThat(rounds.roundComplete("S-none")).isFalse();
    }

    /**
     * 장부 표는 {@code public}에 있고 clip의 세션 경로는 {@code app_clip} 하나다(POK-428). SQL에서 한정이 빠지면 42P01로
     * 크게 실패해야 한다 — 한정이 지워져도 시드 DB에서 초록이면 운영에서만 터진다.
     */
    @Test
    void 장부_표_이름의_public_한정을_지우면_42P01로_실패한다() {
        for (String sql : List.of(RoundRows.HEADS, RoundRows.MEMBER_ROWS, RoundRows.WINDOW_ROWS,
                RoundRows.ROUND_COMPLETE)) {
            String unqualified = sql.replace("public.", "");
            assertThatThrownBy(() -> jdbc.execute("EXPLAIN " + 빈_인자로(unqualified)))
                    .as(unqualified)
                    .isInstanceOfSatisfying(BadSqlGrammarException.class,
                            e -> assertThat(e.getSQLException().getSQLState()).isEqualTo("42P01"));
        }
    }

    /** {@code ?} 를 NULL 로 채워 EXPLAIN 만 돌린다(행을 안 읽고 이름 풀이만 잰다). */
    private static String 빈_인자로(String sql) {
        return sql.replace("?", "NULL");
    }

    private Broadcast 방송(String streamId) {
        return 방송(streamId, KEY);
    }

    private Broadcast 방송(String streamId, String ingestKey) {
        return broadcasts.save(Broadcast.startedNow(streamId, TestIds.STREAMER, ingestKey, 1L, at(0), null));
    }
}
