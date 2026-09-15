package com.pokeclip.clip.render.api;

import com.pokeclip.clip.render.RenderFixtures;
import com.pokeclip.clip.support.IntegrationTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 계약1 4절 보고 문. 상태머신 표를 <b>한 줄씩</b> 잰다 — STARTED가 토큰을 주고, 그 토큰만 통하고, 두 번째 STARTED가
 * 옛 토큰을 죽이고, 성공·실패가 완성 영상에 옮겨지고, 끝난 뒤 보고는 409이고, 같은 보고는 같은 답을 받는다.
 * 큐가 필요 없어 기본 컨텍스트(주문줄 꺼짐)에서 돈다.
 */
@AutoConfigureMockMvc
class JobEventControllerTest extends IntegrationTestSupport {

    private static final String INTERNAL = "test-only-internal-token-32bytes-long!!";
    private static final String 방송 = "s-job";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final MockMvc mvc;
    private final JdbcTemplate jdbc;

    private UUID 잡;
    private long 영상;

    JobEventControllerTest(MockMvc mvc, JdbcTemplate jdbc) {
        this.mvc = mvc;
        this.jdbc = jdbc;
    }

    @BeforeEach
    void 씨앗() {
        방송과_카드를_비운다(jdbc);
        RenderFixtures.방송을_넣는다(jdbc, 방송);
        long 편집본 = RenderFixtures.편집본을_넣는다(jdbc, 방송, RenderFixtures.CUT_IN, RenderFixtures.CUT_OUT);
        long[] clipId = new long[1];
        잡 = RenderFixtures.주문을_넣는다(jdbc, 방송, 편집본, clipId);
        영상 = clipId[0];
    }

    @AfterEach
    void 내_흔적을_지운다() {
        jdbc.update("DELETE FROM render_job_events");
        jdbc.update("DELETE FROM render_jobs");
        jdbc.update("DELETE FROM clips");
        jdbc.update("DELETE FROM recipes");
    }

    @Test
    void STARTED는_토큰을_주고_영상은_만드는_중이_된다() throws Exception {
        String 응답 = 본문(보고(잡, 이벤트("STARTED", null, null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.proceed").value(true))
                .andExpect(jsonPath("$.attemptOrdinal").value(1))
                .andExpect(jsonPath("$.isFinalAttempt").value(false)));
        String 토큰 = MAPPER.readTree(응답).get("executionToken").asString();
        assertThat(UUID.fromString(토큰)).isNotNull();
        assertThat(clip상태()).isEqualTo("rendering");
        assertThat(jdbc.queryForObject("SELECT execution_token::text FROM render_jobs WHERE id = ?", String.class, 잡))
                .isEqualTo(토큰);
    }

    @Test
    void PROGRESS는_같은_토큰만_통하고_뒤로_가는_값은_무시한다() throws Exception {
        String 토큰 = 시작();

        보고(잡, 이벤트("PROGRESS", 토큰, "\"progress\":{\"percent\":40,\"stage\":\"encode\"}")).andExpect(status().isOk());
        보고(잡, 이벤트("PROGRESS", 토큰, "\"progress\":{\"percent\":10,\"stage\":\"mux\"}")).andExpect(status().isOk());
        보고(잡, 이벤트("PROGRESS", UUID.randomUUID().toString(), "\"progress\":{\"percent\":90}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("SUPERSEDED"));

        assertThat(jdbc.queryForObject("SELECT progress_percent || ':' || progress_stage FROM render_jobs WHERE id = ?",
                String.class, 잡)).isEqualTo("40:mux");
    }

    /** 일꾼이 죽고 큐가 다시 줬다 — 두 번째 STARTED가 새 토큰·순번 2를 주고, 옛 토큰의 보고는 409다. 진행률은 0으로 돌아간다. */
    @Test
    void 두_번째_STARTED는_옛_실행을_무효로_한다() throws Exception {
        String 옛토큰 = 시작();
        보고(잡, 이벤트("PROGRESS", 옛토큰, "\"progress\":{\"percent\":70}")).andExpect(status().isOk());

        String 응답 = 본문(보고(잡, 이벤트("STARTED", null, null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.attemptOrdinal").value(2)));
        String 새토큰 = MAPPER.readTree(응답).get("executionToken").asString();

        assertThat(새토큰).isNotEqualTo(옛토큰);
        보고(잡, 이벤트("PROGRESS", 옛토큰, "\"progress\":{\"percent\":80}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("SUPERSEDED"));
        assertThat(jdbc.queryForObject("SELECT progress_percent FROM render_jobs WHERE id = ?", Integer.class, 잡)).isZero();
    }

    @Test
    void 세_번째_STARTED가_마지막_시도다() throws Exception {
        시작();
        시작();
        보고(잡, 이벤트("STARTED", null, null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.attemptOrdinal").value(3))
                .andExpect(jsonPath("$.isFinalAttempt").value(true));
    }

    @Test
    void SUCCEEDED는_산출물을_영상에_옮기고_그_뒤_보고는_409다() throws Exception {
        String 토큰 = 시작();
        String result = "\"result\":[{\"outputId\":\"o1\",\"kind\":\"video\",\"s3Key\":\"clips/" + 영상 + "/" + 토큰 + "/o1.mp4\"},"
                + "{\"outputId\":\"o1\",\"kind\":\"srt\",\"s3Key\":\"clips/" + 영상 + "/" + 토큰 + "/o1.srt\"}]";

        보고(잡, 이벤트("SUCCEEDED", 토큰, result)).andExpect(status().isOk());

        assertThat(clip상태()).isEqualTo("rendered");
        assertThat(jdbc.queryForObject("SELECT outputs->0->>'s3Key' FROM clips WHERE id = ?", String.class, 영상))
                .isEqualTo("clips/" + 영상 + "/" + 토큰 + "/o1.mp4");
        보고(잡, 이벤트("PROGRESS", 토큰, "\"progress\":{\"percent\":50}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("TERMINAL"));
        보고(잡, 이벤트("STARTED", null, null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.proceed").value(false));
    }

    /** 산출물이 내 자리(clips/{id}/{토큰}/) 밖이거나 output이 빠지면 400이고 <b>상태는 그대로</b>다(검증이 전이보다 먼저). */
    @Test
    void 산출물이_틀리면_400이고_상태는_안_바뀐다() throws Exception {
        String 토큰 = 시작();

        보고(잡, 이벤트("SUCCEEDED", 토큰, "\"result\":[{\"outputId\":\"o1\",\"kind\":\"video\",\"s3Key\":\"clips/999/x/o1.mp4\"}]"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.reason").value("INVALID_RESULT"));
        보고(잡, 이벤트("SUCCEEDED", 토큰, "\"result\":[{\"outputId\":\"o1\",\"kind\":\"srt\",\"s3Key\":\"clips/" + 영상 + "/" + 토큰 + "/o1.srt\"}]"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.reason").value("INVALID_RESULT"));

        assertThat(clip상태()).isEqualTo("rendering");
        assertThat(jdbc.queryForObject("SELECT status FROM render_jobs WHERE id = ?", String.class, 잡)).isEqualTo("started");
    }

    @Test
    void TERMINAL_FAILED는_사유를_영상에_남긴다() throws Exception {
        String 토큰 = 시작();

        보고(잡, 이벤트("TERMINAL_FAILED", 토큰, "\"error\":{\"code\":\"SOURCE_EXPIRED\",\"message\":\"조각이 없다\",\"retryable\":true}"))
                .andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT status || ':' || error_code || ':' || error_message FROM clips WHERE id = ?",
                String.class, 영상)).isEqualTo("failed:SOURCE_EXPIRED:조각이 없다");
    }

    /** 시작 전 검사(preflight) 실패는 토큰 없이 온다 — 주문됨 상태에서만 받는다. */
    @Test
    void 토큰_없는_TERMINAL_FAILED는_시작_전에만_받는다() throws Exception {
        보고(잡, 이벤트("TERMINAL_FAILED", null, "\"error\":{\"code\":\"VALIDATION\",\"message\":\"레시피\"}"))
                .andExpect(status().isOk());
        assertThat(clip상태()).isEqualTo("failed");
    }

    @Test
    void 시작_전_PROGRESS는_400이다() throws Exception {
        보고(잡, 이벤트("PROGRESS", UUID.randomUUID().toString(), "\"progress\":{\"percent\":1}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.reason").value("INVALID_EVENT"));
    }

    /** 같은 eventId가 다시 오면 저장한 답 그대로 — 상태는 두 번 안 바뀐다(순번이 안 오른다). */
    @Test
    void 같은_보고가_두_번_오면_같은_답이고_한_번만_반영된다() throws Exception {
        String 본문 = 이벤트("STARTED", null, null);
        String 첫답 = 본문(보고(잡, 본문).andExpect(status().isOk()));
        String 둘째답 = 본문(보고(잡, 본문).andExpect(status().isOk()));

        // jsonb가 칸 순서를 바꿔 돌려주므로 글자가 아니라 뜻으로 비교한다 — 토큰·순번이 같으면 같은 답이다.
        assertThat(MAPPER.readTree(둘째답)).isEqualTo(MAPPER.readTree(첫답));
        assertThat(jdbc.queryForObject("SELECT attempt_ordinal FROM render_jobs WHERE id = ?", Integer.class, 잡)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM render_job_events", Integer.class)).isEqualTo(1);
    }

    /** STARTED replay는 그 사이 토큰이 갈렸으면 proceed:false로 바뀐다 — 죽었던 일꾼이 옛 답으로 다시 일하면 안 된다. */
    @Test
    void STARTED_재전송은_토큰이_갈렸으면_proceed_false다() throws Exception {
        String 첫보고 = 이벤트("STARTED", null, null);
        보고(잡, 첫보고).andExpect(status().isOk()).andExpect(jsonPath("$.proceed").value(true));
        보고(잡, 이벤트("STARTED", null, null)).andExpect(status().isOk());

        보고(잡, 첫보고).andExpect(status().isOk()).andExpect(jsonPath("$.proceed").value(false));
    }

    @Test
    void 모르는_잡은_404_토큰_없으면_401_본문이_틀리면_400() throws Exception {
        보고(UUID.randomUUID(), 이벤트("STARTED", null, null)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("job_not_found"));
        mvc.perform(post("/internal/jobs/" + 잡 + "/events").contentType(MediaType.APPLICATION_JSON)
                .content(이벤트("STARTED", null, null))).andExpect(status().isUnauthorized());
        보고(잡, "{\"eventId\":\"x\",\"eventType\":\"STARTED\",\"occurredAt\":\"2026-09-15T00:00:00Z\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("eventId"));
        보고(잡, "{\"eventId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"DANCE\",\"occurredAt\":\"2026-09-15T00:00:00Z\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("eventType"));
        mvc.perform(post("/internal/jobs/not-a-uuid/events").header("X-Internal-Token", INTERNAL)
                .contentType(MediaType.APPLICATION_JSON).content(이벤트("STARTED", null, null)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("jobId"));
    }

    // ── 도우미 ──────────────────────────────────────────────────

    private String 시작() throws Exception {
        return MAPPER.readTree(본문(보고(잡, 이벤트("STARTED", null, null)).andExpect(status().isOk())))
                .get("executionToken").asString();
    }

    private String clip상태() {
        return jdbc.queryForObject("SELECT status FROM clips WHERE id = ?", String.class, 영상);
    }

    private static String 이벤트(String type, String token, String extra) {
        return "{\"eventId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"" + type + "\","
                + "\"occurredAt\":\"2026-09-15T00:00:00Z\""
                + (token == null ? "" : ",\"executionToken\":\"" + token + "\"")
                + (extra == null ? "" : "," + extra) + "}";
    }

    private ResultActions 보고(UUID jobId, String body) throws Exception {
        return mvc.perform(post("/internal/jobs/" + jobId + "/events")
                .header("X-Internal-Token", INTERNAL).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static String 본문(ResultActions actions) throws Exception {
        return actions.andReturn().getResponse().getContentAsString();
    }
}
