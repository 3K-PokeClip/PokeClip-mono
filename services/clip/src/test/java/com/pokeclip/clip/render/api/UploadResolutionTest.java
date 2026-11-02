package com.pokeclip.clip.render.api;

import ch.qos.logback.classic.Level;
import com.pokeclip.clip.support.TestIds;
import com.pokeclip.clip.support.TestTokens;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 「확인 필요」 업로드를 사람이 푼다(POK-333). 확인 필요 줄은 판 하나를 묶는다(다시 시도는 그 줄을 돌려주고 같은 판 영상 만들기는
 * 409). 재는 것: 「올라갔다」면 올림으로 보관함 발행됨에 나오고, 「안 올라갔다」면 자리가 비어 다시 시도 · 영상 만들기가 열린다.
 * 🔴 「안 올라갔다」는 마지막 일꾼 활동에서 30분 뒤에만 받는다(그 전에는 일꾼이 아직 같은 주소로 올리고 있을 수 있다).
 */
class UploadResolutionTest extends RenderUploadTestSupport {

    private static final String 영상번호 = "abcDEF12345";

    UploadResolutionTest(MockMvc mvc, JdbcTemplate jdbc) {
        super(mvc, jdbc);
    }

    /**
     * 「올라갔다」로 풀면 올림이 되고 푼 사람 · 시각이 남는다. 썸네일이 결과를 기다리던 줄은 「확인 못 함」으로 닫는다(붙었는지는 일꾼만
     * 안다). 보관함 「발행됨」 거르기에 나온다.
     */
    @Test
    void 올라갔다로_풀면_올림이고_보관함_발행됨에_나온다() throws Exception {
        long[] 줄 = 확인_필요_업로드();

        풀기(줄, "{\"result\":\"uploaded\",\"videoId\":\"https://youtu.be/" + 영상번호 + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(줄[1]))
                .andExpect(jsonPath("$.status").value("uploaded"))
                .andExpect(jsonPath("$.videoId").value(영상번호))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.thumbnail.status").value("failed"))
                .andExpect(jsonPath("$.thumbnail.errorCode").value("THUMBNAIL_UNCONFIRMED"))
                .andExpect(jsonPath("$.resolvedBy").value(요청자))
                .andExpect(jsonPath("$.resolvedAt").isNotEmpty());

        assertThat(상태(줄[1])).isEqualTo("uploaded:null");
        AUTH.respondWith("/internal/editor-delegations/accessible", 200,
                "{\"streamers\":[{\"streamerUserId\":" + TestIds.STREAMER + ",\"relation\":\"OWNER\"}]}");
        mvc.perform(get("/api/clip/library?status=uploaded").header("Authorization", 토큰()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].recipeId").value(편집본))
                .andExpect(jsonPath("$.items[0].latestClip.upload.videoId").value(영상번호));
    }

    /** 사람이 채널에서 복사해 오는 주소 모양을 다 받는다. 번호 모양은 일꾼 보고와 같은 규칙이다. */
    @ParameterizedTest
    @ValueSource(strings = {
            영상번호,
            "  " + 영상번호 + "  ",
            "https://www.youtube.com/watch?v=" + 영상번호 + "&t=3s",
            "https://m.youtube.com/watch?feature=share&v=" + 영상번호,
            "youtube.com/shorts/" + 영상번호 + "?si=x",
            "https://studio.youtube.com/video/" + 영상번호 + "/edit",
    })
    void 영상_주소의_여러_모양에서_번호를_꺼낸다(String 입력) throws Exception {
        long[] 줄 = 확인_필요_업로드();
        풀기(줄, "{\"result\":\"uploaded\",\"videoId\":\"" + 입력 + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.videoId").value(영상번호));
    }

    @Test
    void 결과나_영상_번호가_틀리면_400이고_줄은_그대로다() throws Exception {
        long[] 줄 = 확인_필요_업로드();
        for (String body : List.of("{\"result\":\"maybe\"}", "{}", "{\"result\":\"uploaded\"}",
                "{\"result\":\"uploaded\",\"videoId\":\"https://vimeo.com/" + 영상번호 + "\"}",
                "{\"result\":\"uploaded\",\"videoId\":\"a b\"}")) {
            풀기(줄, body).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.field").value(body.contains("uploaded") ? "videoId" : "result"));
        }
        assertThat(상태(줄[1])).startsWith("checking:");
    }

    /**
     * 「안 올라갔다」: 마지막 일꾼 활동에서 30분 안이면 409 {@code resolution_too_early}이고 줄은 그대로다. 30분이 지나면 실패
     * ({@code CONFIRMED_NOT_UPLOADED})로 자리가 비어 <b>다시 시도</b>가 새 줄을 만든다.
     */
    @Test
    void 안_올라갔다는_30분_뒤에만_받고_다시_시도가_열린다() throws Exception {
        long[] 줄 = 확인_필요_업로드();
        일꾼_활동_시각(줄[1], Duration.ofMinutes(29));

        String 이른_답 = 본문(풀기(줄, 안_올라감()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("resolution_too_early"))
                .andExpect(jsonPath("$.allowedAt").isNotEmpty()));
        assertThat(Instant.parse(MAPPER.readTree(이른_답).get("allowedAt").asString()))
                .isBetween(Instant.now(), Instant.now().plus(Duration.ofMinutes(2)));
        assertThat(상태(줄[1])).startsWith("checking:");

        일꾼_활동_시각(줄[1], Duration.ofMinutes(31));
        풀기(줄, 안_올라감()).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("failed"))
                .andExpect(jsonPath("$.error.code").value("CONFIRMED_NOT_UPLOADED"))
                .andExpect(jsonPath("$.resolvedBy").value(요청자));

        비운다(업로드줄);
        long 새_줄 = json(다시(줄[0]).andExpect(status().isCreated())).get("id").asLong();
        assertThat(새_줄).isNotEqualTo(줄[1]);
        assertThat(업로드_줄_수()).isEqualTo(2);
    }

    /**
     * 「안 올라갔다」로 풀면 같은 판 「영상 만들기(+ 바로 올리기)」도 열린다. 확인 필요인 동안은 409 {@code already_uploaded}다
     * (업로드 정보를 실은 주문만 이 검사를 탄다: 렌더만 하는 주문은 원래 막히지 않는다).
     */
    @Test
    void 안_올라갔다로_풀면_같은_판_영상_만들기가_열린다() throws Exception {
        long[] 줄 = 확인_필요_업로드();
        만들기(편집본, 기본_업로드()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("already_uploaded"))
                .andExpect(jsonPath("$.uploadId").value(줄[1]));

        일꾼_활동_시각(줄[1], Duration.ofMinutes(31));
        풀기(줄, 안_올라감()).andExpect(status().isOk());

        만들기(편집본, 기본_업로드()).andExpect(status().is2xxSuccessful());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM clip_uploads WHERE status <> 'failed'", Integer.class))
                .as("풀린 판에 새 업로드가 섰다").isOne();
    }

    /**
     * 같은 결과를 다시 적으면 200이고 처음 반영이 그대로다(푼 시각이 안 바뀐다). 다른 결과로 덮지 못한다. 일꾼이 끝낸 줄(올리는 중 · 올림)은
     * 확인 필요가 아니라 409 {@code upload_not_checking}이고 지금 상태를 싣는다.
     */
    @Test
    void 같은_결과는_멱등이고_다른_결과와_확인_필요_아닌_줄은_409다() throws Exception {
        long[] 줄 = 확인_필요_업로드();
        String 올라감 = "{\"result\":\"uploaded\",\"videoId\":\"" + 영상번호 + "\"}";
        풀기(줄, 올라감).andExpect(status().isOk());
        Timestamp 처음 = 푼_시각(줄[1]);

        풀기(줄, 올라감).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("uploaded"));
        assertThat(푼_시각(줄[1])).isEqualTo(처음);
        풀기(줄, "{\"result\":\"uploaded\",\"videoId\":\"zzzZZZ99999\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("upload_not_checking"))
                .andExpect(jsonPath("$.status").value("uploaded"));
        풀기(줄, 안_올라감()).andExpect(status().isConflict());

        for (String 일꾼_상태 : new String[]{"uploading", "uploaded"}) {
            jdbc.update("UPDATE clip_uploads SET status = ?, youtube_video_id = ?, resolved_by = NULL, resolved_at = NULL "
                    + "WHERE id = ?", 일꾼_상태, "uploaded".equals(일꾼_상태) ? 영상번호 : null, 줄[1]);
            풀기(줄, 올라감).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value("upload_not_checking"))
                    .andExpect(jsonPath("$.status").value(일꾼_상태));
        }
    }

    /**
     * 판정은 줄을 잠그고 한다: 다른 트랜잭션이 그 줄을 쥐고 있으면 기다렸다가, 그쪽이 바꾼 상태로 판정한다. 그래서 두 사람이 동시에 다른
     * 결과를 적어도 하나만 반영된다(진 쪽은 409). 🔴 두 요청을 그냥 동시에 쏘면 트랜잭션이 짧아 겹치지 않고 잠금을 떼도 초록이었다
     * (결함 주입으로 확인): 줄을 이 시험이 먼저 잡아 겹침을 강제한다.
     */
    @Test
    void 다른_트랜잭션이_줄을_쥐면_기다렸다가_바뀐_상태로_판정한다() throws Exception {
        long[] 줄 = 확인_필요_업로드();
        ExecutorService 하나 = Executors.newSingleThreadExecutor();
        try (Connection 연결 = jdbc.getDataSource().getConnection()) {
            연결.setAutoCommit(false);
            try (PreparedStatement 잡기 = 연결.prepareStatement("SELECT id FROM clip_uploads WHERE id = ? FOR UPDATE")) {
                잡기.setLong(1, 줄[1]);
                잡기.executeQuery().close();
            }
            Future<String> 요청 = 하나.submit(() -> 본문(풀기(줄, "{\"result\":\"uploaded\",\"videoId\":\"" + 영상번호 + "\"}")));
            Thread.sleep(1_000);
            assertThat(요청.isDone()).as("줄을 쥔 트랜잭션을 안 기다렸다: 잠그지 않고 판정한다").isFalse();

            // 쥔 쪽(다른 사람의 「안 올라갔다」)이 먼저 끝난다.
            try (PreparedStatement 바꾸기 = 연결.prepareStatement(
                    "UPDATE clip_uploads SET status = 'failed', error_code = 'CONFIRMED_NOT_UPLOADED', "
                            + "resolved_by = '9999', resolved_at = now() WHERE id = ?")) {
                바꾸기.setLong(1, 줄[1]);
                바꾸기.executeUpdate();
            }
            연결.commit();
            assertThat(MAPPER.readTree(요청.get(30, TimeUnit.SECONDS)).get("error").asString()).isEqualTo("upload_not_checking");
        } finally {
            하나.shutdownNow();
        }
        assertThat(상태(줄[1])).isEqualTo("failed:CONFIRMED_NOT_UPLOADED");
    }

    @Test
    void 남의_방송_다른_영상의_업로드_없는_번호는_404다() throws Exception {
        long[] 줄 = 확인_필요_업로드();
        mvc.perform(post("/api/clip/broadcasts/s-없는방송/clips/" + 줄[0] + "/uploads/" + 줄[1] + "/resolution")
                        .header("Authorization", 토큰()).contentType(MediaType.APPLICATION_JSON).content(안_올라감()))
                .andExpect(status().isNotFound());
        풀기(new long[]{999_999L, 줄[1]}, 안_올라감()).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("clip_not_found"));
        풀기(new long[]{줄[0], 999_999L}, 안_올라감()).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("upload_not_found"));

        AUTH.respondWith(RESOLVE, 200, "{\"relation\":\"NONE\"}");
        풀기(줄, 안_올라감()).andExpect(status().isNotFound());
        assertThat(상태(줄[1])).startsWith("checking:");
    }

    /** 편집자도 푼다(업로드 승인 게이트 없음, 2026-08-31 결정). 자격 판정은 다른 업로드 문과 같다. */
    @Test
    void 편집자도_푼다() throws Exception {
        long[] 줄 = 확인_필요_업로드();
        AUTH.respondWith(RESOLVE, 200, "{\"relation\":\"EDITOR\"}");
        풀기(줄, "{\"result\":\"uploaded\",\"videoId\":\"" + 영상번호 + "\"}").andExpect(status().isOk());
    }

    /**
     * 「안 올라갔다」로 푼 뒤 늦게 온 일꾼의 올림 보고는 지금처럼 409 {@code TERMINAL}로 받지 않는다(새 상태 없음). 대신 WARN에 업로드 ·
     * 영상 번호를 남겨 사람이 채널의 그 영상을 찾게 한다. 대조: 일꾼이 끝낸 실패 줄의 늦은 올림은 이 줄을 안 찍는다.
     */
    @Test
    void 안_올라갔다_뒤_늦은_올림_보고는_받지_않고_WARN에_번호를_남긴다() throws Exception {
        long[] 줄 = 확인_필요_업로드();
        일꾼_활동_시각(줄[1], Duration.ofMinutes(31));
        풀기(줄, 안_올라감()).andExpect(status().isOk());

        try (LogCaptor captor = new LogCaptor()) {
            늦은_올림(줄[1]).andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("TERMINAL"));
            assertThat(captor.levelOf("clip.upload.uploaded_after_resolved_not_uploaded")).isEqualTo(Level.WARN);
            assertThat(captor.messages()).anySatisfy(line -> assertThat(line)
                    .startsWith("clip.upload.uploaded_after_resolved_not_uploaded")
                    .contains("uploadId=" + 줄[1]).contains("clipId=" + 줄[0]).contains("videoId=" + 영상번호));
        }
        assertThat(상태(줄[1])).isEqualTo("failed:CONFIRMED_NOT_UPLOADED");

        jdbc.update("UPDATE clip_uploads SET error_code = 'QUOTA_EXCEEDED', resolved_by = NULL, resolved_at = NULL WHERE id = ?",
                줄[1]);
        try (LogCaptor captor = new LogCaptor()) {
            늦은_올림(줄[1]).andExpect(status().isConflict());
            assertThat(captor.levelOf("clip.upload.uploaded_after_resolved_not_uploaded")).isNull();
        }
    }

    /** 사람이 적은 번호와 일꾼이 늦게 보고한 번호가 다르면 줄은 그대로 두고 WARN에 둘 다 남긴다(사람이 다른 영상을 적었을 수 있다). */
    @Test
    void 사람이_적은_번호와_다른_늦은_올림_보고는_WARN에_둘_다_남긴다() throws Exception {
        long[] 줄 = 확인_필요_업로드();
        풀기(줄, "{\"result\":\"uploaded\",\"videoId\":\"zzzZZZ99999\"}").andExpect(status().isOk());

        try (LogCaptor captor = new LogCaptor()) {
            늦은_올림(줄[1]).andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("TERMINAL"));
            assertThat(captor.messages()).anySatisfy(line -> assertThat(line)
                    .startsWith("clip.upload.resolved_video_mismatch")
                    .contains("resolvedVideoId=zzzZZZ99999").contains("reportedVideoId=" + 영상번호));
        }
        assertThat(jdbc.queryForObject("SELECT youtube_video_id FROM clip_uploads WHERE id = ?", String.class, 줄[1]))
                .isEqualTo("zzzZZZ99999");
    }

    /**
     * 「마지막 일꾼 활동」은 마지막 heartbeat 또는 상태 변경이다(PR #261 codex P1). 줄이 확인 필요가 된 지 30분이 넘었어도 일꾼이 아직
     * heartbeat를 보내면 「안 올라갔다」는 409다(그 일꾼이 같은 주소로 아직 보내고 있다). heartbeat가 끊긴 지 30분이 지나야 받는다.
     */
    @Test
    void heartbeat가_오는_동안은_안_올라갔다가_409이고_끊긴_지_30분_뒤에야_받는다() throws Exception {
        long[] 줄 = 확인_필요_업로드();
        for (int 분 = 0; 분 < 3; 분++) {
            // 확인 필요가 된 지 31분 · 32분 · 33분이 지났지만 그 사이 일꾼이 계속 heartbeat를 보낸다.
            일꾼_활동_시각(줄[1], Duration.ofMinutes(31 + 분));
            heartbeat(줄[1]).andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("checking"))
                    .andExpect(jsonPath("$.touched").value(true));
            풀기(줄, 안_올라감()).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value("resolution_too_early"));
        }
        assertThat(상태(줄[1])).startsWith("checking:");

        // heartbeat가 끊겼다: 마지막 것에서 31분.
        일꾼_활동_시각(줄[1], Duration.ofMinutes(31));
        풀기(줄, 안_올라감()).andExpect(status().isOk());
    }

    /** heartbeat는 상태를 안 바꾸고, 끝난 줄(올림 · 실패)의 시각도 안 옮긴다. 몇 번 와도 같다. 모르는 번호는 404다. */
    @Test
    void heartbeat는_끝난_줄을_건드리지_않고_멱등이다() throws Exception {
        long[] 줄 = 확인_필요_업로드();
        for (String 끝 : new String[]{"uploaded", "failed"}) {
            jdbc.update("UPDATE clip_uploads SET status = ?, youtube_video_id = ? WHERE id = ?",
                    끝, "uploaded".equals(끝) ? 영상번호 : null, 줄[1]);
            일꾼_활동_시각(줄[1], Duration.ofMinutes(40));
            Timestamp 전 = jdbc.queryForObject("SELECT updated_at FROM clip_uploads WHERE id = ?", Timestamp.class, 줄[1]);
            for (int i = 0; i < 2; i++) {
                heartbeat(줄[1]).andExpect(status().isOk())
                        .andExpect(jsonPath("$.status").value(끝))
                        .andExpect(jsonPath("$.touched").value(false));
            }
            assertThat(jdbc.queryForObject("SELECT updated_at FROM clip_uploads WHERE id = ?", Timestamp.class, 줄[1]))
                    .isEqualTo(전);
            assertThat(상태(줄[1])).startsWith(끝 + ":");
        }
        heartbeat(999_999L).andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("upload_not_found"));
    }

    /**
     * 사람이 먼저 「올라갔다」로 풀어 썸네일을 「확인 못 함」으로 닫은 뒤, 같은 영상을 올린 일꾼이 썸네일 결과를 늦게 알려 오면 그 결과를
     * 적는다(PR #261 codex). 응답은 같은 끝 보고라 200이다. 대조: 사람이 안 푼 줄은 같은 끝 보고의 재전송이라 처음 값이 이긴다.
     */
    @Test
    void 사람이_푼_뒤_같은_영상의_늦은_썸네일_결과는_적는다() throws Exception {
        long[] 줄 = 확인_필요_업로드();
        풀기(줄, "{\"result\":\"uploaded\",\"videoId\":\"" + 영상번호 + "\"}").andExpect(status().isOk());
        assertThat(썸네일(줄[1])).isEqualTo("failed:THUMBNAIL_UNCONFIRMED");

        일꾼_올림(줄[1], ",\"thumbnailOutcome\":\"SET\"").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("uploaded"));
        assertThat(썸네일(줄[1])).isEqualTo("set:null");

        // 그 뒤 재전송(FAILED)은 덮지 못한다: 이제 확정 결과다(같은 보고가 두 번 와도 결과가 같다).
        일꾼_올림(줄[1], ",\"thumbnailOutcome\":\"FAILED\",\"thumbnailErrorCode\":\"THUMBNAIL_FORBIDDEN\"")
                .andExpect(status().isOk());
        assertThat(썸네일(줄[1])).isEqualTo("set:null");
    }

    /** 사람이 적은 번호와 다른 영상의 늦은 보고는 썸네일도 안 건드린다(WARN만, 위 시험). 영상 상태도 그대로다. */
    @Test
    void 다른_영상의_늦은_썸네일_결과는_적지_않는다() throws Exception {
        long[] 줄 = 확인_필요_업로드();
        풀기(줄, "{\"result\":\"uploaded\",\"videoId\":\"zzzZZZ99999\"}").andExpect(status().isOk());

        일꾼_올림(줄[1], ",\"thumbnailOutcome\":\"SET\"").andExpect(status().isConflict());
        assertThat(썸네일(줄[1])).isEqualTo("failed:THUMBNAIL_UNCONFIRMED");
        assertThat(상태(줄[1])).isEqualTo("uploaded:null");
    }

    /**
     * 일꾼이 스스로 「썸네일 확인 못 함」으로 끝낸 줄(사람이 안 푼 줄)에 같은 끝 보고가 다시 오면 처음 값이 이긴다(재전송 규칙 그대로).
     * 늦은 썸네일 결과를 적는 것은 사람이 푼 줄뿐이다.
     */
    @Test
    void 일꾼이_끝낸_줄의_재전송은_썸네일을_덮지_않는다() throws Exception {
        long[] 줄 = 확인_필요_업로드();
        jdbc.update("""
                UPDATE clip_uploads SET status = 'uploaded', youtube_video_id = ?, error_code = NULL,
                       thumbnail_status = 'failed', thumbnail_error_code = 'THUMBNAIL_UNCONFIRMED' WHERE id = ?""",
                영상번호, 줄[1]);

        일꾼_올림(줄[1], ",\"thumbnailOutcome\":\"SET\"").andExpect(status().isOk());
        assertThat(썸네일(줄[1])).isEqualTo("failed:THUMBNAIL_UNCONFIRMED");
    }

    // ── 도우미 ──────────────────────────────────────────────────

    /**
     * 의도를 걸고 만들어 완성시킨 영상의 업로드를 확인 필요로 둔다(주소가 적힌 뒤 결론을 못 낸 줄). 썸네일은 결과 대기.
     *
     * @return {영상 번호, 업로드 번호}
     */
    private long[] 확인_필요_업로드() throws Exception {
        long clipId = json(만들기(편집본, 기본_업로드()).andExpect(status().isCreated())).get("id").asLong();
        완성시킨다(clipId, 영상_하나());
        long uploadId = jdbc.queryForObject("SELECT id FROM clip_uploads WHERE clip_id = ?", Long.class, clipId);
        jdbc.update("""
                UPDATE clip_uploads SET status = 'checking', error_code = 'SESSION_GONE',
                       session_uri = 'https://www.googleapis.com/upload/youtube/v3/videos?upload_id=X', thumbnail_status = 'pending'
                 WHERE id = ?""", uploadId);
        return new long[]{clipId, uploadId};
    }

    private void 일꾼_활동_시각(long uploadId, Duration 전) {
        jdbc.update("UPDATE clip_uploads SET updated_at = ? WHERE id = ?", Timestamp.from(Instant.now().minus(전)), uploadId);
    }

    private Timestamp 푼_시각(long uploadId) {
        return jdbc.queryForObject("SELECT resolved_at FROM clip_uploads WHERE id = ?", Timestamp.class, uploadId);
    }

    private String 상태(long uploadId) {
        return jdbc.queryForObject("SELECT status || ':' || coalesce(error_code, 'null') FROM clip_uploads WHERE id = ?",
                String.class, uploadId);
    }

    private static String 안_올라감() {
        return "{\"result\":\"not_uploaded\"}";
    }

    private static String 토큰() {
        return "Bearer " + TestTokens.access(요청자);
    }

    private ResultActions 풀기(long[] 줄, String body) throws Exception {
        return mvc.perform(post("/api/clip/broadcasts/" + 방송 + "/clips/" + 줄[0] + "/uploads/" + 줄[1] + "/resolution")
                .header("Authorization", 토큰()).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions heartbeat(long uploadId) throws Exception {
        return mvc.perform(post("/internal/uploads/" + uploadId + "/heartbeat").header("X-Internal-Token", INTERNAL));
    }

    private ResultActions 일꾼_올림(long uploadId, String thumbnailFields) throws Exception {
        return mvc.perform(post("/internal/uploads/" + uploadId + "/result")
                .header("X-Internal-Token", INTERNAL).contentType(MediaType.APPLICATION_JSON)
                .content("{\"outcome\":\"UPLOADED\",\"videoId\":\"" + 영상번호 + "\"" + thumbnailFields + "}"));
    }

    private String 썸네일(long uploadId) {
        return jdbc.queryForObject("SELECT thumbnail_status || ':' || coalesce(thumbnail_error_code, 'null') "
                + "FROM clip_uploads WHERE id = ?", String.class, uploadId);
    }

    private ResultActions 늦은_올림(long uploadId) throws Exception {
        return mvc.perform(post("/internal/uploads/" + uploadId + "/result")
                .header("X-Internal-Token", INTERNAL).contentType(MediaType.APPLICATION_JSON)
                .content("{\"outcome\":\"UPLOADED\",\"videoId\":\"" + 영상번호 + "\"}"));
    }

    private ResultActions 다시(long clipId) throws Exception {
        ResultActions actions = mvc.perform(post("/api/clip/broadcasts/" + 방송 + "/clips/" + clipId + "/uploads/retry")
                .header("Authorization", 토큰()));
        발행을_기다린다();
        return actions;
    }
}
