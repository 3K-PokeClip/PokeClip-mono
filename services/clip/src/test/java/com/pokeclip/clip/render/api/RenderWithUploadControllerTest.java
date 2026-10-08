package com.pokeclip.clip.render.api;

import com.pokeclip.clip.support.LocalStackFixture;
import com.pokeclip.clip.support.TestIds;
import com.pokeclip.clip.support.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 「영상 만들기」 문의 업로드 갈래(POK-291). 갈래 셋(만드는 중 · 이미 완성 · 그 밖)과 거절 넷(검사 · 연결 없음 · 이미 올림 · auth 장애에
 * 안 막힘)을 잰다. 거절은 전부 <b>아무것도 안 남는다</b>를 같이 잰다: 렌더 주문·의도·업로드 줄 셋 다.
 */
class RenderWithUploadControllerTest extends RenderUploadTestSupport {

    RenderWithUploadControllerTest(MockMvc mvc, JdbcTemplate jdbc) {
        super(mvc, jdbc);
    }

    /** (c) 영상이 없는 판: 렌더를 주문하고 의도를 같은 트랜잭션에 남긴다. 업로드 줄은 아직 없다(렌더 성공 때 생긴다). */
    @Test
    void 영상이_없으면_렌더를_주문하고_의도를_남긴다() throws Exception {
        만들기(편집본, 기본_업로드()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("queued"))
                .andExpect(jsonPath("$.upload").doesNotExist())
                .andExpect(jsonPath("$.uploadRequest.title").value("펜타킬"));

        assertThat(count("clips")).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT requested_by || ':' || title || ':' || privacy_status || ':' || made_for_kids || ':' || tags::text
                       || ':' || thumbnail_source || ':' || thumbnail_offset_ms FROM upload_requests""", String.class))
                .isEqualTo(요청자 + ":펜타킬:unlisted:true:[\"롤\"]:scene:12000");
        assertThat(업로드_줄_수()).isZero();
        assertThat(LocalStackFixture.receiveAndDelete(렌더줄.queueUrl())).as("렌더 주문서").isNotNull();
    }

    /**
     * (a) 같은 판이 만드는 중: 다시 만들지 않고 그 영상(200)을 돌려주고 의도만 덮어쓴다. 마지막 누름이 이긴다: 성공 때 그 값으로 올린다.
     */
    @Test
    void 만드는_중이면_의도만_덮어쓰고_마지막_누름으로_올린다() throws Exception {
        long clipId = json(만들기(편집본, null).andExpect(status().isCreated())).get("id").asLong();

        만들기(편집본, 업로드("{\"title\":\"첫 제목\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(clipId));
        만들기(편집본, 업로드("{\"title\":\"둘째 제목\",\"privacyStatus\":\"public\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(clipId))
                .andExpect(jsonPath("$.uploadRequest.title").value("둘째 제목"));

        assertThat(count("clips")).isOne();
        assertThat(count("upload_requests")).isOne();
        assertThat(LocalStackFixture.receiveAndDelete(렌더줄.queueUrl())).isNotNull();
        assertThat(LocalStackFixture.receiveAndDelete(렌더줄.queueUrl())).as("렌더 주문서가 또 실렸다").isNull();

        완성시킨다(clipId, 영상_하나());

        assertThat(jdbc.queryForObject("SELECT title || ':' || privacy_status FROM clip_uploads", String.class))
                .isEqualTo("둘째 제목:public");
    }

    /**
     * (b) 같은 판 최신 영상이 이미 완성: 다시 렌더하지 않고 그 영상에 바로 업로드 줄을 만들어 커밋 뒤 싣는다(200, upload 칸이 찬다).
     * 렌더 성공 때와 같은 메서드를 쓴다. 이 갈래에서 줄 만들기를 빼면 의도만 남고 아무것도 안 올라간다.
     */
    @Test
    void 이미_완성된_판이면_다시_만들지_않고_바로_올린다() throws Exception {
        long clipId = json(만들기(편집본, null).andExpect(status().isCreated())).get("id").asLong();
        완성시킨다(clipId, 영상_하나());
        비운다(렌더줄);
        assertThat(업로드_줄_수()).isZero();

        JsonNode 응답 = json(만들기(편집본, 기본_업로드()).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(clipId))
                .andExpect(jsonPath("$.status").value("rendered"))
                .andExpect(jsonPath("$.upload.status").value("queued"))
                .andExpect(jsonPath("$.upload.privacyStatus").value("unlisted"))
                .andExpect(jsonPath("$.uploadRequest.title").value("펜타킬")));

        assertThat(count("clips")).as("다시 렌더하지 않는다").isOne();
        assertThat(LocalStackFixture.receiveAndDelete(렌더줄.queueUrl())).isNull();
        assertThat(업로드_줄_수()).isOne();
        String 쪽지 = LocalStackFixture.receiveAndDelete(업로드줄.queueUrl());
        assertThat(쪽지).as("커밋 뒤 바로 실린다").isNotNull();
        assertThat(MAPPER.readTree(쪽지).get("uploadId").asString()).isEqualTo(String.valueOf(응답.at("/upload/id").asLong()));
    }

    /** (c) 마지막 영상이 실패: 새로 만들고(201) 의도를 남긴다. */
    @Test
    void 마지막_영상이_실패면_다시_만든다() throws Exception {
        long 첫째 = json(만들기(편집본, null).andExpect(status().isCreated())).get("id").asLong();
        jdbc.update("UPDATE clips SET status = 'failed' WHERE id = ?", 첫째);

        long 둘째 = json(만들기(편집본, 기본_업로드()).andExpect(status().isCreated())).get("id").asLong();

        assertThat(둘째).isNotEqualTo(첫째);
        assertThat(count("upload_requests")).isOne();
    }

    /**
     * 같은 판이 이미 올라갔거나 올라가는 중이면 409 {@code already_uploaded}(그 업로드 번호)이고 의도도 안 바뀐다. 실패한 업로드는 자리를
     * 비운다: 다시 누르면 (b)로 새로 올린다.
     */
    @Test
    void 같은_판이_살아_있는_업로드가_있으면_409이고_실패면_다시_올린다() throws Exception {
        long clipId = json(만들기(편집본, null).andExpect(status().isCreated())).get("id").asLong();
        완성시킨다(clipId, 영상_하나());
        long uploadId = json(만들기(편집본, 기본_업로드()).andExpect(status().isOk())).at("/upload/id").asLong();

        만들기(편집본, 업로드("{\"title\":\"바꾼 제목\"}")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("already_uploaded"))
                .andExpect(jsonPath("$.uploadId").value(uploadId));
        assertThat(jdbc.queryForObject("SELECT title FROM upload_requests", String.class)).isEqualTo("펜타킬");

        jdbc.update("UPDATE clip_uploads SET status = 'failed' WHERE id = ?", uploadId);
        만들기(편집본, 업로드("{\"title\":\"바꾼 제목\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.upload.title").value("바꾼 제목"));
        assertThat(업로드_줄_수()).isEqualTo(2);
    }

    /** 스트리머의 유튜브 채널이 확실히 없으면 렌더 전에 409. auth에 묻는 번호는 주문한 사람이 아니라 방송의 스트리머다. */
    @Test
    void 유튜브_연결이_없으면_409이고_아무것도_안_남는다() throws Exception {
        AUTH.respondWith(LINK_STATUS, 200, "{\"linked\":false,\"reason\":\"UNLINKED\"}");

        만들기(편집본, 기본_업로드()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("youtube_not_linked"))
                .andExpect(jsonPath("$.reason").value("UNLINKED"));

        assertThat(AUTH.lastPath()).isEqualTo(LINK_STATUS);
        assertThat(MAPPER.readTree(AUTH.lastBody()).get("userId").asLong()).isEqualTo(Long.parseLong(TestIds.STREAMER));
        아무것도_안_남았다();
    }

    /** auth가 5xx를 주거나 답이 늦으면(시한 2초) 연결 확인을 건너뛰고 주문한다: 렌더를 auth 장애로 막지 않는다. */
    @Test
    void auth가_못_답하면_연결_확인을_건너뛰고_주문한다() throws Exception {
        AUTH.respondWith(LINK_STATUS, 500, "");
        만들기(편집본, 기본_업로드()).andExpect(status().isCreated());

        jdbc.update("DELETE FROM render_jobs");
        jdbc.update("DELETE FROM clips");
        연결됐다();
        // 자격 판정(전역 시한 5초)은 3초 뒤에 답을 받고, 연결 확인(2초)은 시한에 걸린다.
        AUTH.holdFor(Duration.ofSeconds(3));
        long 시작 = System.nanoTime();
        만들기(편집본, 기본_업로드()).andExpect(status().isCreated());
        assertThat(Duration.ofNanos(System.nanoTime() - 시작)).as("연결 확인이 시한 없이 매달렸다")
                .isLessThan(Duration.ofSeconds(8));
    }

    /** 검사에 걸리면 400 {@code field}이고 렌더 주문·의도·업로드 줄 무엇도 안 남는다(검사가 트랜잭션 전이다). */
    @Test
    void 검사에_걸리면_400이고_아무것도_안_남는다() throws Exception {
        만들기(편집본, 업로드("{\"title\":\"<b>\"}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("title"));
        만들기(편집본, 업로드("{\"title\":\"t\",\"thumbnail\":{\"source\":\"scene\",\"offsetMs\":45000}}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("thumbnail.offsetMs"));
        // 그림은 JSON으로 못 보낸다(multipart 문 몫).
        만들기(편집본, 업로드("{\"title\":\"t\",\"thumbnail\":{\"source\":\"file\"}}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("thumbnail"));
        만들기(편집본, "{\"upload\":").andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("upload"));

        아무것도_안_남았다();
    }

    /** 보관함 줄은 그 편집본 지금 판의 의도 요약을 싣는다. 업로드 줄이 없을 때 화면이 이 제목을 보인다. */
    @Test
    void 보관함_줄에_지금_판의_의도가_실린다() throws Exception {
        만들기(편집본, 기본_업로드()).andExpect(status().isCreated());
        AUTH.respondWith("/internal/editor-delegations/accessible", 200,
                "{\"streamers\":[{\"streamerUserId\":" + TestIds.STREAMER + ",\"relation\":\"OWNER\"}]}");

        mvc.perform(get("/api/clip/library").header("Authorization", "Bearer " + TestTokens.access(요청자)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].status").value("rendering"))
                .andExpect(jsonPath("$.items[0].uploadRequest.title").value("펜타킬"))
                .andExpect(jsonPath("$.items[0].uploadRequest.thumbnailSource").value("scene"))
                .andExpect(jsonPath("$.items[0].latestClip.uploadRequest.privacyStatus").value("unlisted"));
        mvc.perform(get("/api/clip/library/" + 편집본).header("Authorization", "Bearer " + TestTokens.access(요청자)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uploadRequest.title").value("펜타킬"));
    }

    private void 아무것도_안_남았다() {
        assertThat(count("clips")).isZero();
        assertThat(count("upload_requests")).isZero();
        assertThat(업로드_줄_수()).isZero();
        assertThat(LocalStackFixture.receiveAndDelete(렌더줄.queueUrl())).isNull();
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }
}
