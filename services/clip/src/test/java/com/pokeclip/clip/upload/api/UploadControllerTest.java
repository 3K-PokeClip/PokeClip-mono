package com.pokeclip.clip.upload.api;

import com.pokeclip.clip.render.RenderFixtures;
import com.pokeclip.clip.support.IntegrationTestSupport;
import com.pokeclip.clip.support.LocalStackFixture;
import com.pokeclip.clip.support.TestIds;
import com.pokeclip.clip.support.TestTokens;
import com.pokeclip.clip.upload.UploadDlqReconciler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 유튜브 업로드 주문(POK-220). 재는 것의 중심은 <b>「영상은 하나」</b>다: 두 번 눌러도, 일꾼이 둘이어도, 쪽지가 두 번 와도,
 * 실패 큐에 떨어져도 채널에 같은 영상이 둘 생길 길이 없어야 한다. 그 길은 둘뿐이다: 새 업로드 줄이 생기거나(선점이 막는다),
 * 한 줄이 두 이어 올리기 주소로 바이트를 보내거나(주소 기록이 막는다). 둘 다 여기서 잰다.
 *
 * <p>줄은 LocalStack의 진짜 SQS다(렌더 주문 시험과 같은 모양).
 */
@AutoConfigureMockMvc
class UploadControllerTest extends IntegrationTestSupport {

    private static final String RESOLVE = "/internal/editor-delegations/resolve";
    private static final String INTERNAL = "test-only-internal-token-32bytes-long!!";
    private static final String 요청자 = "4180";
    private static final String 내_방송 = "s-upload";
    private static final String 다른_방송 = "s-upload-2";
    private static final String 주소1 = "https://www.googleapis.com/upload/youtube/v3/videos?uploadType=resumable&upload_id=AAA";
    private static final String 주소2 = "https://www.googleapis.com/upload/youtube/v3/videos?uploadType=resumable&upload_id=BBB";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final LocalStackFixture.QueuePair 줄 = LocalStackFixture.createStandardQueueWithDlq("jobs-upload-test", 3);

    @DynamicPropertySource
    static void uploadProperties(DynamicPropertyRegistry registry) {
        registry.add("pokeclip.upload.enabled", () -> "true");
        registry.add("pokeclip.upload.queue-url", 줄::queueUrl);
        registry.add("pokeclip.upload.dlq-url", 줄::dlqUrl);
        registry.add("pokeclip.upload.region", LocalStackFixture::region);
        registry.add("pokeclip.upload.endpoint", LocalStackFixture::endpoint);
        registry.add("pokeclip.render.output-bucket", () -> "clips-test");
    }

    private final MockMvc mvc;
    private final JdbcTemplate jdbc;
    private final UploadDlqReconciler reconciler;

    UploadControllerTest(MockMvc mvc, JdbcTemplate jdbc, UploadDlqReconciler reconciler) {
        this.mvc = mvc;
        this.jdbc = jdbc;
        this.reconciler = reconciler;
    }

    private long 편집본;

    @BeforeEach
    void 준비() {
        방송과_카드를_비운다(jdbc);
        RenderFixtures.방송을_넣는다(jdbc, 내_방송);
        RenderFixtures.방송을_넣는다(jdbc, 다른_방송);
        편집본 = RenderFixtures.편집본을_넣는다(jdbc, 내_방송, RenderFixtures.CUT_IN, RenderFixtures.CUT_OUT);
        while (LocalStackFixture.receiveAndDelete(줄.queueUrl()) != null) { }
        while (LocalStackFixture.receiveAndDelete(줄.dlqUrl()) != null) { }
    }

    @AfterEach
    void 내_흔적을_지운다() {
        jdbc.update("DELETE FROM clip_uploads");
        jdbc.update("DELETE FROM render_job_events");
        jdbc.update("DELETE FROM render_jobs");
        jdbc.update("DELETE FROM clips");
        jdbc.update("DELETE FROM recipes");
    }

    // ── 주문 ─────────────────────────────────────────────────────

    /**
     * 주문서가 줄에 <b>실제로</b> 실리고, 채널 주인은 주문한 사람이 아니라 방송의 스트리머이며(ADR-010), 비공개이고,
     * 🔴 토큰이 없다(주문서는 로그·실패 큐에 남는다).
     */
    @Test
    void 주문하면_201이고_스트리머_채널로_비공개_주문서가_실린다() throws Exception {
        볼_수_있다();
        long clipId = 완성된_영상("""
                [{"outputId":"o1","kind":"video","s3Key":"clips/%1$d/t1/o1.mp4"},
                 {"outputId":"o1","kind":"srt","s3Key":"clips/%1$d/t1/o1.srt"}]""");

        JsonNode 응답 = 본문(주문(내_방송, clipId, "{\"title\":\"  펜타킬 순간  \",\"description\":\"설명\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("queued"))
                .andExpect(jsonPath("$.outputId").value("o1"))
                .andExpect(jsonPath("$.title").value("펜타킬 순간"))
                .andExpect(jsonPath("$.requestedBy").value(요청자)));

        String 쪽지 = LocalStackFixture.receiveAndDelete(줄.queueUrl());
        assertThat(쪽지).as("주문서가 줄에 안 실렸다").isNotNull();
        JsonNode 봉투 = MAPPER.readTree(쪽지);
        assertThat(봉투.get("jobType").asString()).isEqualTo("UPLOAD");
        assertThat(봉투.get("uploadId").asString()).isEqualTo(String.valueOf(응답.get("id").asLong()));
        assertThat(봉투.get("channelOwnerUserId").asString()).as("주문한 사람이 아니라 방송 주인 채널").isEqualTo(TestIds.STREAMER);
        assertThat(봉투.get("source").get("bucket").asString()).isEqualTo("clips-test");
        assertThat(봉투.get("source").get("s3Key").asString()).isEqualTo("clips/" + clipId + "/t1/o1.mp4");
        assertThat(봉투.get("video").get("privacyStatus").asString()).isEqualTo("private");
        assertThat(봉투.get("video").get("title").asString()).isEqualTo("펜타킬 순간");
        assertThat(쪽지.toLowerCase()).doesNotContain("token");
        assertThat(jdbc.queryForObject("SELECT published_at IS NOT NULL FROM clip_uploads", Boolean.class)).isTrue();
    }

    /** 두 번 누르면 두 번째는 200이고 같은 업로드다. 줄에도 한 통뿐이다. */
    @Test
    void 두_번_주문하면_같은_업로드이고_줄에_한_통이다() throws Exception {
        볼_수_있다();
        long clipId = 완성된_영상(영상_하나());

        long 첫째 = 본문(주문(내_방송, clipId, 제목("하나")).andExpect(status().isCreated())).get("id").asLong();
        long 둘째 = 본문(주문(내_방송, clipId, 제목("둘")).andExpect(status().isOk())).get("id").asLong();

        assertThat(둘째).isEqualTo(첫째);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM clip_uploads", Integer.class)).isEqualTo(1);
        assertThat(LocalStackFixture.receiveAndDelete(줄.queueUrl())).isNotNull();
        assertThat(LocalStackFixture.receiveAndDelete(줄.queueUrl())).as("두 번째 주문서가 실렸다").isNull();
    }

    /** 이미 올렸거나 결과를 모르면 다시 주문해도 새 업로드가 안 생긴다. 실패(영상이 없는 것이 확실)만 자리를 비운다. */
    @Test
    void 실패만_다시_올릴_수_있고_올림_확인중은_새로_안_만든다() throws Exception {
        볼_수_있다();
        long clipId = 완성된_영상(영상_하나());
        long 첫째 = 본문(주문(내_방송, clipId, 제목("하나")).andExpect(status().isCreated())).get("id").asLong();

        jdbc.update("UPDATE clip_uploads SET status = 'checking' WHERE id = ?", 첫째);
        주문(내_방송, clipId, 제목("다시")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("checking"));
        jdbc.update("UPDATE clip_uploads SET status = 'uploaded', youtube_video_id = 'abcDEF12345' WHERE id = ?", 첫째);
        주문(내_방송, clipId, 제목("다시")).andExpect(status().isOk()).andExpect(jsonPath("$.videoId").value("abcDEF12345"));
        jdbc.update("UPDATE clip_uploads SET status = 'failed', youtube_video_id = NULL WHERE id = ?", 첫째);
        long 새것 = 본문(주문(내_방송, clipId, 제목("다시")).andExpect(status().isCreated())).get("id").asLong();

        assertThat(새것).isNotEqualTo(첫째);
    }

    @Test
    void 완성_전이면_409다() throws Exception {
        볼_수_있다();
        long[] clipId = new long[1];
        RenderFixtures.주문을_넣는다(jdbc, 내_방송, 편집본, clipId);

        주문(내_방송, clipId[0], 제목("하나")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("clip_not_rendered"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM clip_uploads", Integer.class)).isZero();
    }

    /** 유튜브 제목 규칙(1~100자, 꺾쇠 금지)을 우리가 먼저 본다: 줄에 실린 뒤 유튜브가 거절하면 늦다. 글자는 코드포인트로 센다. */
    @Test
    void 제목과_설명이_유튜브_규칙을_어기면_400이고_아무것도_안_남는다() throws Exception {
        볼_수_있다();
        long clipId = 완성된_영상(영상_하나());

        for (String 나쁜_본문 : new String[]{"{}", 제목("   "), 제목("a".repeat(101)), 제목("<b>굵게</b>")}) {
            주문(내_방송, clipId, 나쁜_본문).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("title"));
        }
        주문(내_방송, clipId, "{\"title\":\"ok\",\"description\":\"" + "가".repeat(1667) + "\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("description"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM clip_uploads", Integer.class)).isZero();

        // 이모지 100개는 UTF-16으로 200칸이지만 유튜브 기준 100자다: 통과해야 한다.
        주문(내_방송, clipId, 제목("🎮".repeat(100))).andExpect(status().isCreated());
    }

    /** 영상 파일이 여럿이면 무엇을 올릴지 우리가 안 고른다. 고르면 그것이 실린다. */
    @Test
    void 영상_파일이_여럿이면_벌을_골라야_한다() throws Exception {
        볼_수_있다();
        long clipId = 완성된_영상("""
                [{"outputId":"vert","kind":"video","s3Key":"clips/%1$d/t1/vert.mp4"},
                 {"outputId":"square","kind":"video","s3Key":"clips/%1$d/t1/square.mp4"}]""");

        주문(내_방송, clipId, 제목("하나")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("outputId"));
        주문(내_방송, clipId, "{\"title\":\"하나\",\"outputId\":\"nope\"}").andExpect(status().isBadRequest());
        주문(내_방송, clipId, "{\"title\":\"하나\",\"outputId\":\"square\"}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.outputId").value("square"));

        JsonNode 봉투 = MAPPER.readTree(LocalStackFixture.receiveAndDelete(줄.queueUrl()));
        assertThat(봉투.get("source").get("s3Key").asString()).isEqualTo("clips/" + clipId + "/t1/square.mp4");
    }

    @Test
    void 다른_방송_번호와_자격_없음은_404다() throws Exception {
        볼_수_있다();
        long clipId = 완성된_영상(영상_하나());
        주문(다른_방송, clipId, 제목("하나")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("clip_not_found"));
        볼_수_없다();
        주문(내_방송, clipId, 제목("하나")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("broadcast_not_found"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM clip_uploads", Integer.class)).isZero();
    }

    // ── 일꾼 문 ────────────────────────────────────────────────────

    /**
     * 🔴 두 일꾼이 같은 주문을 동시에 잡아 각자 이어 올리기 주소를 받아도 <b>먼저 적힌 하나만</b> 남고 둘 다 그것을 받는다 :
     * 바이트가 한 주소로만 가니 영상은 하나다. 쪽지가 다시 와서 잡으면(재배달·재시작) 새 주소가 아니라 그 주소를 받는다.
     */
    @Test
    void 이어_올리기_주소는_먼저_적힌_하나만_남고_다시_잡으면_그것을_받는다() throws Exception {
        볼_수_있다();
        long id = 주문한_업로드();

        일꾼(id, "start", "{}").andExpect(status().isOk())
                .andExpect(jsonPath("$.proceed").value(true))
                .andExpect(jsonPath("$.attempt").value(1))
                .andExpect(jsonPath("$.sessionUri").doesNotExist());
        일꾼(id, "session", 주소_본문(주소1)).andExpect(status().isOk()).andExpect(jsonPath("$.sessionUri").value(주소1));
        일꾼(id, "session", 주소_본문(주소2)).andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionUri").value(주소1));

        일꾼(id, "start", "{}").andExpect(status().isOk())
                .andExpect(jsonPath("$.proceed").value(true))
                .andExpect(jsonPath("$.attempt").value(2))
                .andExpect(jsonPath("$.sessionUri").value(주소1));
    }

    /** 끝 보고는 같은 것이 다시 와도 200이고, 다른 끝으로는 못 덮는다. 끝난 주문을 다시 잡으면 {@code proceed:false}. */
    @Test
    void 끝_보고는_멱등이고_다른_끝으로_못_덮는다() throws Exception {
        볼_수_있다();
        long id = 주문한_업로드();
        일꾼(id, "start", "{}");
        일꾼(id, "session", 주소_본문(주소1));

        일꾼(id, "result", "{\"outcome\":\"UPLOADED\",\"videoId\":\"abcDEF12345\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("uploaded"));
        일꾼(id, "result", "{\"outcome\":\"UPLOADED\",\"videoId\":\"abcDEF12345\"}").andExpect(status().isOk());
        일꾼(id, "result", "{\"outcome\":\"UPLOADED\",\"videoId\":\"zzzzzzzzzzz\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("TERMINAL"));
        일꾼(id, "result", "{\"outcome\":\"FAILED\",\"errorCode\":\"X\"}").andExpect(status().isConflict());
        일꾼(id, "start", "{}").andExpect(status().isOk())
                .andExpect(jsonPath("$.proceed").value(false))
                .andExpect(jsonPath("$.status").value("uploaded"));
        일꾼(id, "session", 주소_본문(주소2)).andExpect(status().isConflict());
    }

    /**
     * 🔴 주소가 적힌 뒤에는 「실패」로 닫지 않는다(PR #198 codex P1). 같은 주소로 두 일꾼이 올리는 중 하나가 실패를 보고해 자리가 비면,
     * 그 사이 다시 주문한 업로드와 늦게 끝난 쪽의 영상이 둘 뜬다. 그래서 실패 보고는 확인 중이 되고, 늦게 온 올림 보고는 확인 중을 이긴다.
     * 자리는 끝내 안 빈다.
     */
    @Test
    void 주소가_적힌_뒤의_실패_보고는_확인_중이고_늦은_올림이_이긴다() throws Exception {
        볼_수_있다();
        long id = 주문한_업로드();
        long clipId = jdbc.queryForObject("SELECT clip_id FROM clip_uploads WHERE id = ?", Long.class, id);
        일꾼(id, "start", "{}");
        일꾼(id, "session", 주소_본문(주소1));

        일꾼(id, "result", "{\"outcome\":\"FAILED\",\"errorCode\":\"YOUTUBE_REJECTED\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("checking"));
        // 그 200이 유실돼 같은 보고가 다시 와도 200이다(멱등, PR #198 codex 2판).
        일꾼(id, "result", "{\"outcome\":\"FAILED\",\"errorCode\":\"YOUTUBE_REJECTED\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("checking"));
        주문(내_방송, clipId, 제목("다시")).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));

        일꾼(id, "result", "{\"outcome\":\"UPLOADED\",\"videoId\":\"abcDEF12345\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("uploaded"));
        일꾼(id, "result", "{\"outcome\":\"FAILED\",\"errorCode\":\"X\"}").andExpect(status().isConflict());
        assertThat(상태(id)).isEqualTo("uploaded:null");
    }

    /** 주소를 받기 전의 실패는 그대로 실패다: 바이트가 갈 곳이 없었으니 영상이 생길 수 없다. 자리가 비어 다시 올릴 수 있다. */
    @Test
    void 주소를_받기_전의_실패는_실패다() throws Exception {
        볼_수_있다();
        long id = 주문한_업로드();
        일꾼(id, "start", "{}");
        일꾼(id, "result", "{\"outcome\":\"FAILED\",\"errorCode\":\"QUOTA_EXCEEDED\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("failed"));
    }

    /** 🔴 일꾼의 오류 문장에 이어 올리기 주소가 섞여도 화면에는 안 나간다(PR #198 codex P1). 주소 모양 글자를 지워 저장한다. */
    @Test
    void 오류_문장_속_주소는_지워서_저장한다() throws Exception {
        볼_수_있다();
        long id = 주문한_업로드();
        long clipId = jdbc.queryForObject("SELECT clip_id FROM clip_uploads WHERE id = ?", Long.class, id);
        일꾼(id, "start", "{}");
        일꾼(id, "result", "{\"outcome\":\"FAILED\",\"errorCode\":\"X\",\"errorMessage\":\"끊겼다 " + 주소1 + " 에서\"}");

        String 조회 = 본문문자(mvc.perform(get("/api/clip/broadcasts/" + 내_방송 + "/clips/" + clipId).header("Authorization", 토큰()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.upload.error.message").value("끊겼다 [주소 지움] 에서")));
        assertThat(조회).doesNotContain("upload_id=");
        assertThat(jdbc.queryForObject("SELECT error_message FROM clip_uploads WHERE id = ?", String.class, id))
                .doesNotContain("googleapis");
    }

    @Test
    void 잡기_전의_보고와_모양이_틀린_보고는_거절한다() throws Exception {
        볼_수_있다();
        long id = 주문한_업로드();

        일꾼(id, "session", 주소_본문(주소1)).andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("NOT_STARTED"));
        일꾼(id, "result", "{\"outcome\":\"UPLOADED\",\"videoId\":\"abcDEF12345\"}").andExpect(status().isConflict());
        일꾼(id, "start", "{}");
        일꾼(id, "session", 주소_본문("ftp://x")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("sessionUri"));
        일꾼(id, "result", "{\"outcome\":\"UPLOADED\"}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("videoId"));
        일꾼(id, "result", "{\"outcome\":\"FAILED\"}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("errorCode"));
        일꾼(id, "result", "{\"outcome\":\"NOPE\"}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("outcome"));
        일꾼(999_999L, "start", "{}").andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("upload_not_found"));
        mvc.perform(post("/internal/uploads/" + id + "/start")).andExpect(status().isUnauthorized());
    }

    /**
     * 🔴 이어 올리기 주소는 그 자체가 올리기 권한이다: 사람 문(영상 조회·보관함) 어디에도 나가면 안 된다. 일꾼 문만 준다.
     * 올린 뒤에는 영상 조회에 {@code upload}가 붙고 보관함 상태는 {@code uploaded}다.
     */
    @Test
    void 사람_문에는_이어_올리기_주소가_없고_올린_상태가_보인다() throws Exception {
        볼_수_있다();
        long id = 주문한_업로드();
        long clipId = jdbc.queryForObject("SELECT clip_id FROM clip_uploads WHERE id = ?", Long.class, id);
        일꾼(id, "start", "{}");
        일꾼(id, "session", 주소_본문(주소1));

        String 조회 = 본문문자(mvc.perform(get("/api/clip/broadcasts/" + 내_방송 + "/clips/" + clipId).header("Authorization", 토큰()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.upload.status").value("uploading")));
        assertThat(조회).doesNotContain("upload_id=").doesNotContain("sessionUri");

        일꾼(id, "result", "{\"outcome\":\"UPLOADED\",\"videoId\":\"abcDEF12345\"}");
        볼_수_있는_스트리머();
        String 목록 = 본문문자(mvc.perform(get("/api/clip/library?status=uploaded").header("Authorization", 토큰()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].status").value("uploaded"))
                .andExpect(jsonPath("$.items[0].latestClip.upload.videoId").value("abcDEF12345")));
        assertThat(목록).doesNotContain("upload_id=");
    }

    // ── 실패 큐 ────────────────────────────────────────────────────

    /**
     * 🔴 실패 큐에 떨어진 주문: 주소가 적힌 적 없으면 {@code failed}(영상이 생길 수 없었다), 있으면 {@code checking}
     * (바이트가 다 갔을 수 있다). 주소가 있는데 {@code failed}로 닫으면 자리가 비어 다시 올리고 영상이 둘 뜬다. 끝난 것은 안 건드린다.
     */
    @Test
    void 실패_큐의_주문은_주소가_없으면_실패_있으면_확인_중으로_닫는다() throws Exception {
        볼_수_있다();
        long 시작_안_함 = 주문한_업로드();
        long 주소_있음 = 주문한_업로드_다른_영상();
        일꾼(주소_있음, "start", "{}");
        일꾼(주소_있음, "session", 주소_본문(주소1));
        long 올림 = 주문한_업로드_다른_영상();
        일꾼(올림, "start", "{}");
        일꾼(올림, "result", "{\"outcome\":\"UPLOADED\",\"videoId\":\"abcDEF12345\"}");
        for (long id : new long[]{시작_안_함, 주소_있음, 올림}) {
            LocalStackFixture.sendPlain(줄.dlqUrl(), "{\"uploadId\":\"" + id + "\"}");
        }
        LocalStackFixture.sendPlain(줄.dlqUrl(), "{못 읽는 쪽지");

        int 닫음 = reconciler.reconcileOnce();

        assertThat(닫음).isEqualTo(2);
        assertThat(상태(시작_안_함)).isEqualTo("failed:SWEPT");
        assertThat(상태(주소_있음)).isEqualTo("checking:SWEPT_AFTER_SESSION");
        assertThat(상태(올림)).isEqualTo("uploaded:null");
        assertThat(LocalStackFixture.approximateMessageCount(줄.dlqUrl())).isZero();
    }

    // ── 도우미 ──────────────────────────────────────────────────

    private void 볼_수_있다() {
        AUTH.respondWith(RESOLVE, 200, "{\"relation\":\"OWNER\"}");
    }

    private void 볼_수_없다() {
        AUTH.respondWith(RESOLVE, 200, "{\"relation\":\"NONE\"}");
    }

    /** 보관함 목록은 「볼 수 있는 스트리머」를 따로 묻는다. */
    private void 볼_수_있는_스트리머() {
        AUTH.respondWith("/internal/editor-delegations/accessible", 200,
                "{\"streamers\":[{\"streamerUserId\":" + TestIds.STREAMER + ",\"relation\":\"OWNER\"}]}");
    }

    private long 완성된_영상(String outputsTemplate) {
        long[] clipId = new long[1];
        RenderFixtures.주문을_넣는다(jdbc, 내_방송, 편집본, clipId);
        jdbc.update("UPDATE clips SET status = 'rendered', outputs = ?::jsonb WHERE id = ?",
                outputsTemplate.formatted(clipId[0]), clipId[0]);
        jdbc.update("UPDATE render_jobs SET status = 'succeeded' WHERE clip_id = ?", clipId[0]);
        return clipId[0];
    }

    private static String 영상_하나() {
        return "[{\"outputId\":\"o1\",\"kind\":\"video\",\"s3Key\":\"clips/%1$d/t1/o1.mp4\"}]";
    }

    private long 주문한_업로드() throws Exception {
        long clipId = 완성된_영상(영상_하나());
        return 본문(주문(내_방송, clipId, 제목("하나")).andExpect(status().isCreated())).get("id").asLong();
    }

    /** 영상 줄마다 편집본이 하나라(열린 주문 선점) 새 편집본을 만든다. */
    private long 주문한_업로드_다른_영상() throws Exception {
        편집본 = RenderFixtures.편집본을_넣는다(jdbc, 내_방송, RenderFixtures.CUT_IN, RenderFixtures.CUT_OUT);
        return 주문한_업로드();
    }

    private String 상태(long id) {
        return jdbc.queryForObject("SELECT status || ':' || coalesce(error_code, 'null') FROM clip_uploads WHERE id = ?",
                String.class, id);
    }

    private static String 제목(String title) {
        return "{\"title\":\"" + title + "\"}";
    }

    private static String 주소_본문(String uri) {
        return "{\"sessionUri\":\"" + uri + "\"}";
    }

    private static String 토큰() {
        return "Bearer " + TestTokens.access(요청자);
    }

    private ResultActions 주문(String streamId, long clipId, String body) throws Exception {
        return mvc.perform(post("/api/clip/broadcasts/" + streamId + "/clips/" + clipId + "/uploads")
                .header("Authorization", 토큰()).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions 일꾼(long uploadId, String door, String body) throws Exception {
        return mvc.perform(post("/internal/uploads/" + uploadId + "/" + door)
                .header("X-Internal-Token", INTERNAL).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static JsonNode 본문(ResultActions actions) throws Exception {
        return MAPPER.readTree(본문문자(actions));
    }

    private static String 본문문자(ResultActions actions) throws Exception {
        return actions.andReturn().getResponse().getContentAsString();
    }
}
