package com.pokeclip.chat.detector.detect;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpikeEpisodesTest {

    private static final Instant AT = Instant.parse("2026-09-13T11:15:00Z");
    private static final SpikeVerdict SPIKE = new SpikeVerdict(true, "spike", 4.0, 10.0, 5_000L, 40, 25);

    private static SpikeEpisodes.Window 창(long id, long startMs) {
        return new SpikeEpisodes.Window(id, startMs, 5_000L, SPIKE, AT);
    }

    @Test
    void 이어지는_창은_같은_사건이다() {
        SpikeEpisodes episodes = new SpikeEpisodes(10_000L, 90_000L);

        assertThat(episodes.add("s1", 창(1, 0))).isEmpty();
        assertThat(episodes.add("s1", 창(2, 5_000))).isEmpty();
        assertThat(episodes.add("s1", 창(3, 10_000))).as("붙는 동안은 아무것도 안 닫힌다").isEmpty();

        assertThat(episodes.drainExpired(30_000L)).singleElement().satisfies(e -> {
            assertThat(e.windows()).hasSize(3);
            assertThat(e.startMs()).isZero();
            assertThat(e.endMs()).isEqualTo(15_000L);
            assertThat(e.spanMs()).isEqualTo(15_000L);
            assertThat(e.metricIds()).containsExactly(1L, 2L, 3L);
        });
    }

    @Test
    void 간격_안이면_창_사이가_비어도_붙는다() {
        SpikeEpisodes episodes = new SpikeEpisodes(10_000L, 90_000L);
        episodes.add("s1", 창(1, 0));

        assertThat(episodes.add("s1", 창(2, 15_000))).as("끝 5,000 + 간격 10,000 = 15,000 ≤ 시작").isEmpty();
        assertThat(episodes.add("s1", 창(3, 30_001))).as("끝 20,000 + 10,000 < 30,001 → 앞 사건이 닫힌다")
                .singleElement().satisfies(e -> assertThat(e.windows()).hasSize(2));
    }

    /**
     * 🔴 닫는 시각은 「끝 + 간격」이 아니라 「그 자리에 시작할 수 있는 마지막 창이 닫히는 시각」이다
     * (봇 리뷰 1판, codex). 15,000에 시작하는 창은 {@code add}가 같은 사건으로 보는데(≤), 그 창은
     * 끝 20,000이 지평에 들어와야 판정된다 — 15,000에 닫으면 그 창이 따로 카드가 된다.
     */
    @Test
    void 지평_전에는_안_닫힌다() {
        SpikeEpisodes episodes = new SpikeEpisodes(10_000L, 90_000L);
        episodes.add("s1", 창(1, 0));

        assertThat(episodes.drainExpired(15_000L)).as("끝 5,000 + 간격 10,000에 시작하는 창이 아직 안 닫혔다").isEmpty();
        assertThat(episodes.drainExpired(19_999L)).isEmpty();
        assertThat(episodes.drainExpired(20_000L)).hasSize(1);
        assertThat(episodes.drainExpired(20_000L)).as("한 번 닫힌 사건은 다시 안 나온다").isEmpty();
    }

    /** 위 규칙의 양성 대조 — 경계에 딱 시작한 창이 지평 안에서 <b>같은 사건</b>에 들어간다. */
    @Test
    void 간격_끝에_딱_시작한_창은_같은_사건에_들어간_뒤에야_닫힌다() {
        SpikeEpisodes episodes = new SpikeEpisodes(10_000L, 90_000L);
        episodes.add("s1", 창(1, 0));

        assertThat(episodes.drainExpired(15_000L)).isEmpty();
        assertThat(episodes.add("s1", 창(2, 15_000))).isEmpty();
        assertThat(episodes.drainExpired(35_000L)).singleElement()
                .satisfies(e -> assertThat(e.metricIds()).containsExactly(1L, 2L));
    }

    @Test
    void 상한을_넘기면_끊고_새_사건을_연다() {
        SpikeEpisodes episodes = new SpikeEpisodes(10_000L, 20_000L);
        episodes.add("s1", 창(1, 0));
        episodes.add("s1", 창(2, 5_000));
        episodes.add("s1", 창(3, 10_000));
        episodes.add("s1", 창(4, 15_000));   // 끝 20,000 = 상한. 아직 들어간다

        List<SpikeEpisodes.Episode> closed = episodes.add("s1", 창(5, 20_000));   // 끝 25,000 > 상한

        assertThat(closed).as("상한에 걸린 사건은 새 창이 간격 안이라 아직 안 닫힌다 — 시간으로 닫힌다").isEmpty();
        assertThat(episodes.openCount()).isEqualTo(2);
        assertThat(episodes.drainExpired(100_000L)).hasSize(2)
                .extracting(SpikeEpisodes.Episode::metricIds)
                .containsExactly(List.of(1L, 2L, 3L, 4L), List.of(5L));
    }

    /**
     * 🔴 <b>되돌아온 창이 새 사건 뒤에 붙지 않는다</b>(봇 리뷰 1판, codex P1·claude). 발행이 RETRY_LATER로
     * 되돌린 창은 다음 바퀴에 오래된 시각으로 다시 들어오는데, 그 사이 같은 방송에 새 사건이 열려 있다.
     * 옛 구현은 그 뒤에 붙여 첫·끝이 시간순이 아니게 되고 길이가 음수가 됐다 — 카드는 GIVE_UP, 발행권은
     * 소진, 새 사건의 창까지 같이 삼켰다.
     */
    @Test
    void 되돌아온_옛_창은_새_사건_뒤에_붙지_않고_따로_선다() {
        SpikeEpisodes episodes = new SpikeEpisodes(10_000L, 90_000L);
        episodes.add("s1", 창(9, 60_000));   // 새 사건이 먼저 열려 있다

        List<SpikeEpisodes.Episode> closed = episodes.add("s1", 창(1, 0));   // 되돌아온 옛 창

        assertThat(closed).as("옛 창이 들어왔다고 진행 중인 새 사건을 닫지 않는다").isEmpty();
        assertThat(episodes.openCount()).isEqualTo(2);
        List<SpikeEpisodes.Episode> all = episodes.drainExpired(100_000L);
        assertThat(all).hasSize(2);
        assertThat(all.get(0).metricIds()).containsExactly(1L);
        assertThat(all.get(1).metricIds()).containsExactly(9L);
        assertThat(all).allSatisfy(e -> assertThat(e.spanMs()).isEqualTo(5_000L));
    }

    /** 되돌아온 창 여럿이 자기들끼리 같은 사건으로 다시 묶인다 — 재시도의 목적이 그것이다. */
    @Test
    void 되돌아온_창들은_자기들끼리_같은_사건으로_다시_묶인다() {
        SpikeEpisodes episodes = new SpikeEpisodes(10_000L, 90_000L);
        episodes.add("s1", 창(9, 60_000));
        episodes.add("s1", 창(1, 0));
        episodes.add("s1", 창(2, 5_000));
        episodes.add("s1", 창(3, 10_000));

        List<SpikeEpisodes.Episode> all = episodes.drainExpired(100_000L);

        assertThat(all).hasSize(2);
        assertThat(all.get(0).metricIds()).containsExactly(1L, 2L, 3L);
        assertThat(all.get(0).spanMs()).isEqualTo(15_000L);
        assertThat(all.get(1).metricIds()).containsExactly(9L);
    }

    /** 순서가 뒤집혀 들어와도 사건 안에서는 시간순이다 — 첫·끝이 곧 카드 구간이다. */
    @Test
    void 사건_안의_창은_들어온_순서가_아니라_시간순이다() {
        SpikeEpisodes episodes = new SpikeEpisodes(10_000L, 90_000L);
        episodes.add("s1", 창(2, 5_000));
        episodes.add("s1", 창(1, 0));
        episodes.add("s1", 창(3, 10_000));

        assertThat(episodes.drainExpired(100_000L)).singleElement().satisfies(e -> {
            assertThat(e.metricIds()).containsExactly(1L, 2L, 3L);
            assertThat(e.startMs()).isZero();
            assertThat(e.spanMs()).isEqualTo(15_000L);
        });
    }

    /** 앞에 열린 사건 여럿이 새 창보다 완전히 앞이면 한꺼번에 닫힌다 — 그래서 반환이 목록이다. */
    @Test
    void 새_창보다_완전히_앞인_사건은_전부_닫힌다() {
        SpikeEpisodes episodes = new SpikeEpisodes(10_000L, 90_000L);
        episodes.add("s1", 창(9, 60_000));
        episodes.add("s1", 창(1, 0));

        List<SpikeEpisodes.Episode> closed = episodes.add("s1", 창(20, 200_000));

        assertThat(closed).hasSize(2);
        assertThat(episodes.openCount()).isEqualTo(1);
    }

    @Test
    void 방송이_다르면_섞이지_않는다() {
        SpikeEpisodes episodes = new SpikeEpisodes(10_000L, 90_000L);
        episodes.add("s1", 창(1, 0));
        episodes.add("s2", 창(2, 0));

        List<SpikeEpisodes.Episode> closed = episodes.drainExpired(100_000L);

        assertThat(closed).hasSize(2);
        assertThat(closed).extracting(SpikeEpisodes.Episode::streamId).containsExactlyInAnyOrder("s1", "s2");
    }

    @Test
    void 간격_0이면_맞닿은_창만_같은_사건이다() {
        SpikeEpisodes episodes = new SpikeEpisodes(0L, 90_000L);
        episodes.add("s1", 창(1, 0));

        assertThat(episodes.add("s1", 창(2, 5_000))).as("끝 5,000 + 0 = 5,000 ≤ 시작 5,000 → 붙는다(맞닿음)").isEmpty();
        assertThat(episodes.add("s1", 창(3, 10_001))).isNotEmpty();
    }

    @Test
    void 근거_집계는_최대_배율과_합계_건수다() {
        SpikeEpisodes.Episode e = new SpikeEpisodes.Episode("s1", List.of(
                new SpikeEpisodes.Window(1, 0, 5_000L, new SpikeVerdict(true, "spike", 3.0, 10.0, 5_000L, 30, 10), AT),
                new SpikeEpisodes.Window(2, 5_000, 5_000L, new SpikeVerdict(true, "spike", 7.5, 10.0, 5_000L, 75, 40), AT),
                new SpikeEpisodes.Window(3, 10_000, 5_000L, new SpikeVerdict(true, "spike", 4.0, 10.0, 5_000L, 40, 20), AT)));

        assertThat(e.maxRatio()).isEqualTo(7.5);
        assertThat(e.totalMessages()).isEqualTo(145);
        assertThat(e.maxChatters()).isEqualTo(40);
    }

    @Test
    void 잘못된_설정은_부팅에서_막는다() {
        assertThatThrownBy(() -> new SpikeEpisodes(-1L, 90_000L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SpikeEpisodes(0L, 0L)).isInstanceOf(IllegalArgumentException.class);
    }
}
