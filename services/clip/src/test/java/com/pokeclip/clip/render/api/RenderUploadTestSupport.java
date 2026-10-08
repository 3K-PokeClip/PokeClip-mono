package com.pokeclip.clip.render.api;

import com.pokeclip.clip.render.RenderFixtures;
import com.pokeclip.clip.support.IntegrationTestSupport;
import com.pokeclip.clip.support.LocalStackFixture;
import com.pokeclip.clip.support.TestTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 「영상 만들기 = 렌더 + 유튜브 바로 올리기」(POK-291) 시험들의 공통 바탕. 렌더 줄·업로드 줄을 <b>둘 다</b> 켠 컨텍스트 하나를
 * 여러 시험 클래스가 나눠 쓴다(설정 메서드가 이 클래스 하나라 컨텍스트 캐시가 안 갈린다).
 *
 * <p>진짜 톰캣(RANDOM_PORT)이다: 썸네일 그림의 크기 상한은 서블릿 층이 자르므로 MockMvc로는 못 잰다
 * ({@code RenderUploadSizeLimitTest}). 나머지는 같은 컨텍스트의 MockMvc로 잰다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
abstract class RenderUploadTestSupport extends IntegrationTestSupport {

    static final String RESOLVE = "/internal/editor-delegations/resolve";
    static final String LINK_STATUS = "/internal/youtube-link/status";
    static final String INTERNAL = "test-only-internal-token-32bytes-long!!";
    static final String 요청자 = "4180";
    static final String 방송 = "s-make";
    static final String 창고 = "clips-make-test";
    static final ObjectMapper MAPPER = new ObjectMapper();

    static final LocalStackFixture.QueuePair 렌더줄 = LocalStackFixture.createStandardQueueWithDlq("jobs-render-make", 3);
    static final LocalStackFixture.QueuePair 업로드줄 = LocalStackFixture.createStandardQueueWithDlq("jobs-upload-make", 3);

    @DynamicPropertySource
    static void renderAndUploadProperties(DynamicPropertyRegistry registry) {
        registry.add("pokeclip.render.enabled", () -> "true");
        registry.add("pokeclip.render.queue-url", 렌더줄::queueUrl);
        registry.add("pokeclip.render.dlq-url", 렌더줄::dlqUrl);
        registry.add("pokeclip.render.region", LocalStackFixture::region);
        registry.add("pokeclip.render.endpoint", LocalStackFixture::endpoint);
        registry.add("pokeclip.render.output-bucket", () -> 창고);
        registry.add("pokeclip.render.segment-bucket", () -> "segments-make-test");
        registry.add("pokeclip.upload.enabled", () -> "true");
        registry.add("pokeclip.upload.queue-url", 업로드줄::queueUrl);
        registry.add("pokeclip.upload.dlq-url", 업로드줄::dlqUrl);
        registry.add("pokeclip.upload.region", LocalStackFixture::region);
        registry.add("pokeclip.upload.endpoint", LocalStackFixture::endpoint);
    }

    final MockMvc mvc;
    final JdbcTemplate jdbc;

    long 편집본;

    RenderUploadTestSupport(MockMvc mvc, JdbcTemplate jdbc) {
        this.mvc = mvc;
        this.jdbc = jdbc;
    }

    @BeforeEach
    void 바탕을_깐다() {
        방송과_카드를_비운다(jdbc);
        LocalStackFixture.ensureBucket(창고);
        RenderFixtures.방송을_넣는다(jdbc, 방송);
        편집본 = RenderFixtures.편집본을_넣는다(jdbc, 방송, RenderFixtures.CUT_IN, RenderFixtures.CUT_OUT);
        RenderFixtures.조각을_넣는다(jdbc, 방송, 0);
        비운다(렌더줄);
        비운다(업로드줄);
        AUTH.respondWith(RESOLVE, 200, "{\"relation\":\"OWNER\"}");
        연결됐다();
    }

    /** 다른 시험 클래스가 recipes·clips를 직접 지운다. 내 자식 줄을 내가 치운다. */
    @AfterEach
    void 흔적을_지운다() {
        jdbc.update("DELETE FROM clip_uploads");
        jdbc.update("DELETE FROM render_job_events");
        jdbc.update("DELETE FROM render_jobs");
        jdbc.update("DELETE FROM clips");
        jdbc.update("DELETE FROM upload_requests");
        jdbc.update("DELETE FROM recipes");
        jdbc.update("DELETE FROM stream_segments");
    }

    static void 비운다(LocalStackFixture.QueuePair pair) {
        while (LocalStackFixture.receiveAndDelete(pair.queueUrl()) != null) {
        }
        while (LocalStackFixture.receiveAndDelete(pair.dlqUrl()) != null) {
        }
    }

    void 연결됐다() {
        AUTH.respondWith(LINK_STATUS, 200, "{\"linked\":true,\"reason\":null}");
    }

    static String 업로드(String upload) {
        return "{\"upload\":" + upload + "}";
    }

    static String 기본_업로드() {
        return 업로드("{\"title\":\"펜타킬\",\"tags\":[\"롤\"],\"privacyStatus\":\"unlisted\",\"madeForKids\":true,"
                + "\"thumbnail\":{\"source\":\"scene\",\"offsetMs\":12000}}");
    }

    ResultActions 만들기(long recipeId, String body) throws Exception {
        var request = post("/api/clip/broadcasts/" + 방송 + "/recipes/" + recipeId + "/renders")
                .header("Authorization", "Bearer " + TestTokens.access(요청자));
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(request);
    }

    /** 일꾼이 잡고(STARTED) 성공을 보고한다(SUCCEEDED). @return SUCCEEDED 본문(재전송 시험이 같은 것을 다시 보낸다) */
    String 완성시킨다(long clipId, String resultTemplate) throws Exception {
        UUID jobId = 잡(clipId);
        String token = MAPPER.readTree(본문(보고(jobId, 이벤트("STARTED", null, null)).andExpect(status().isOk())))
                .get("executionToken").asString();
        String result = "\"result\":" + resultTemplate.replace("{prefix}", "clips/" + clipId + "/" + token);
        String succeeded = 이벤트("SUCCEEDED", token, result);
        보고(jobId, succeeded).andExpect(status().isOk());
        return succeeded;
    }

    static String 영상_하나() {
        return "[{\"outputId\":\"o1\",\"kind\":\"video\",\"s3Key\":\"{prefix}/o1.mp4\"}]";
    }

    ResultActions 보고(UUID jobId, String body) throws Exception {
        return mvc.perform(post("/internal/jobs/" + jobId + "/events")
                .header("X-Internal-Token", INTERNAL).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    UUID 잡(long clipId) {
        return UUID.fromString(jdbc.queryForObject("SELECT id::text FROM render_jobs WHERE clip_id = ?", String.class, clipId));
    }

    static String 이벤트(String type, String token, String extra) {
        return "{\"eventId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"" + type + "\","
                + "\"occurredAt\":\"2026-10-08T00:00:00Z\""
                + (token == null ? "" : ",\"executionToken\":\"" + token + "\"")
                + (extra == null ? "" : "," + extra) + "}";
    }

    int 업로드_줄_수() {
        return jdbc.queryForObject("SELECT count(*) FROM clip_uploads", Integer.class);
    }

    static JsonNode json(ResultActions actions) throws Exception {
        return MAPPER.readTree(본문(actions));
    }

    static String 본문(ResultActions actions) throws Exception {
        return actions.andReturn().getResponse().getContentAsString();
    }
}
