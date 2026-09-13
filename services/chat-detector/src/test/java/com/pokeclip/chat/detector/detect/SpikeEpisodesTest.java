package com.pokeclip.chat.detector.detect;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

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

        assertThat(episodes.drainExpired(25_000L)).singleElement().satisfies(e -> {
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
                .get().satisfies(e -> assertThat(e.windows()).hasSize(2));
    }

    @Test
    void 지평_전에는_안_닫힌다() {
        SpikeEpisodes episodes = new SpikeEpisodes(10_000L, 90_000L);
        episodes.add("s1", 창(1, 0));

        assertThat(episodes.drainExpired(14_999L)).as("끝 5,000 + 간격 10,000 = 15,000 > 지평").isEmpty();
        assertThat(episodes.drainExpired(15_000L)).hasSize(1);
        assertThat(episodes.drainExpired(15_000L)).as("한 번 닫힌 사건은 다시 안 나온다").isEmpty();
    }

    @Test
    void 상한을_넘기면_끊고_새_사건을_연다() {
        SpikeEpisodes episodes = new SpikeEpisodes(10_000L, 20_000L);
        episodes.add("s1", 창(1, 0));
        episodes.add("s1", 창(2, 5_000));
        episodes.add("s1", 창(3, 10_000));
        episodes.add("s1", 창(4, 15_000));   // 끝 20,000 = 상한. 아직 들어간다

        Optional<SpikeEpisodes.Episode> closed = episodes.add("s1", 창(5, 20_000));   // 끝 25,000 > 상한

        assertThat(closed).get().satisfies(e -> assertThat(e.windows()).hasSize(4));
        assertThat(episodes.drainExpired(100_000L)).singleElement()
                .satisfies(e -> assertThat(e.metricIds()).containsExactly(5L));
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
    void 간격_0이면_창_하나가_사건_하나다() {
        SpikeEpisodes episodes = new SpikeEpisodes(0L, 90_000L);
        episodes.add("s1", 창(1, 0));

        assertThat(episodes.add("s1", 창(2, 5_000))).as("끝 5,000 + 0 = 5,000 ≤ 시작 5,000 → 붙는다(맞닿음)").isEmpty();
        assertThat(episodes.add("s1", 창(3, 10_001))).isPresent();
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
