package com.pokeclip.clip.render.api;

import com.pokeclip.clip.render.RenderFixtures;
import com.pokeclip.clip.support.LocalStackFixture;
import com.pokeclip.clip.support.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 렌더 성공 → 자동 업로드(POK-291). 재는 것의 중심은 <b>「판 하나에 업로드 줄 하나」</b>다: 성공 보고가 두 번 와도, 같은 판을 다시
 * 렌더해도 줄이 안 늘고, 의도가 없으면 하나도 안 생긴다. 줄은 성공과 <b>같은 트랜잭션</b>에서 생기고 커밋 뒤 <b>바로</b> 실린다.
 */
class AutoUploadOnRenderTest extends RenderUploadTestSupport {

    AutoUploadOnRenderTest(MockMvc mvc, JdbcTemplate jdbc) {
        super(mvc, jdbc);
    }

    /**
     * 성공 보고 하나가 업로드 줄을 정확히 하나 만들고, 주문서가 <b>outbox를 안 기다리고</b> 줄에 실린다(커밋 뒤 훅). 같은 성공 보고가
     * 다시 오면 보고 장부가 막아 줄도 주문서도 안 는다.
     */
    @Test
    void 성공_보고가_업로드_줄을_정확히_하나_만들고_재전송에_안_는다() throws Exception {
        long clipId = json(만들기(편집본, 기본_업로드()).andExpect(status().isCreated())).get("id").asLong();
        assertThat(업로드_줄_수()).as("렌더 주문 때는 업로드 줄이 없다").isZero();

        String succeeded = 완성시킨다(clipId, 영상_하나());

        assertThat(업로드_줄_수()).isOne();
        assertThat(jdbc.queryForObject("SELECT published_at IS NOT NULL FROM clip_uploads", Boolean.class))
                .as("커밋 뒤 훅이 바로 실어야 한다(outbox는 30초 뒤)").isTrue();
        String 쪽지 = LocalStackFixture.receiveAndDelete(업로드줄.queueUrl());
        assertThat(쪽지).isNotNull();
        JsonNode 봉투 = MAPPER.readTree(쪽지);
        assertThat(봉투.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(봉투.get("clipId").asString()).isEqualTo(String.valueOf(clipId));
        assertThat(봉투.get("channelOwnerUserId").asString()).isEqualTo("7");
        assertThat(봉투.at("/source/outputId").asString()).isEqualTo("o1");
        assertThat(봉투.at("/video/title").asString()).isEqualTo("펜타킬");
        assertThat(봉투.at("/video/privacyStatus").asString()).as("비공개 고정이 아니라 고른 값").isEqualTo("unlisted");
        assertThat(봉투.at("/video/madeForKids").asBoolean()).isTrue();
        assertThat(봉투.at("/video/tags/0").asString()).isEqualTo("롤");
        assertThat(봉투.at("/thumbnail/source").asString()).isEqualTo("scene");
        assertThat(봉투.at("/thumbnail/offsetMs").asLong()).isEqualTo(12_000L);
        assertThat(jdbc.queryForObject("SELECT requested_by || ':' || thumbnail_status FROM clip_uploads", String.class))
                .isEqualTo(요청자 + ":pending");

        보고(잡(clipId), succeeded).andExpect(status().isOk());

        assertThat(업로드_줄_수()).as("같은 성공 보고의 재전송").isOne();
        assertThat(LocalStackFixture.receiveAndDelete(업로드줄.queueUrl())).as("두 번째 주문서가 실렸다").isNull();
    }

    /**
     * 🔴 업로드 줄 쓰기가 실패하면 <b>완성까지 되감긴다</b>: 성공 보고가 500이고 보고 장부에 안 남아, 일꾼이 같은 보고를 다시 보내면
     * 처음부터 다시 되고 그때 업로드 줄이 생긴다. 줄을 커밋 뒤에 따로 만들면 완성과 장부(200)는 남고 줄만 사라져, 재전송이 저장된
     * 200을 받아 <b>영영 안 올라간다</b>. 실패는 시험에서만 거는 방아쇠(그 제목의 줄을 거절)로 만든다.
     */
    @Test
    void 업로드_줄_쓰기가_실패하면_완성까지_되감기고_재전송이_다시_만든다() throws Exception {
        long clipId = json(만들기(편집본, 업로드("{\"title\":\"터지는 제목\"}")).andExpect(status().isCreated())).get("id").asLong();
        UUID jobId = 잡(clipId);
        String token = MAPPER.readTree(본문(보고(jobId, 이벤트("STARTED", null, null)).andExpect(status().isOk())))
                .get("executionToken").asString();
        String succeeded = 이벤트("SUCCEEDED", token,
                "\"result\":" + 영상_하나().replace("{prefix}", "clips/" + clipId + "/" + token));
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION pok291_boom() RETURNS trigger AS $$
                BEGIN IF NEW.title = '터지는 제목' THEN RAISE EXCEPTION 'pok291 boom'; END IF; RETURN NEW; END $$ LANGUAGE plpgsql""");
        jdbc.execute("CREATE TRIGGER pok291_boom BEFORE INSERT ON clip_uploads FOR EACH ROW EXECUTE FUNCTION pok291_boom()");
        try {
            // MockMvc는 처리기 없는 예외를 그대로 던진다(진짜 톰캣이면 500이다).
            assertThatThrownBy(() -> 보고(jobId, succeeded)).hasStackTraceContaining("pok291 boom");
            assertThat(jdbc.queryForObject("SELECT status FROM clips WHERE id = ?", String.class, clipId))
                    .as("완성이 업로드 줄과 함께 되감겨야 한다").isEqualTo("rendering");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM render_job_events WHERE event_type = 'SUCCEEDED'",
                    Integer.class)).as("장부에 남으면 재전송이 저장된 답만 받는다").isZero();
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS pok291_boom ON clip_uploads");
            jdbc.execute("DROP FUNCTION IF EXISTS pok291_boom()");
        }

        보고(jobId, succeeded).andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT status FROM clips WHERE id = ?", String.class, clipId)).isEqualTo("rendered");
        assertThat(업로드_줄_수()).isOne();
    }

    /** 본문 없이 만든 영상(의도 없음)은 성공해도 업로드 줄이 안 생긴다: 요청하지 않은 영상을 올리지 않는다(ADR-084). */
    @Test
    void 의도가_없으면_성공해도_업로드_줄이_없다() throws Exception {
        long clipId = json(만들기(편집본, null).andExpect(status().isCreated())).get("id").asLong();

        완성시킨다(clipId, 영상_하나());

        assertThat(업로드_줄_수()).isZero();
        assertThat(LocalStackFixture.receiveAndDelete(업로드줄.queueUrl())).isNull();
    }

    /**
     * 출력이 둘이면 세로(VERT_9_16)를 올린다(ADR-080 규칙). 세로를 사전순 뒤(o2)에 둬서 「첫째를 고른다」와 갈린다. 비율은 이 영상을 만든
     * 주문서의 편집본에서 읽는다.
     */
    @Test
    void 출력이_둘이면_세로를_올린다() throws Exception {
        long 두벌 = jdbc.queryForObject("""
                INSERT INTO recipes (stream_id, creator_id, schema_version, recipe_version, cut_in_at_ms, cut_out_at_ms,
                                     outputs, audio, updated_at)
                VALUES (?, '4180', 1, 1, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), now()) RETURNING id""", Long.class,
                방송, RenderFixtures.CUT_IN, RenderFixtures.CUT_OUT,
                "[{\"outputId\":\"o1\",\"aspect\":\"SQUARE_1_1\",\"crop\":{\"x\":0.2,\"y\":0.0,\"w\":0.5625,\"h\":1.0}},"
                        + "{\"outputId\":\"o2\",\"aspect\":\"VERT_9_16\",\"crop\":{\"x\":0.21,\"y\":0.0,\"w\":0.316,\"h\":1.0}}]",
                "{\"tracks\":[{\"trackId\":1,\"gain\":1.0}]}");
        long clipId = json(만들기(두벌, 기본_업로드()).andExpect(status().isCreated())).get("id").asLong();

        완성시킨다(clipId, "[{\"outputId\":\"o1\",\"kind\":\"video\",\"s3Key\":\"{prefix}/o1.mp4\"},"
                + "{\"outputId\":\"o2\",\"kind\":\"video\",\"s3Key\":\"{prefix}/o2.mp4\"}]");

        assertThat(jdbc.queryForObject("SELECT output_id FROM clip_uploads", String.class)).isEqualTo("o2");
    }

    /**
     * 렌더가 실패해도 의도는 남는다. 같은 판을 다시 만들어 성공하면 저장된 정보로 이어서 올린다(창 없이). 다시 만들기는 본문이 없어도 된다.
     */
    @Test
    void 렌더_실패_뒤_다시_만들면_저장된_정보로_이어서_올린다() throws Exception {
        long 첫째 = json(만들기(편집본, 기본_업로드()).andExpect(status().isCreated())).get("id").asLong();
        보고(잡(첫째), 이벤트("TERMINAL_FAILED", null, "\"error\":{\"code\":\"VALIDATION\",\"message\":\"x\"}"))
                .andExpect(status().isOk());

        long 둘째 = json(만들기(편집본, null).andExpect(status().isCreated())).get("id").asLong();
        완성시킨다(둘째, 영상_하나());

        assertThat(jdbc.queryForObject("SELECT clip_id || ':' || title FROM clip_uploads", String.class))
                .isEqualTo(둘째 + ":펜타킬");
    }

    /**
     * 같은 판을 본문 없이 다시 렌더해 새 영상이 생겨도, 그 판이 이미 올라가는 중이면 새 영상은 안 올린다(판 하나는 채널에 하나).
     * 부분 UNIQUE는 (영상, 벌) 단위라 이 경우를 못 막는다: 판 단위 검사를 지우면 여기서 둘이 된다.
     */
    @Test
    void 같은_판을_다시_렌더해도_업로드는_하나다() throws Exception {
        long 첫째 = json(만들기(편집본, 기본_업로드()).andExpect(status().isCreated())).get("id").asLong();
        완성시킨다(첫째, 영상_하나());
        assertThat(업로드_줄_수()).isOne();

        long 둘째 = json(만들기(편집본, null).andExpect(status().isCreated())).get("id").asLong();
        완성시킨다(둘째, 영상_하나());

        assertThat(업로드_줄_수()).isOne();
        assertThat(jdbc.queryForObject("SELECT clip_id FROM clip_uploads", Long.class)).isEqualTo(첫째);
    }

    /** 영상 조회가 그 판의 의도 요약을 싣는다: 화면이 업로드 줄이 없을 때 고른 제목을 보이고, 완성인데 줄이 없으면 「업로드는 시작되지 않았어요」로 본다. */
    @Test
    void 영상_조회에_의도_요약이_실린다() throws Exception {
        long clipId = json(만들기(편집본, 기본_업로드()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.uploadRequest.title").value("펜타킬"))
                .andExpect(jsonPath("$.uploadRequest.privacyStatus").value("unlisted"))
                .andExpect(jsonPath("$.uploadRequest.thumbnailSource").value("scene"))).get("id").asLong();

        완성시킨다(clipId, 영상_하나());

        String 조회 = 본문(mvc.perform(get("/api/clip/broadcasts/" + 방송 + "/clips/" + clipId)
                        .header("Authorization", "Bearer " + TestTokens.access(요청자)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uploadRequest.title").value("펜타킬"))
                .andExpect(jsonPath("$.upload.privacyStatus").value("unlisted"))
                .andExpect(jsonPath("$.upload.madeForKids").value(true))
                .andExpect(jsonPath("$.upload.tags[0]").value("롤"))
                .andExpect(jsonPath("$.upload.thumbnail.source").value("scene"))
                .andExpect(jsonPath("$.upload.thumbnail.status").value("pending")));
        // 키가 있는지를 글자로 잰다: jsonPath의 doesNotExist는 값이 null인 키도 통과시킨다.
        assertThat(조회).as("설명은 싣지 않는다(목록 폴링이 무거워진다)").doesNotContain("\"description\"")
                .doesNotContain("sessionUri");
    }
}
