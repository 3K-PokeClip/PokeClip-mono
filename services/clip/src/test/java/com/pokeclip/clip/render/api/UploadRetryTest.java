package com.pokeclip.clip.render.api;

import com.pokeclip.clip.support.LocalStackFixture;
import com.pokeclip.clip.support.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 업로드 다시 시도(POK-291): 실패한 업로드를 <b>저장된 정보 그대로</b>(창 없이) 새 줄로 올린다. 실패만 대상이다: 확인 중(결과 불명)을
 * 다시 올리면 채널에 영상이 둘 뜰 수 있다.
 */
class UploadRetryTest extends RenderUploadTestSupport {

    UploadRetryTest(MockMvc mvc, JdbcTemplate jdbc) {
        super(mvc, jdbc);
    }

    @Test
    void 실패한_업로드를_저장된_정보로_새_줄에_올린다() throws Exception {
        long clipId = 올린_영상();
        long 첫째 = 최신_업로드();
        jdbc.update("UPDATE clip_uploads SET status = 'failed', error_code = 'QUOTA_EXCEEDED', thumbnail_status = 'pending' "
                + "WHERE id = ?", 첫째);
        비운다(업로드줄);

        JsonNode 응답 = json(다시(clipId).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("queued"))
                .andExpect(jsonPath("$.title").value("펜타킬"))
                .andExpect(jsonPath("$.privacyStatus").value("unlisted"))
                .andExpect(jsonPath("$.madeForKids").value(true))
                .andExpect(jsonPath("$.tags[0]").value("롤"))
                .andExpect(jsonPath("$.thumbnail.source").value("scene"))
                .andExpect(jsonPath("$.thumbnail.status").value("pending")));

        assertThat(응답.get("id").asLong()).isNotEqualTo(첫째);
        assertThat(업로드_줄_수()).isEqualTo(2);
        JsonNode 봉투 = MAPPER.readTree(LocalStackFixture.receiveAndDelete(업로드줄.queueUrl()));
        assertThat(봉투.get("uploadId").asString()).isEqualTo(String.valueOf(응답.get("id").asLong()));
        assertThat(봉투.at("/source/outputId").asString()).isEqualTo("o1");
        assertThat(봉투.at("/source/s3Key").asString()).startsWith("clips/" + clipId + "/").endsWith("/o1.mp4");
        assertThat(봉투.at("/thumbnail/offsetMs").asLong()).isEqualTo(12_000L);
        assertThat(봉투.at("/video/madeForKids").asBoolean()).isTrue();
    }

    /** 최신이 살아 있으면(올리는 중·확인 중·올림) 새로 안 만들고 그것을 200으로. 연타한 두 번째 누름도 여기다. */
    @Test
    void 최신이_살아_있으면_그것을_돌려준다() throws Exception {
        long clipId = 올린_영상();
        long 첫째 = 최신_업로드();

        for (String 상태 : new String[]{"queued", "checking"}) {
            jdbc.update("UPDATE clip_uploads SET status = ? WHERE id = ?", 상태, 첫째);
            다시(clipId).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(첫째))
                    .andExpect(jsonPath("$.status").value(상태));
        }
        assertThat(업로드_줄_수()).isOne();
    }

    @Test
    void 올린_적도_의도도_없으면_409_nothing_to_retry다() throws Exception {
        long clipId = json(만들기(편집본, null).andExpect(status().isCreated())).get("id").asLong();
        완성시킨다(clipId, 영상_하나());

        다시(clipId).andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("nothing_to_retry"));
    }

    /**
     * 완성인데 업로드 줄이 없고 그 판의 의도가 있으면(자동 업로드를 건너뛴 영상: 성공 때 업로드 줄이 꺼져 있었다 등) 다시 시도가 <b>의도로</b>
     * 업로드 줄을 만든다(201, POK-291 로컬 리뷰 1라운드). 보관함의 「업로드」가 이 문을 쓴다: 옛 문으로 가면 고른 설명·태그·공개 범위·썸네일이
     * 빠진다. 렌더 성공 때와 같은 메서드라 벌 고르기·주문서도 같다.
     */
    @Test
    void 올린_적이_없어도_의도가_있으면_의도로_올린다() throws Exception {
        long clipId = json(만들기(편집본, null).andExpect(status().isCreated())).get("id").asLong();
        완성시킨다(clipId, 영상_하나());
        assertThat(업로드_줄_수()).isZero();
        // 성공 보고 뒤에 의도만 남은 판(자동 업로드를 건너뛴 상태)을 만든다.
        jdbc.update("""
                INSERT INTO upload_requests (recipe_id, recipe_version, requested_by, title, description, tags, privacy_status,
                                             made_for_kids, thumbnail_source, thumbnail_offset_ms)
                SELECT recipe_id, recipe_version, '9999', '의도 제목', '의도 설명', '["롤"]'::jsonb, 'unlisted', true, 'scene', 3000
                  FROM clips WHERE id = ?""", clipId);

        JsonNode 응답 = json(다시(clipId).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("queued"))
                .andExpect(jsonPath("$.title").value("의도 제목"))
                .andExpect(jsonPath("$.privacyStatus").value("unlisted"))
                .andExpect(jsonPath("$.thumbnail.source").value("scene")));

        assertThat(업로드_줄_수()).isOne();
        JsonNode 봉투 = MAPPER.readTree(LocalStackFixture.receiveAndDelete(업로드줄.queueUrl()));
        assertThat(봉투.get("uploadId").asString()).isEqualTo(String.valueOf(응답.get("id").asLong()));
        assertThat(봉투.at("/video/description").asString()).isEqualTo("의도 설명");
        assertThat(봉투.at("/video/tags/0").asString()).isEqualTo("롤");
        assertThat(봉투.at("/video/madeForKids").asBoolean()).isTrue();
        assertThat(봉투.at("/thumbnail/offsetMs").asLong()).isEqualTo(3_000L);
        assertThat(jdbc.queryForObject("SELECT requested_by FROM clip_uploads", String.class))
                .as("의도를 남긴 사람이 아니라 지금 누른 사람").isEqualTo(요청자);

        // 연타한 두 번째 누름은 그 줄을 200으로 돌려받는다.
        다시(clipId).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(응답.get("id").asLong()));
        assertThat(업로드_줄_수()).isOne();
    }

    /**
     * 같은 판의 <b>다른</b> 영상(재렌더)에 살아 있는 업로드가 있으면 409 {@code already_uploaded}. 실패한 옛 영상을 다시 올리면
     * 채널에 같은 판이 둘 뜬다.
     */
    @Test
    void 같은_판의_다른_영상이_올라가는_중이면_409다() throws Exception {
        long 첫째 = 올린_영상();
        jdbc.update("UPDATE clip_uploads SET status = 'failed' WHERE clip_id = ?", 첫째);
        long 둘째 = json(만들기(편집본, null).andExpect(status().isCreated())).get("id").asLong();
        완성시킨다(둘째, 영상_하나());
        long 둘째_업로드 = 최신_업로드();
        assertThat(jdbc.queryForObject("SELECT clip_id FROM clip_uploads WHERE id = ?", Long.class, 둘째_업로드))
                .as("의도가 남아 있어 재렌더가 이어서 올렸다").isEqualTo(둘째);

        다시(첫째).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("already_uploaded"))
                .andExpect(jsonPath("$.uploadId").value(둘째_업로드));
        assertThat(업로드_줄_수()).isEqualTo(2);
    }

    @Test
    void 남의_방송_번호와_없는_영상은_404다() throws Exception {
        long clipId = 올린_영상();
        mvc.perform(post("/api/clip/broadcasts/s-없는방송/clips/" + clipId + "/uploads/retry")
                        .header("Authorization", "Bearer " + TestTokens.access(요청자)))
                .andExpect(status().isNotFound());
        다시(999_999L).andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("clip_not_found"));
    }

    /** 의도를 걸고 만들어 성공시킨다: 자동 업로드 줄이 하나 생긴다. */
    private long 올린_영상() throws Exception {
        long clipId = json(만들기(편집본, 기본_업로드()).andExpect(status().isCreated())).get("id").asLong();
        완성시킨다(clipId, 영상_하나());
        assertThat(업로드_줄_수()).isOne();
        return clipId;
    }

    private long 최신_업로드() {
        return jdbc.queryForObject("SELECT max(id) FROM clip_uploads", Long.class);
    }

    private ResultActions 다시(long clipId) throws Exception {
        ResultActions actions = mvc.perform(post("/api/clip/broadcasts/" + 방송 + "/clips/" + clipId + "/uploads/retry")
                .header("Authorization", "Bearer " + TestTokens.access(요청자)));
        발행을_기다린다();
        return actions;
    }
}
