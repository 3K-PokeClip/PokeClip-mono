package com.pokeclip.clip.segment;

import com.pokeclip.clip.broadcast.Broadcast;
import com.pokeclip.clip.broadcast.BroadcastRepository;
import com.pokeclip.clip.support.IntegrationTestSupport;
import com.pokeclip.clip.support.LedgerSeed;
import com.pokeclip.clip.support.TestIds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static com.pokeclip.clip.support.LedgerSeed.at;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 기준점은 그 방송 <b>회차의 첫 귀속 행</b>에서 잰다(POK-196 · r6 S-c1 C8). 값의 식(첫 행의
 * {@code COALESCE(playback_pdt, start_wall_utc) − start_pts_ms})은 그대로이고 행을 고르는 범위만 회차로 바뀐다. 머리 세션
 * 행이 없는 옛 방송은 지금 시간창 그대로다.
 */
class TimelineOriginReaderTest extends IntegrationTestSupport {

    private static final String KEY = "ORIGINKEY";

    private final TimelineOriginReader origins;
    private final BroadcastRepository broadcasts;
    private final JdbcTemplate jdbc;
    private final LedgerSeed seed;

    TimelineOriginReaderTest(TimelineOriginReader origins, BroadcastRepository broadcasts, JdbcTemplate jdbc) {
        this.origins = origins;
        this.broadcasts = broadcasts;
        this.jdbc = jdbc;
        this.seed = new LedgerSeed(jdbc);
    }

    @BeforeEach
    void 비운다() {
        방송과_카드를_비운다(jdbc);
    }

    /** 회차 머리 앞 비귀속 행(E28 회차 머리 지연)은 seq가 앞서도 기준점에 안 쓰인다. */
    @Test
    void 회차_머리_앞_비귀속_행은_기준점에_안_쓰인다() {
        seed.session("S-a", KEY, "S-a", null);
        seed.row(KEY, 1).pts(0).wall(0).insert();
        seed.row(KEY, 2).pts(4_000).wall(4_100).pdt(10_000).session("S-a").insert();

        Map<String, Instant> found = origins.originsOf(List.of(방송("S-a", at(0))));

        assertThat(found).containsEntry("S-a", at(10_000 - 4_000));
    }

    /**
     * r6 T-13 ⑥ 꼴 — 4D 뒤 60초 재시작. 앞 회차(R1)가 래칫으로 120초 앞섰고 R2 첫 행이 그 래칫을 이어받는다. 시간창(시작 앞
     * 2분)이면 R1 꼬리 행이 먼저 뽑혀 R1 의 값이 나온다. 회차로 자르면 R2 첫 귀속 행에서 선다.
     */
    @Test
    void 재시작한_방송의_기준점은_새_회차의_첫_귀속_행에서_선다() {
        seed.session("S-r1", KEY, "S-r1", null);
        seed.session("S-r2", KEY, "S-r2", null);
        seed.row(KEY, 1).pts(0).wallAndPdt(0).duration(600_000).session("S-r1").insert();
        // R1 꼬리 — 래칫으로 PDT 가 벽시각보다 120초 앞섬
        seed.row(KEY, 2).pts(600_000).wall(480_000).pdt(600_000).duration(4_000).session("S-r1").insert();
        // 60초 뒤 R2 첫 행 — PDT 가 앞 회차 래칫을 이어받음(벽시각 544000 + 120000 앞섬)
        seed.row(KEY, 3).pts(604_000).wall(544_000).pdt(664_000).duration(4_000).session("S-r2").insert();
        방송("S-r1", at(0));

        Map<String, Instant> found = origins.originsOf(List.of(방송("S-r2", at(544_000))));

        assertThat(found).containsEntry("S-r2", at(664_000 - 604_000));
    }

    @Test
    void 회차는_있는데_귀속_행이_아직_없으면_기준점이_없다() {
        seed.session("S-a", KEY, "S-a", null);
        seed.row(KEY, 1).pts(0).wall(0).insert();

        assertThat(origins.originsOf(List.of(방송("S-a", at(0))))).isEmpty();
    }

    @Test
    void 머리_세션_행이_없는_옛_방송은_시간창_첫_조각이다() {
        seed.row(KEY, 1).pts(0).wall(-200_000).insert();
        seed.row(KEY, 2).pts(4_000).wall(-100_000).insert();

        assertThat(origins.originsOf(List.of(방송("S-old", at(0))))).containsEntry("S-old", at(-100_000 - 4_000));
    }

    @Test
    void 한_페이지에_회차_방송과_옛_방송이_섞여도_각자_잰다() {
        seed.session("S-a", KEY, "S-a", null);
        seed.row(KEY, 1).pts(1_000).wallAndPdt(5_000).session("S-a").insert();
        seed.row("OLDKEY", 1).pts(0).wall(7_000).insert();

        Map<String, Instant> found = origins.originsOf(List.of(방송("S-a", at(0)), 방송("S-old", "OLDKEY", at(0))));

        assertThat(found).containsEntry("S-a", at(4_000)).containsEntry("S-old", at(7_000));
    }

    private Broadcast 방송(String streamId, Instant startedAt) {
        return 방송(streamId, KEY, startedAt);
    }

    private Broadcast 방송(String streamId, String ingestKey, Instant startedAt) {
        return broadcasts.save(Broadcast.startedNow(streamId, TestIds.STREAMER, ingestKey, 1L, startedAt, null));
    }
}
