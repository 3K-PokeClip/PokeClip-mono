package com.pokeclip.clip.render.api;

import com.pokeclip.clip.broadcast.Broadcast;
import com.pokeclip.clip.broadcast.BroadcastRepository;
import com.pokeclip.clip.render.RenderFixtures;
import com.pokeclip.clip.support.IntegrationTestSupport;
import com.pokeclip.clip.support.LedgerSeed;
import com.pokeclip.clip.support.LocalStackFixture;
import com.pokeclip.clip.support.TestIds;
import com.pokeclip.clip.support.TestTokens;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static com.pokeclip.clip.support.LedgerSeed.at;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 렌더 주문 문이 장부를 읽어 새 꼴 주문서를 낸다(POK-196 · 설계 r7 5.1 · 9.1 · 9.4 · 16.2 P30). 판정은 {@code CutPlanner} 의
 * 단위 시험이 덮고, 여기는 회차 고르기 · 회차 행 · 장부 이웃 읽기 · 주문서 직렬화를 진짜 장부 · 진짜 큐로 한 번에 잰다.
 *
 * <p><b>P30 일곱 모양은 렌더 R-c1 시험 자료({@code workers/render} {@code RenderPlanCheckTest} P30)와 같은 seq · 같은 좌표다</b> —
 * 이 주문서가 그대로 렌더 계획 검사(설계 5.3)를 통과한다는 것을 두 자리 숫자로 묶는다. ② 는 준비 조각이 {@code pending} 이라
 * 409 로 끝나 주문이 없다. 조각은 모두 4000ms · 판 NULL({@code FILE_HEAD}) · 컷 [머리 + 1000, 머리 + 6000)이다. 시각은
 * {@link LedgerSeed#BASE} 에서 잰 ms다.
 */
@AutoConfigureMockMvc
class RenderOrderPlanTest extends IntegrationTestSupport {

    private static final String RESOLVE = "/internal/editor-delegations/resolve";
    private static final String 요청자 = "4180";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 이 클래스 전용 큐 — 다른 렌더 시험 클래스가 실은 주문서와 섞이지 않게. */
    private static final LocalStackFixture.QueuePair 큐 =
            LocalStackFixture.createStandardQueueWithDlq("jobs-render-plan-test", 3);

    @DynamicPropertySource
    static void renderProperties(DynamicPropertyRegistry registry) {
        registry.add("pokeclip.render.enabled", () -> "true");
        registry.add("pokeclip.render.queue-url", 큐::queueUrl);
        registry.add("pokeclip.render.dlq-url", 큐::dlqUrl);
        registry.add("pokeclip.render.region", LocalStackFixture::region);
        registry.add("pokeclip.render.endpoint", LocalStackFixture::endpoint);
        registry.add("pokeclip.render.output-bucket", () -> "clips-test");
        registry.add("pokeclip.render.segment-bucket", () -> "segments-test");
    }

    private final MockMvc mvc;
    private final JdbcTemplate jdbc;
    private final BroadcastRepository broadcasts;
    private final LedgerSeed seed;

    RenderOrderPlanTest(MockMvc mvc, JdbcTemplate jdbc, BroadcastRepository broadcasts) {
        this.mvc = mvc;
        this.jdbc = jdbc;
        this.broadcasts = broadcasts;
        this.seed = new LedgerSeed(jdbc);
    }

    @BeforeEach
    void 비운다() {
        방송과_카드를_비운다(jdbc);
        AUTH.respondWith(RESOLVE, 200, "{\"relation\":\"OWNER\"}");
        while (LocalStackFixture.receiveAndDelete(큐.queueUrl()) != null) { }
    }

    @AfterEach
    void 내_흔적을_지운다() {
        방송과_카드를_비운다(jdbc);
    }

    // ── P30 일곱 모양 ───────────────────────────────────────────

    /** ① 회차 첫 행 101 · 앞 행 100 비귀속 · 101 새 녹화 아님 → {100, owner 101}. */
    @Test
    void P30_1_첫_묶음_머리_앞_비귀속_행을_준비로_싣는다() throws Exception {
        String streamId = 회차("p30-1");
        seed.row(키("p30-1"), 100).wallAndPdt(96_000).insert();
        귀속(streamId, 101, 100_000);
        귀속(streamId, 102, 104_000);

        JsonNode envelope = 주문서(streamId, 101_000, 106_000);

        assertThat(sources(envelope)).containsExactly("audio_prime:100:owner=101", "segment:101:f:f", "segment:102:f:t");
        assertThat(envelope.get("plan").get("in").toString()).isEqualTo("{\"seq\":101,\"offsetUs\":1000000}");
        assertThat(envelope.get("plan").get("out").toString()).isEqualTo("{\"seq\":102,\"offsetUs\":2000000}");
        assertThat(envelope.get("plan").get("mediaSeqs").toString()).isEqualTo("[101,102]");
        assertThat(envelope.get("plan").get("offsetOrigin").asString()).isEqualTo("FILE_HEAD");
        assertThat(envelope.get("plan").get("subtitles").toString()).isEqualTo("[]");
        assertThat(envelope.get("sourceKeys")).allSatisfy(s -> {
            assertThat(s.get("durationMs").asInt()).isEqualTo(4_000);
            assertThat(s.has("sourceStartAtMs")).isFalse();
        });
    }

    /** ② 준비 조각 200 이 아직 안 올라갔다 → 409 · 주문 없음. */
    @Test
    void P30_2_준비_조각이_아직이면_409다() throws Exception {
        String streamId = 회차("p30-2");
        seed.row(키("p30-2"), 200).wallAndPdt(196_000).pending().insert();
        귀속(streamId, 201, 200_000);
        귀속(streamId, 202, 204_000);

        주문(streamId, 201_000, 206_000).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("source_not_ready"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM clips", Integer.class)).isZero();
        assertThat(LocalStackFixture.receiveAndDelete(큐.queueUrl())).isNull();
    }

    /** ③ 준비 조각 300 이 {@code failed} → 기다리지 않고 준비 없이 주문 · {@code prime_skipped reason=failed}. */
    @Test
    void P30_3_준비_조각이_실패면_없이_싣고_신호를_남긴다() throws Exception {
        String streamId = 회차("p30-3");
        seed.row(키("p30-3"), 300).wallAndPdt(296_000).insert();
        jdbc.update("UPDATE public.stream_segments SET upload_state = 'failed' WHERE stream_id = ? AND seq = 300",
                키("p30-3"));
        귀속(streamId, 301, 300_000);
        귀속(streamId, 302, 304_000);

        try (LogCaptor logs = new LogCaptor()) {
            JsonNode envelope = 주문서(streamId, 301_000, 306_000);

            assertThat(sources(envelope)).containsExactly("segment:301:f:f", "segment:302:f:t");
            assertThat(logs.messages()).contains(
                    "clip.render.prime_skipped streamId=" + streamId + " seq=300 ownerSeq=301 reason=failed");
        }
    }

    /** ④ 머리 401 이 새 녹화 → 앞 행 400 이 있어도 준비를 싣지 않는다 · {@code runHeads} 에 {@code new_recording}. */
    @Test
    void P30_4_새_녹화_머리에는_준비가_없다() throws Exception {
        String streamId = 회차("p30-4");
        seed.row(키("p30-4"), 400).wallAndPdt(396_000).insert();
        seed.row(키("p30-4"), 401).wallAndPdt(400_000).session(streamId).discontinuity().insert();
        귀속(streamId, 402, 404_000);

        try (LogCaptor logs = new LogCaptor()) {
            JsonNode envelope = 주문서(streamId, 401_000, 406_000);

            assertThat(sources(envelope)).containsExactly("segment:401:t:f", "segment:402:f:t");
            assertThat(logs.messages()).anyMatch(m -> m.startsWith("clip.render.cut_planned streamId=" + streamId)
                    && m.endsWith("runHeads=401:new_recording"));
        }
    }

    /** ⑤ A 501 → 비귀속 U 502 → B 503 → 둘째 묶음 머리 B 몫 {502, owner 503}. 501 은 장부 첫 행이라 진짜 시작이다. */
    @Test
    void P30_5_둘째_묶음_머리_앞_비귀속_행을_준비로_싣는다() throws Exception {
        String streamId = 회차("p30-5");
        귀속(streamId, 501, 500_000);
        seed.row(키("p30-5"), 502).wallAndPdt(504_000).insert();
        귀속(streamId, 503, 508_000);

        JsonNode envelope = 주문서(streamId, 501_000, 510_000);

        assertThat(sources(envelope)).containsExactly("segment:501:f:f", "audio_prime:502:owner=503", "segment:503:f:f");
        assertThat(envelope.get("plan").get("mediaSeqs").toString()).isEqualTo("[501,503]");
        assertThat(envelope.get("plan").get("in").toString()).isEqualTo("{\"seq\":501,\"offsetUs\":1000000}");
        assertThat(envelope.get("plan").get("out").toString()).isEqualTo("{\"seq\":503,\"offsetUs\":2000000}");
    }

    /**
     * ⑥ 앞 행 600 이 같은 물리 키 앞 방송 몫(이 방송 장부 구간 시작 앞) → 준비를 싣지 않는다 · {@code prime_skipped
     * reason=other_broadcast}. 그 행이 지워진 뒤(앞 방송 만료)에도 같은 주문 · 같은 신호다(K2 · K4).
     */
    @Test
    void P30_6_앞_방송_몫이면_준비가_없고_그_행이_지워져도_같다() throws Exception {
        String key = 키("p30-6");
        Broadcast previous = broadcasts.save(Broadcast.startedNow("S-p30-6-prev", TestIds.STREAMER, key, 1L, at(0),
                null));
        jdbc.update("UPDATE broadcasts SET status = 'ended', ended_at = ? WHERE id = ?", Timestamp.from(at(60_000)),
                previous.getId());
        String streamId = 회차("p30-6", at(600_000));
        seed.row(key, 600).wallAndPdt(30_000).insert();
        귀속(streamId, 601, 600_000);
        귀속(streamId, 602, 604_000);

        try (LogCaptor logs = new LogCaptor()) {
            JsonNode kept = 주문서(streamId, 601_000, 606_000);
            jdbc.update("DELETE FROM public.stream_segments WHERE stream_id = ? AND seq = 600", key);
            JsonNode purged = 주문서(streamId, 601_000, 606_000);

            assertThat(sources(kept)).containsExactly("segment:601:f:f", "segment:602:f:t");
            assertThat(sources(purged)).isEqualTo(sources(kept));
            assertThat(logs.messages()).filteredOn(m -> m.equals("clip.render.prime_skipped streamId=" + streamId
                    + " seq=- ownerSeq=601 reason=other_broadcast")).hasSize(2);
        }
    }

    /** ⑦ seq 비조밀 — 머리 705 의 장부 바로 앞 행은 702(703 · 704 없음). */
    @Test
    void P30_7_seq_가_비조밀해도_장부_바로_앞_행을_준비로_싣는다() throws Exception {
        String streamId = 회차("p30-7");
        seed.row(키("p30-7"), 702).wallAndPdt(700_000).insert();
        귀속(streamId, 705, 704_000);
        귀속(streamId, 706, 708_000);

        JsonNode envelope = 주문서(streamId, 705_000, 710_000);

        assertThat(sources(envelope)).containsExactly("audio_prime:702:owner=705", "segment:705:f:f", "segment:706:f:t");
    }

    // ── 축 · 원점 · 거절 ──────────────────────────────────────────

    /**
     * 판 2 회차는 PDT 축 좌표와 {@code VIDEO_START} 를 싣는다(kty 2026-11-12 D1). 파일명 시각을 PDT 보다 120초 늦게 심었다 —
     * 파일명 시각으로 고르면 회차 행이 없어 주문이 안 나간다.
     */
    @Test
    void 판_2_회차_주문은_PDT_축_좌표와_영상_첫_표본_원점을_싣는다() throws Exception {
        String streamId = 회차("v2");
        seed.row(키("v2"), 1).wall(120_000).pdt(0).v2().session(streamId).insert();
        seed.row(키("v2"), 2).wall(124_000).pdt(4_000).v2().session(streamId).insert();

        JsonNode envelope = 주문서(streamId, 1_000, 6_000);

        assertThat(envelope.get("plan").get("offsetOrigin").asString()).isEqualTo("VIDEO_START");
        assertThat(envelope.get("plan").get("in").toString()).isEqualTo("{\"seq\":1,\"offsetUs\":1000000}");
        assertThat(envelope.get("plan").get("out").toString()).isEqualTo("{\"seq\":2,\"offsetUs\":2000000}");
    }

    /** 422 {@code reason} 닫힌 목록(설계 r7 9.5) — 회차 첫 행 앞에서 시작하는 컷은 다시 물어도 같다. 표 · 큐에 아무것도 없다. */
    @Test
    void 녹화_밖_컷은_422_이고_사유를_싣는다() throws Exception {
        String streamId = 회차("outside");
        귀속(streamId, 1, 10_000);

        주문(streamId, 5_000, 12_000).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("source_unrenderable"))
                .andExpect(jsonPath("$.reason").value("outside_recording"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM clips", Integer.class)).isZero();
        assertThat(LocalStackFixture.receiveAndDelete(큐.queueUrl())).isNull();
    }

    @Test
    void 컷_안에_영상이_없으면_422_no_footage_다() throws Exception {
        String streamId = 회차("nofootage");
        귀속(streamId, 1, 0);
        seed.row(키("nofootage"), 2).wallAndPdt(10_000).session(streamId).discontinuity().insert();

        주문(streamId, 5_000, 9_000).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.reason").value("no_footage"));
    }

    // ── 도우미 ──────────────────────────────────────────────────

    private static String 키(String name) {
        return "K-" + name;
    }

    /** 머리 세션 하나짜리 닫힌 회차의 방송. @return 방송 번호(= 머리 세션) */
    private String 회차(String name) {
        return 회차(name, at(0));
    }

    private String 회차(String name, Instant startedAt) {
        String streamId = "S-" + name;
        seed.session(streamId, 키(name), streamId, null);
        seed.endSession(streamId, true);
        broadcasts.save(Broadcast.startedNow(streamId, TestIds.STREAMER, 키(name), 1L, startedAt, null));
        return streamId;
    }

    /** 회차 귀속 행(판 NULL · 올라감 · 새 녹화 아님 · 파일명 시각 = PDT). */
    private void 귀속(String streamId, long seq, long ms) {
        seed.row("K-" + streamId.substring(2), seq).wallAndPdt(ms).session(streamId).insert();
    }

    private ResultActions 주문(String streamId, long inMs, long outMs) throws Exception {
        long recipeId = RenderFixtures.편집본을_넣는다(jdbc, streamId, at(inMs).toEpochMilli(), at(outMs).toEpochMilli());
        return mvc.perform(post("/api/clip/broadcasts/" + streamId + "/recipes/" + recipeId + "/renders")
                .header("Authorization", "Bearer " + TestTokens.access(요청자)));
    }

    private JsonNode 주문서(String streamId, long inMs, long outMs) throws Exception {
        주문(streamId, inMs, outMs).andExpect(status().isCreated());
        String body = LocalStackFixture.receiveAndDelete(큐.queueUrl());
        assertThat(body).as("주문서가 큐에 안 실렸다").isNotNull();
        return MAPPER.readTree(body);
    }

    /** {@code 역할:seq[:isDiscontinuity:followsLedgerPrevious | :owner=ownerSeq]} 꼴로 줄인 조각 목록. */
    private static List<String> sources(JsonNode envelope) {
        List<String> out = new ArrayList<>();
        for (JsonNode s : envelope.get("sourceKeys")) {
            String head = s.get("role").asString() + ":" + s.get("seq").asLong();
            out.add(s.has("ownerSeq") ? head + ":owner=" + s.get("ownerSeq").asLong()
                    : head + ":" + flag(s, "isDiscontinuity") + ":" + flag(s, "followsLedgerPrevious"));
        }
        return out;
    }

    private static String flag(JsonNode s, String name) {
        return s.get(name).asBoolean() ? "t" : "f";
    }
}
