package com.pokeclip.clip.thumbnail;

import com.pokeclip.clip.broadcast.Broadcast;
import com.pokeclip.clip.broadcast.BroadcastRepository;
import com.pokeclip.clip.segment.TimelineOriginReader;
import com.pokeclip.clip.support.IntegrationTestSupport;
import com.pokeclip.clip.support.LedgerSeed;
import com.pokeclip.clip.support.TestIds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static com.pokeclip.clip.support.LedgerSeed.at;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 라이브 사진(POK-196 S-c4 · r6 S-c4 라이브). 회차가 있는 방송은 <b>회차 귀속 행</b> 가운데 올라간 seq 최대 행의 가운데를 찍고,
 * 원점 표지는 그 행의 판이다. 머리 세션 행이 없는 옛 방송은 지금 조건(시작 2분 전 이후 벽시각 최신) 그대로다
 * ({@code ThumbnailFlowTest} 라이브 시험이 그 갈래다).
 *
 * <p>시각은 {@link LedgerSeed#BASE}에서 잰 ms다.
 */
class LiveThumbnailRoundTest extends IntegrationTestSupport {

    private static final String 조각_창고 = "segments-test";
    private static final String 사진_창고 = "clips-test";

    private final JdbcTemplate jdbc;
    private final ThumbnailTargets targets;
    private final ThumbnailRepository thumbnails;
    private final BroadcastRepository broadcasts;
    private final TimelineOriginReader origins;
    private final ObjectMapper mapper;
    private final LedgerSeed seed;

    private final List<JsonNode> sent = new ArrayList<>();

    LiveThumbnailRoundTest(JdbcTemplate jdbc, ThumbnailTargets targets, ThumbnailRepository thumbnails,
                           BroadcastRepository broadcasts, TimelineOriginReader origins, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.targets = targets;
        this.thumbnails = thumbnails;
        this.broadcasts = broadcasts;
        this.origins = origins;
        this.mapper = mapper;
        this.seed = new LedgerSeed(jdbc);
    }

    @BeforeEach
    void 비운다() {
        방송과_카드를_비운다(jdbc);
        sent.clear();
    }

    /**
     * 회차 밖 행(비귀속 · 다른 세션)이 더 늦게 올라와도 회차 귀속 행만 본다. 아직 안 올라간 귀속 행은 건너뛴다. 옛 조건(벽시각
     * 최신)이면 비귀속 행을 찍는다.
     */
    @Test
    void 회차_귀속_행_가운데_올라간_seq_최대_행을_찍는다() {
        방송("S-live", "LIVEKEY");
        seed.session("S-live", "LIVEKEY", "S-live", null);
        seed.session("S-other", "LIVEKEY", "S-other", null);
        seed.row("LIVEKEY", 1).pts(0).wallAndPdt(0).session("S-live").insert();
        seed.row("LIVEKEY", 2).pts(4_000).wallAndPdt(4_000).session("S-live").insert();
        seed.row("LIVEKEY", 3).pts(8_000).wallAndPdt(8_000).session("S-live").pending().insert();
        seed.row("LIVEKEY", 4).pts(12_000).wallAndPdt(12_000).insert();
        seed.row("LIVEKEY", 5).pts(16_000).wallAndPdt(16_000).session("S-other").insert();

        sweeper().sweepLive();

        assertThat(sent).hasSize(1);
        JsonNode job = sent.get(0);
        assertThat(job.path("kind").asString()).isEqualTo("live");
        assertThat(job.path("targetId").asString()).isEqualTo("S-live");
        assertThat(job.path("source").path("s3Key").asString()).isEqualTo("seg/LIVEKEY/2");
        assertThat(job.path("source").path("offsetMs").asLong()).isEqualTo(2_000);
        assertThat(job.path("source").path("offsetOrigin").asString()).isEqualTo("FILE_HEAD");
        assertThat(Instant.parse(job.path("capturedAt").asString())).isEqualTo(at(6_000));
    }

    /** 고르는 순서는 seq 다 — 벽시각이 거꾸로 간 행(판 NULL 벽시각 역행)이 있어도 seq 최대 행이다. */
    @Test
    void 벽시각이_거꾸로_가도_seq_최대_행을_찍는다() {
        방송("S-back", "BACKKEY");
        seed.session("S-back", "BACKKEY", "S-back", null);
        seed.row("BACKKEY", 1).pts(0).wallAndPdt(10_000).session("S-back").insert();
        seed.row("BACKKEY", 2).pts(4_000).wall(9_000).pdt(14_000).session("S-back").insert();

        sweeper().sweepLive();

        assertThat(sent).extracting(job -> job.path("source").path("s3Key").asString())
                .containsExactly("seg/BACKKEY/2");
        assertThat(Instant.parse(sent.get(0).path("capturedAt").asString())).isEqualTo(at(16_000));
    }

    /** 판 2 행은 원점 표지가 {@code VIDEO_START}다(가운데 자리는 영상 첫 표본부터 잰다 · PDT 가 그 자리를 가리킨다). */
    @Test
    void 판_2_행의_원점은_영상_시작이다() {
        방송("S-v2", "V2KEY");
        seed.session("S-v2", "V2KEY", "S-v2", null);
        seed.row("V2KEY", 1).pts(0).wall(0).pdt(30).session("S-v2").v2().insert();

        sweeper().sweepLive();

        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).path("source").path("offsetOrigin").asString()).isEqualTo("VIDEO_START");
        assertThat(Instant.parse(sent.get(0).path("capturedAt").asString())).isEqualTo(at(2_030));
    }

    /** 회차 둘이 한 순회에 섞여도 방송마다 제 회차 행이다. */
    @Test
    void 방송마다_제_회차_행을_찍는다() {
        방송("S-a", "AKEY");
        seed.session("S-a", "AKEY", "S-a", null);
        seed.row("AKEY", 1).pts(0).wallAndPdt(0).session("S-a").insert();
        방송("S-b", "BKEY");
        seed.session("S-b", "BKEY", "S-b", null);
        seed.row("BKEY", 1).pts(0).wallAndPdt(0).session("S-b").insert();
        seed.row("BKEY", 2).pts(4_000).wallAndPdt(4_000).session("S-b").insert();

        sweeper().sweepLive();

        assertThat(sent).extracting(job -> job.path("targetId").asString() + "=" + job.path("source").path("s3Key")
                .asString()).containsExactlyInAnyOrder("S-a=seg/AKEY/1", "S-b=seg/BKEY/2");
    }

    private void 방송(String streamId, String ingestKey) {
        broadcasts.save(Broadcast.startedNow(streamId, TestIds.STREAMER, ingestKey, 1L, at(0), null));
    }

    private ThumbnailSweeper sweeper() {
        ThumbnailQueueClient queue = new ThumbnailQueueClient(null, "unused") {
            @Override
            public void send(String body) {
                sent.add(mapper.readTree(body));
            }
        };
        return new ThumbnailSweeper(targets, thumbnails, queue, broadcasts, origins, mapper, 조각_창고, 사진_창고,
                Clock.fixed(at(50_000_000), ZoneOffset.UTC));
    }
}
