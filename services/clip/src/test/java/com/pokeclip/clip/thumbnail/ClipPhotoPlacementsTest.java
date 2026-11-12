package com.pokeclip.clip.thumbnail;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.pokeclip.clip.broadcast.Broadcast;
import com.pokeclip.clip.broadcast.BroadcastRepository;
import com.pokeclip.clip.jumpcard.CardPositionReader.Scene;
import com.pokeclip.clip.segment.TimelineOriginReader;
import com.pokeclip.clip.support.IntegrationTestSupport;
import com.pokeclip.clip.support.LedgerSeed;
import com.pokeclip.clip.support.TestIds;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.pokeclip.clip.support.LedgerSeed.at;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 완성 사진 자리(POK-196 S-c5 · PDT 설계 r7 8.3 · 예측 P14). 배치표가 있는 완성 영상은 <b>주문서의 계획 in · out</b> 을 카드 축
 * ({@code start_pts})으로 옮긴 구간 안 최고 점수 카드를 고르고, 그 카드의 장면(카드 장면 함수 — 카드 썸네일과 같은 장면)을
 * {@link ResultPlacements} 로 결과 영상 ms 에 놓는다. 카드가 없으면 결과 가운데다. 배치표가 없는 영상(옛 꼴)은 옛 식 그대로다
 * ({@code ThumbnailFlowTest} 의 완성 영상 시험들).
 *
 * <p>시각은 {@link LedgerSeed#BASE}에서 잰 ms다. 순회기는 손으로 만들어 한 번씩 돌린다.
 */
class ClipPhotoPlacementsTest extends IntegrationTestSupport {

    private static final String 사진_창고 = "clips-test";

    private final JdbcTemplate jdbc;
    private final ThumbnailTargets targets;
    private final ThumbnailRepository thumbnails;
    private final BroadcastRepository broadcasts;
    private final TimelineOriginReader origins;
    private final ObjectMapper mapper;
    private final LedgerSeed seed;

    private final List<JsonNode> sent = new ArrayList<>();

    ClipPhotoPlacementsTest(JdbcTemplate jdbc, ThumbnailTargets targets, ThumbnailRepository thumbnails,
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
     * 판 2 회차 — 계획 in (10, 1,000ms) · out (13, 2,000ms) 이 카드 축 구간 [1,000, 14,000) 이다. 구간 안 최고 카드(9,500 = 조각 12
     * 의 1,500)를 배치표로 놓으면 6,700 + 1,500 = 8,200 이다(조각 12 는 이음 겹침으로 300ms 당겨 놓였다 — 카드 축 거리 8,500 과
     * 다르다). 구간 앞(500) · 구간 끝(14,000) 카드는 점수가 높아도 안 본다(끝 배타).
     */
    @Test
    void 계획_구간_안_최고_카드의_장면을_배치표로_결과에_놓는다() {
        시드_판2("S-pa", "PA");
        카드("S-pa", 9_500, 90);
        카드("S-pa", 500, 99);
        카드("S-pa", 14_000, 99);
        long clip = 완성_영상("S-pa", 배치표_판2(), "{\"seq\":10,\"offsetUs\":1000000}", "{\"seq\":13,\"offsetUs\":2000000}");

        try (LogCaptor logs = new LogCaptor()) {
            디버그로(() -> sweeper().sweepClips());

            assertThat(logs.messages()).contains("clip.thumbnail.clip_photo clipId=" + clip + " source=placements card=scene");
        }
        assertThat(장면들()).containsExactly(8_200L);
    }

    /**
     * 재접속 틈 안 카드(9,000 — 조각 21 끝 8,000 과 조각 22 시작 10,000 사이)는 카드 썸네일과 같은 장면 — 뒤 조각 첫 화면 (22, 0) —
     * 이고 결과에서는 이어 붙은 자리 7,500 이다(틈은 결과에 없고 조각 21 꼬리 0.5초는 결과 끝 소유로 빠졌다). 옛 식(기준점 + 카드
     * − 컷 시작)이면 카드 축 거리 8,000 이다.
     */
    @Test
    void 틈_안_카드는_뒤_조각_첫_화면이다() {
        broadcasts.save(Broadcast.startedNow("S-pg", TestIds.STREAMER, "PG", 1L, at(0), null));
        seed.session("S-pg", "PG", "S-pg", null);
        seed.session("S-pg2", "PG", "S-pg", "S-pg");
        seed.row("PG", 20).pts(0).wallAndPdt(100_000).session("S-pg").v2().insert();
        seed.row("PG", 21).pts(4_000).wallAndPdt(104_000).session("S-pg").v2().insert();
        seed.row("PG", 22).pts(10_000).wallAndPdt(110_000).session("S-pg2").v2().discontinuity().insert();
        seed.row("PG", 23).pts(14_000).wallAndPdt(114_000).session("S-pg2").v2().insert();
        카드("S-pg", 9_000, 90);
        완성_영상("S-pg", """
                {"placements":[
                  {"seq":20,"zeroAtUs":0,"fromUs":0,"toUs":4000000,"headLeadUs":0},
                  {"seq":21,"zeroAtUs":4000000,"fromUs":0,"toUs":3500000,"headLeadUs":0},
                  {"seq":22,"zeroAtUs":7500000,"fromUs":0,"toUs":4000000,"headLeadUs":0},
                  {"seq":23,"zeroAtUs":11500000,"fromUs":0,"toUs":2000000,"headLeadUs":0}],
                 "lengthUs":13500000}""", "{\"seq\":20,\"offsetUs\":0}", "{\"seq\":23,\"offsetUs\":2000000}");

        sweeper().sweepClips();

        assertThat(장면들()).containsExactly(7_500L);
    }

    /**
     * 판 NULL 회차 — 원점은 파일 머리라 m(21,333µs)을 빼고 놓는다. 끝이 {@code toSegmentEnd} 면 구간 끝은 조각 31 의 끝(8,000)이라
     * 7,950 카드가 든다. 7,950 = 조각 31 의 3,950 → 4,000,000 + 3,950,000 − 21,333 → 7,928.
     */
    @Test
    void 판_NULL_은_파일_머리_원점이고_끝까지_컷은_조각_끝까지가_구간이다() {
        broadcasts.save(Broadcast.startedNow("S-pn", TestIds.STREAMER, "PN", 1L, at(0), null));
        seed.session("S-pn", "PN", "S-pn", null);
        seed.row("PN", 30).pts(0).wallAndPdt(100_000).session("S-pn").insert();
        seed.row("PN", 31).pts(4_000).wallAndPdt(104_000).session("S-pn").insert();
        seed.row("PN", 32).pts(8_000).wallAndPdt(108_000).session("S-pn").insert();
        카드("S-pn", 5_000, 50);
        카드("S-pn", 7_950, 90);
        카드("S-pn", 8_000, 99);
        완성_영상("S-pn", """
                {"placements":[
                  {"seq":30,"zeroAtUs":0,"fromUs":0,"toUs":4000000,"headLeadUs":21333},
                  {"seq":31,"zeroAtUs":4000000,"fromUs":-21333,"toUs":4000000,"headLeadUs":21333}],
                 "lengthUs":8000000}""", "{\"seq\":30,\"offsetUs\":0}", "{\"seq\":31,\"toSegmentEnd\":true}");

        sweeper().sweepClips();

        assertThat(장면들()).containsExactly(7_928L);
    }

    @Test
    void 구간_안에_카드가_없으면_결과_가운데다() {
        시드_판2("S-pm", "PM");
        카드("S-pm", 500, 99);
        long clip = 완성_영상("S-pm", 배치표_판2(), "{\"seq\":10,\"offsetUs\":1000000}", "{\"seq\":13,\"offsetUs\":2000000}");

        try (LogCaptor logs = new LogCaptor()) {
            디버그로(() -> sweeper().sweepClips());

            assertThat(logs.messages()).contains("clip.thumbnail.clip_photo clipId=" + clip + " source=placements card=none");
        }
        assertThat(장면들()).containsExactly(6_350L);
    }

    /**
     * 배치표가 깨진 영상은 옛 식으로 떨어지고 WARN 을 남긴다. 한 영상이 깨져도 같은 묶음의 다른 영상 사진은 나간다(순회 전체가
     * 멈추면 그 영상이 묶음에 남는 동안 다른 영상 사진도 멈춘다).
     */
    @Test
    void 배치표가_깨진_영상은_옛_식으로_찍고_묶음의_다른_영상도_찍는다() {
        시드_판2("S-pb", "PB");
        카드("S-pb", 9_500, 90);
        long good = 완성_영상("S-pb", 배치표_판2(), "{\"seq\":10,\"offsetUs\":1000000}", "{\"seq\":13,\"offsetUs\":2000000}");
        long broken = 완성_영상("S-pb", "{\"placements\":\"x\"}", "{\"seq\":10,\"offsetUs\":1000000}",
                "{\"seq\":13,\"offsetUs\":2000000}");

        try (LogCaptor logs = new LogCaptor()) {
            sweeper().sweepClips();

            assertThat(logs.levelOf("clip.thumbnail.clip_photo clipId=" + broken + " source=invalid"))
                    .isEqualTo(Level.WARN);
        }
        assertThat(sent).extracting(job -> job.path("targetId").asString())
                .containsExactlyInAnyOrder(String.valueOf(good), String.valueOf(broken));
        assertThat(sent).filteredOn(job -> job.path("targetId").asString().equals(String.valueOf(good)))
                .extracting(job -> job.path("source").path("offsetMs").asLong()).containsExactly(8_200L);
    }

    /**
     * 장면을 읽다 던지면(장부 불변식 위반 · 장부 읽기 실패) 그 영상은 결과 가운데로 찍고 WARN 을 남긴다 — 순회가 던지면 그 영상이
     * 묶음에 남는 동안 같은 묶음의 다른 영상 사진도 멈춘다.
     */
    @Test
    void 장면을_읽다_실패하면_결과_가운데로_찍고_순회는_계속된다() {
        시드_판2("S-pf", "PF");
        카드("S-pf", 9_500, 90);
        long clip = 완성_영상("S-pf", 배치표_판2(), "{\"seq\":10,\"offsetUs\":1000000}", "{\"seq\":13,\"offsetUs\":2000000}");
        ThumbnailTargets failing = new ThumbnailTargets(jdbc, broadcasts, null, null) {
            @Override
            Optional<Scene> topCardScene(String streamId, PlanCut cut) {
                throw new IllegalStateException("회차 안 판이 섞였다");
            }
        };

        try (LogCaptor logs = new LogCaptor()) {
            sweeper(failing).sweepClips();

            assertThat(logs.levelOf("clip.thumbnail.clip_photo clipId=" + clip + " source=placements card=failed"))
                    .isEqualTo(Level.WARN);
        }
        assertThat(장면들()).containsExactly(6_350L);
    }

    // ── 도우미 ──────────────────────────────────────────────────

    /** 판 2 회차 — 조각 10–13(위치 0 · 4,000 · 8,000 · 12,000). */
    private void 시드_판2(String streamId, String key) {
        broadcasts.save(Broadcast.startedNow(streamId, TestIds.STREAMER, key, 1L, at(0), null));
        seed.session(streamId, key, streamId, null);
        for (int i = 0; i < 4; i++) {
            seed.row(key, 10 + i).pts(i * 4_000L).wallAndPdt(100_000 + i * 4_000L).session(streamId).v2().insert();
        }
    }

    /** in (10, 1,000ms) · out (13, 2,000ms) 결과 — 조각 12 부터 300ms 당겨 놓임(이음 겹침). 결과 12.7초. */
    private static String 배치표_판2() {
        return """
                {"placements":[
                  {"seq":10,"zeroAtUs":-1000000,"fromUs":1000000,"toUs":4000000,"headLeadUs":0},
                  {"seq":11,"zeroAtUs":3000000,"fromUs":0,"toUs":4000000,"headLeadUs":0},
                  {"seq":12,"zeroAtUs":6700000,"fromUs":0,"toUs":4000000,"headLeadUs":0},
                  {"seq":13,"zeroAtUs":10700000,"fromUs":0,"toUs":2000000,"headLeadUs":0}],
                 "lengthUs":12700000}""";
    }

    /** 사진 신호는 DEBUG 라(설계 r7 15절) 그 동안만 순회기 로거를 DEBUG 로 연다(잡는 쪽은 뿌리를 INFO 로 둔다). */
    private static void 디버그로(Runnable run) {
        Logger logger = (Logger) LoggerFactory.getLogger(ThumbnailSweeper.class);
        Level previous = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        try {
            run.run();
        } finally {
            logger.setLevel(previous);
        }
    }

    private List<Long> 장면들() {
        return sent.stream().map(job -> job.path("source").path("offsetMs").asLong()).toList();
    }

    private ThumbnailSweeper sweeper() {
        return sweeper(targets);
    }

    private ThumbnailSweeper sweeper(ThumbnailTargets using) {
        ThumbnailQueueClient queue = new ThumbnailQueueClient(null, "unused") {
            @Override
            public void send(String body) {
                sent.add(mapper.readTree(body));
            }
        };
        return new ThumbnailSweeper(using, thumbnails, queue, broadcasts, origins, mapper, "segments-test", 사진_창고,
                Clock.fixed(Instant.parse("2026-11-12T00:00:00Z"), ZoneOffset.UTC));
    }

    private void 카드(String streamId, long ts, int score) {
        jdbc.update("""
                        INSERT INTO jump_cards (stream_id, source, event_id, stream_timestamp_ms, window_start_ms, window_end_ms, score, event_seq)
                        VALUES (?, 'auto', ?, ?, ?, ?, ?, 0)""",
                streamId, "evt-" + streamId + "-" + ts, ts, ts - 500, ts + 500, score);
    }

    /** 배치표가 있는 완성 영상 + 그 영상을 만든 새 꼴 주문(계획 in · out). @return 영상 번호 */
    private long 완성_영상(String streamId, String placements, String planIn, String planOut) {
        long recipeId = jdbc.queryForObject("""
                        INSERT INTO recipes (stream_id, creator_id, schema_version, recipe_version, cut_in_at_ms, cut_out_at_ms,
                                             outputs, audio, updated_at)
                        VALUES (?, '4180', 1, 1, ?, ?, '[]'::jsonb, '{}'::jsonb, now())
                        RETURNING id""", Long.class, streamId, at(101_000).toEpochMilli(), at(114_000).toEpochMilli());
        long clipId = jdbc.queryForObject("""
                        INSERT INTO clips (stream_id, recipe_id, recipe_version, requested_by, status, outputs, placements,
                                           updated_at)
                        VALUES (?, ?, 1, '4180', 'rendered', CAST(? AS jsonb), CAST(? AS jsonb), now()) RETURNING id""",
                Long.class, streamId, recipeId, "[{\"outputId\":\"o1\",\"kind\":\"video\",\"s3Key\":\"clips/x/t/o1.mp4\"}]",
                placements);
        String payload = mapper.writeValueAsString(Map.of(
                "recipe", Map.of("cut", Map.of("inAtMs", at(101_000).toEpochMilli(), "outAtMs", at(114_000).toEpochMilli())),
                "plan", Map.of("offsetOrigin", "VIDEO_START", "in", mapper.readTree(planIn), "out", mapper.readTree(planOut))));
        jdbc.update("""
                        INSERT INTO render_jobs (id, clip_id, status, payload, published_at, updated_at)
                        VALUES (gen_random_uuid(), ?, 'succeeded', CAST(? AS jsonb), now(), now())""", clipId, payload);
        return clipId;
    }
}
