package com.pokeclip.clip.render.api;

import com.pokeclip.clip.render.DlqReconciler;
import com.pokeclip.clip.render.RenderFixtures;
import com.pokeclip.clip.render.RenderPublisher;
import com.pokeclip.clip.support.IntegrationTestSupport;
import com.pokeclip.clip.support.LocalStackFixture;
import com.pokeclip.clip.support.NotFoundFloor;
import com.pokeclip.clip.support.TestTokens;
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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 영상 만들기 주문 문(POK-125). 재는 것은 다섯 — <b>주문서가 진짜 큐에 실리고 그 안에 조각 목록·편집본이 있는가</b> ·
 * 더블클릭이 주문을 두 번 안 만드는가 · 조각이 덜 올라왔으면 안 주문하는가 · 못 실은 주문을 outbox가 다시 싣는가 ·
 * 실패 큐에 떨어진 주문이 SWEPT로 닫히는가. 자격 판정 갈래는 다른 문과 같은 판정기라 둘만 잰다.
 *
 * <p>큐는 LocalStack의 진짜 SQS다. 이 클래스만 주문줄을 켜므로 컨텍스트 하나를 더 쓴다(출입증 시험과 같은 모양).
 */
@AutoConfigureMockMvc
class RenderRequestControllerTest extends IntegrationTestSupport {

    private static final String RESOLVE = "/internal/editor-delegations/resolve";
    private static final String 요청자 = "4180";
    private static final String 내_방송 = "s-render";
    private static final String 다른_방송 = "s-render-2";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long 바닥_ms = NotFoundFloor.FLOOR.toMillis();

    private static final LocalStackFixture.QueuePair 큐 = LocalStackFixture.createStandardQueueWithDlq("jobs-render-test", 3);

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
    private final RenderPublisher publisher;
    private final DlqReconciler reconciler;

    RenderRequestControllerTest(MockMvc mvc, JdbcTemplate jdbc, RenderPublisher publisher, DlqReconciler reconciler) {
        this.mvc = mvc;
        this.jdbc = jdbc;
        this.publisher = publisher;
        this.reconciler = reconciler;
    }

    private long 편집본;

    @BeforeEach
    void 앞_테스트의_흔적을_지운다() {
        방송과_카드를_비운다(jdbc);
        RenderFixtures.방송을_넣는다(jdbc, 내_방송);
        RenderFixtures.방송을_넣는다(jdbc, 다른_방송);
        편집본 = RenderFixtures.편집본을_넣는다(jdbc, 내_방송, RenderFixtures.CUT_IN, RenderFixtures.CUT_OUT);
        // 큐를 비운다 — 앞 시험이 실은 주문서가 남아 있으면 「이번 주문이 실렸다」를 못 잰다.
        while (LocalStackFixture.receiveAndDelete(큐.queueUrl()) != null) { }
        while (LocalStackFixture.receiveAndDelete(큐.dlqUrl()) != null) { }
    }

    /** 다른 시험 클래스 열다섯이 broadcasts를 직접 지운다 — 내 자식 줄을 내가 치운다(RecipeControllerTest와 같은 이유). */
    @AfterEach
    void 내_흔적을_지운다() {
        jdbc.update("DELETE FROM render_job_events");
        jdbc.update("DELETE FROM render_jobs");
        jdbc.update("DELETE FROM clips");
        jdbc.update("DELETE FROM recipes");
        jdbc.update("DELETE FROM stream_segments");
    }

    // ── 주문 ─────────────────────────────────────────────────────

    /**
     * 주문서가 <b>큐에 실제로</b> 실리고, 그 안에 컷을 덮는 조각 목록(재생 축)·편집본·좌표가 있다. 조각은 재생 축과 녹화 축을
     * 일부러 1시간 어긋나게 심었다 — 조회가 녹화 축을 보면 조각을 못 찾아 409가 나고, 이 시험은 그 자리에서 빨간불이다.
     */
    @Test
    void 주문하면_201이고_주문서가_큐에_실린다() throws Exception {
        볼_수_있다("OWNER");
        RenderFixtures.조각을_넣는다(jdbc, 내_방송, 0);

        String 응답 = 본문(주문(내_방송, 편집본)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("queued"))
                .andExpect(jsonPath("$.recipeId").value(편집본))
                .andExpect(jsonPath("$.recipeVersion").value(1))
                .andExpect(jsonPath("$.requestedBy").value(요청자))
                .andExpect(jsonPath("$.progress.percent").value(0))
                .andExpect(jsonPath("$.outputs").value(nullValue())));
        long clipId = MAPPER.readTree(응답).get("id").asLong();
        String jobId = MAPPER.readTree(응답).get("progress").get("jobId").asString();

        String 주문서 = LocalStackFixture.receiveAndDelete(큐.queueUrl());
        assertThat(주문서).as("주문서가 큐에 안 실렸다").isNotNull();
        JsonNode 봉투 = MAPPER.readTree(주문서);
        assertThat(봉투.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(봉투.get("jobType").asString()).isEqualTo("RENDER");
        assertThat(봉투.get("jobId").asString()).isEqualTo(jobId);
        assertThat(봉투.get("clipId").asString()).isEqualTo(String.valueOf(clipId));
        assertThat(봉투.get("idempotencyKey").asString()).isEqualTo(clipId + ":RENDER:1");
        assertThat(봉투.get("outputPrefix").asString()).isEqualTo("s3://clips-test/clips/" + clipId);
        assertThat(봉투.get("recipe").get("cut").get("inAtMs").asLong()).isEqualTo(RenderFixtures.CUT_IN);
        assertThat(봉투.get("recipe").get("outputs").get(0).get("outputId").asString()).isEqualTo("o1");
        assertThat(봉투.get("trackManifest").get("manifestVersion").asInt()).isEqualTo(3);
        // 45초 컷을 4초 조각으로 덮으면 앞뒤 경계 포함 열둘 — 컷과 안 겹치는 앞뒤 여유 조각은 빠진다.
        JsonNode sources = 봉투.get("sourceKeys");
        assertThat(sources.size()).isEqualTo(12);
        assertThat(sources.get(0).get("bucket").asString()).isEqualTo("segments-test");
        assertThat(sources.get(0).get("s3Key").asString()).isEqualTo("streams/" + 내_방송 + "/seg_2.m4s");
        assertThat(sources.get(0).get("sourceStartAtMs").asLong()).isEqualTo(RenderFixtures.CUT_IN);
        assertThat(sources.get(11).get("sourceStartAtMs").asLong()).isLessThan(RenderFixtures.CUT_OUT);

        assertThat(jdbc.queryForObject("SELECT published_at IS NOT NULL FROM render_jobs WHERE id = ?::uuid",
                Boolean.class, jobId)).as("발행 시각이 안 적혔다 — outbox가 같은 주문을 또 보낸다").isTrue();
        assertThat(AUTH.callCount()).isEqualTo(1);
    }

    /** 더블클릭 — 같은 편집본 같은 판이 진행 중이면 새 주문 없이 그것을 돌려준다(200). 큐에도 한 통뿐이다. */
    @Test
    void 같은_편집본을_두_번_주문하면_두_번째는_200이고_같은_영상이다() throws Exception {
        볼_수_있다("OWNER");
        RenderFixtures.조각을_넣는다(jdbc, 내_방송, 0);

        long 첫째 = MAPPER.readTree(본문(주문(내_방송, 편집본).andExpect(status().isCreated()))).get("id").asLong();
        long 둘째 = MAPPER.readTree(본문(주문(내_방송, 편집본).andExpect(status().isOk()))).get("id").asLong();

        assertThat(둘째).isEqualTo(첫째);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM clips", Integer.class)).isEqualTo(1);
        assertThat(LocalStackFixture.receiveAndDelete(큐.queueUrl())).isNotNull();
        assertThat(LocalStackFixture.receiveAndDelete(큐.queueUrl())).as("두 번째 주문서가 실렸다").isNull();
    }

    /** 끝난 주문(실패)은 자리를 비운다 — 다시 누르면 새 영상이다. 계약1 「종결 시 선점 해제」. */
    @Test
    void 끝난_주문은_다시_주문할_수_있다() throws Exception {
        볼_수_있다("OWNER");
        RenderFixtures.조각을_넣는다(jdbc, 내_방송, 0);
        long 첫째 = MAPPER.readTree(본문(주문(내_방송, 편집본).andExpect(status().isCreated()))).get("id").asLong();
        jdbc.update("UPDATE clips SET status = 'failed' WHERE id = ?", 첫째);

        long 둘째 = MAPPER.readTree(본문(주문(내_방송, 편집본).andExpect(status().isCreated()))).get("id").asLong();

        assertThat(둘째).isNotEqualTo(첫째);
    }

    /** 컷 가운데 조각 하나가 아직 안 올라왔다 — 잘라서 주문하지 않는다. 표에도 큐에도 아무것도 안 남는다. */
    @Test
    void 조각이_덜_올라왔으면_409이고_아무것도_안_남는다() throws Exception {
        볼_수_있다("OWNER");
        RenderFixtures.조각을_넣는다(jdbc, 내_방송, 5);

        주문(내_방송, 편집본).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("source_not_ready"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM clips", Integer.class)).isZero();
        assertThat(LocalStackFixture.receiveAndDelete(큐.queueUrl())).isNull();
    }

    /**
     * 송출이 끊겼다 이어지면 번호는 이어지는데 재생 시각이 건너뛴다. 조립기(번호 연속)만 믿으면 201이 나가고 일꾼이 가운데가
     * 빈 영상을 만든다 — 1판 codex P1. 조각 6과 7 사이를 10초 벌린다(번호는 그대로).
     */
    @Test
    void 번호는_이어져도_재생_시각에_구멍이_있으면_409다() throws Exception {
        볼_수_있다("OWNER");
        RenderFixtures.조각을_넣는다(jdbc, 내_방송, 0);
        jdbc.update("UPDATE stream_segments SET playback_pdt = playback_pdt + interval '10 seconds' WHERE stream_id = ? AND seq >= 7", 내_방송);

        주문(내_방송, 편집본).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("source_not_ready"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM clips", Integer.class)).isZero();
    }

    @Test
    void 템플릿은_주문할_수_없다() throws Exception {
        볼_수_있다("OWNER");
        long 템플릿 = RenderFixtures.편집본을_넣는다(jdbc, 내_방송, null, null);

        주문(내_방송, 템플릿).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"))
                .andExpect(jsonPath("$.field").value("cut"));
    }

    @Test
    void 없는_편집본은_404다() throws Exception {
        볼_수_있다("OWNER");
        주문(내_방송, 999_999L).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("recipe_not_found"));
    }

    /** 자격 없음과 없는 방송은 같은 본문. 규칙 검사(조각 준비)는 그 뒤라 조각을 안 심어도 404다. */
    @Test
    void 자격_없음과_없는_방송은_같은_404다() throws Exception {
        볼_수_없다();
        String 자격_없음 = 본문(주문(내_방송, 편집본).andExpect(status().isNotFound()));
        볼_수_있다("OWNER");
        String 없는_방송 = 본문(주문("s-없는방송", 편집본).andExpect(status().isNotFound()));

        assertThat(자격_없음).isEqualTo(없는_방송).contains("broadcast_not_found");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM clips", Integer.class)).isZero();
    }

    // ── 하나 보기 ─────────────────────────────────────────────────

    @Test
    void 영상_하나를_본다_그리고_다른_방송_번호는_404다() throws Exception {
        볼_수_있다("OWNER");
        RenderFixtures.조각을_넣는다(jdbc, 내_방송, 0);
        long clipId = MAPPER.readTree(본문(주문(내_방송, 편집본).andExpect(status().isCreated()))).get("id").asLong();

        하나(내_방송, clipId).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(clipId))
                .andExpect(jsonPath("$.status").value("queued"));
        하나(다른_방송, clipId).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("clip_not_found"));
        하나(내_방송, 999_999L).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("clip_not_found"));
    }

    @Test
    void 없는_번호의_404가_바닥_시간을_채운다() throws Exception {
        볼_수_있다("OWNER");
        double 최소 = Double.MAX_VALUE;
        for (int i = 0; i < 3; i++) {
            long 시작 = System.nanoTime();
            하나(내_방송, 999_999L).andExpect(status().isNotFound());
            최소 = Math.min(최소, (System.nanoTime() - 시작) / 1_000_000.0);
        }
        assertThat(최소).isGreaterThanOrEqualTo(바닥_ms);
    }

    // ── 완성 영상 주소(POK-247) ──────────────────────────────────

    /**
     * 완성 영상은 파일마다(영상·자막) 미리서명 주소를 받고, <b>그 주소로 창고에서 진짜 받아진다</b>: 서명이 틀리면 LocalStack이
     * 403을 준다. 받을 때 이름(Content-Disposition)도 우리가 정한 것이다. 주소 모양만 보면 서명 없는 평문 주소도 통과한다.
     */
    @Test
    void 완성_영상은_파일마다_주소를_주고_그_주소로_진짜_받아진다() throws Exception {
        볼_수_있다("OWNER");
        long clipId = 완성된_영상();
        byte[] 영상 = "가짜 mp4 바이트".getBytes(StandardCharsets.UTF_8);
        byte[] 자막 = "1\n00:00:00,000 --> 00:00:01,000\n안녕\n".getBytes(StandardCharsets.UTF_8);
        LocalStackFixture.putObject("clips-test", "clips/" + clipId + "/t1/o1.mp4", 영상, "video/mp4");
        LocalStackFixture.putObject("clips-test", "clips/" + clipId + "/t1/o1.srt", 자막, "application/x-subrip");

        Instant 전 = Instant.now();
        JsonNode 응답 = MAPPER.readTree(본문(주소(내_방송, clipId).andExpect(status().isOk())));

        assertThat(응답.get("clipId").asLong()).isEqualTo(clipId);
        Instant 만료 = Instant.parse(응답.get("expiresAt").asString());
        assertThat(만료).isBetween(전.plus(Duration.ofMinutes(59)), 전.plus(Duration.ofMinutes(61)));
        JsonNode files = 응답.get("files");
        assertThat(files.size()).isEqualTo(2);
        assertThat(files.get(0).get("outputId").asString()).isEqualTo("o1");
        assertThat(files.get(0).get("kind").asString()).isEqualTo("video");
        assertThat(files.get(0).get("fileName").asString()).isEqualTo("pokeclip-" + clipId + "-o1.mp4");
        assertThat(files.get(1).get("kind").asString()).isEqualTo("srt");
        assertThat(files.get(1).get("fileName").asString()).isEqualTo("pokeclip-" + clipId + "-o1.srt");
        assertThat(응답.toString()).as("창고 좌표는 화면에 줄 필요가 없다").doesNotContain("s3Key");

        HttpResponse<byte[]> 받음 = 받는다(files.get(0).get("url").asString());
        assertThat(받음.statusCode()).isEqualTo(200);
        assertThat(받음.body()).isEqualTo(영상);
        assertThat(받음.headers().firstValue("Content-Disposition")).hasValue(
                "attachment; filename=\"pokeclip-" + clipId + "-o1.mp4\"");
        assertThat(받는다(files.get(1).get("url").asString()).body()).isEqualTo(자막);

        // 서명을 한 글자 바꾸면 창고가 거절한다: 주소가 출입증이라는 것을 잰다.
        String 원래 = files.get(0).get("url").asString();
        String 위조 = 원래.replaceFirst("X-Amz-Signature=.", "X-Amz-Signature=" + (원래.contains("X-Amz-Signature=0") ? "1" : "0"));
        assertThat(받는다(위조).statusCode()).isEqualTo(403);
    }

    /** 아직 만드는 중이면 줄 파일이 없다: 409. 빈 목록 200으로 접으면 화면이 「파일이 없는 영상」으로 읽는다. */
    @Test
    void 완성_전이면_409다() throws Exception {
        볼_수_있다("OWNER");
        long[] clipId = new long[1];
        RenderFixtures.주문을_넣는다(jdbc, 내_방송, 편집본, clipId);

        주소(내_방송, clipId[0]).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("clip_not_rendered"));
    }

    /** 다른 방송 번호로 부르면 없는 영상이고, 자격이 없으면 방송이 없는 것과 같다: 하나 보기 문과 같은 판정이다. */
    @Test
    void 다른_방송_번호와_자격_없음은_404다() throws Exception {
        볼_수_있다("OWNER");
        long clipId = 완성된_영상();
        주소(다른_방송, clipId).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("clip_not_found"));

        볼_수_없다();
        주소(내_방송, clipId).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("broadcast_not_found"));
    }

    // ── outbox · 실패 큐 ──────────────────────────────────────────

    /**
     * 큐에 못 실은 주문(발행 시각 없음)이 outbox 회차에 실린다. 첫 발행이 실패하는 상황을 만들기 어려워 발행 시각을
     * 지우고 만든 시각을 뒤로 돌려 「못 실은 지 오래된 주문」을 흉내 낸다. 방금 만든 것은 회차가 건너뛴다(첫 발행 중일 수 있다).
     */
    @Test
    void 못_실은_주문을_outbox가_다시_싣는다() throws Exception {
        long[] clipId = new long[1];
        UUID jobId = RenderFixtures.주문을_넣는다(jdbc, 내_방송, 편집본, clipId);
        jdbc.update("UPDATE render_jobs SET published_at = NULL, created_at = ? WHERE id = ?",
                OffsetDateTime.ofInstant(Instant.now().minusSeconds(120), ZoneOffset.UTC), jobId);
        UUID 방금 = RenderFixtures.주문을_넣는다(jdbc, 다른_방송,
                RenderFixtures.편집본을_넣는다(jdbc, 다른_방송, RenderFixtures.CUT_IN, RenderFixtures.CUT_OUT), new long[1]);
        jdbc.update("UPDATE render_jobs SET published_at = NULL WHERE id = ?", 방금);

        publisher.resendUnpublished();

        String 주문서 = LocalStackFixture.receiveAndDelete(큐.queueUrl());
        assertThat(주문서).isNotNull();
        assertThat(MAPPER.readTree(주문서).get("jobId").asString()).isEqualTo(jobId.toString());
        assertThat(LocalStackFixture.receiveAndDelete(큐.queueUrl())).as("방금 만든 주문까지 실렸다").isNull();
        assertThat(jdbc.queryForObject("SELECT published_at IS NOT NULL FROM render_jobs WHERE id = ?", Boolean.class, jobId))
                .isTrue();
    }

    /**
     * 실패 큐에 떨어진 주문은 SWEPT로 닫히고 쪽지는 사라진다. 이미 끝난 주문의 쪽지는 <b>상태를 안 바꾸고</b> 지운다 —
     * 성공이 「실패, 재요청」으로 덮이면 안 된다. 못 읽는 쪽지도 지운다.
     */
    @Test
    void 실패_큐의_주문은_SWEPT로_닫히고_끝난_주문은_안_건드린다() throws Exception {
        long[] 열린 = new long[1];
        UUID 열린잡 = RenderFixtures.주문을_넣는다(jdbc, 내_방송, 편집본, 열린);
        long[] 끝난 = new long[1];
        UUID 끝난잡 = RenderFixtures.주문을_넣는다(jdbc, 다른_방송,
                RenderFixtures.편집본을_넣는다(jdbc, 다른_방송, RenderFixtures.CUT_IN, RenderFixtures.CUT_OUT), 끝난);
        jdbc.update("UPDATE render_jobs SET status = 'succeeded' WHERE id = ?", 끝난잡);
        jdbc.update("UPDATE clips SET status = 'rendered' WHERE id = ?", 끝난[0]);
        LocalStackFixture.sendPlain(큐.dlqUrl(), "{\"jobId\":\"" + 열린잡 + "\"}");
        LocalStackFixture.sendPlain(큐.dlqUrl(), "{\"jobId\":\"" + 끝난잡 + "\"}");
        LocalStackFixture.sendPlain(큐.dlqUrl(), "{이건 JSON이 아니다");

        int swept = reconciler.reconcileOnce();

        assertThat(swept).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status || ':' || error_code FROM clips WHERE id = ?", String.class, 열린[0]))
                .isEqualTo("failed:SWEPT");
        assertThat(jdbc.queryForObject("SELECT status FROM render_jobs WHERE id = ?", String.class, 열린잡)).isEqualTo("failed");
        assertThat(jdbc.queryForObject("SELECT status FROM clips WHERE id = ?", String.class, 끝난[0])).isEqualTo("rendered");
        assertThat(LocalStackFixture.approximateMessageCount(큐.dlqUrl())).isZero();
    }

    // ── 도우미 ──────────────────────────────────────────────────

    private void 볼_수_있다(String relation) {
        AUTH.respondWith(RESOLVE, 200, "{\"relation\":\"%s\"}".formatted(relation));
    }

    private void 볼_수_없다() {
        AUTH.respondWith(RESOLVE, 200, "{\"relation\":\"NONE\"}");
    }

    private ResultActions 주문(String streamId, long recipeId) throws Exception {
        return mvc.perform(post("/api/clip/broadcasts/" + streamId + "/recipes/" + recipeId + "/renders")
                .header("Authorization", "Bearer " + TestTokens.access(요청자)));
    }

    private ResultActions 하나(String streamId, long clipId) throws Exception {
        return mvc.perform(get("/api/clip/broadcasts/" + streamId + "/clips/" + clipId)
                .header("Authorization", "Bearer " + TestTokens.access(요청자)));
    }

    private ResultActions 주소(String streamId, long clipId) throws Exception {
        return mvc.perform(post("/api/clip/broadcasts/" + streamId + "/clips/" + clipId + "/file-access")
                .header("Authorization", "Bearer " + TestTokens.access(요청자)));
    }

    /** 일꾼이 완성을 보고한 모양 그대로: 산출물 두 개(영상·자막). */
    private long 완성된_영상() {
        long[] clipId = new long[1];
        RenderFixtures.주문을_넣는다(jdbc, 내_방송, 편집본, clipId);
        String outputs = """
                [{"outputId":"o1","kind":"video","s3Key":"clips/%1$d/t1/o1.mp4"},
                 {"outputId":"o1","kind":"srt","s3Key":"clips/%1$d/t1/o1.srt"}]""".formatted(clipId[0]);
        jdbc.update("UPDATE clips SET status = 'rendered', outputs = ?::jsonb WHERE id = ?", outputs, clipId[0]);
        return clipId[0];
    }

    private static HttpResponse<byte[]> 받는다(String url) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        }
    }

    private static String 본문(ResultActions actions) throws Exception {
        return actions.andReturn().getResponse().getContentAsString();
    }
}
