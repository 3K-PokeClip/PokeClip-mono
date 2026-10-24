package com.pokeclip.clip.upload.retention;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * auth가 「이 회원의 이 채널 영상 번호를 지워라」를 알리는 문(POK-423). 연동 해제 7일 · 철회 30일이 지난 채널이다.
 *
 * <p>재는 것: 그 채널 줄과 채널을 모르는 줄만 지우고, 다른 채널 · 다른 회원 줄은 남는다(남의 것이 남는지를 같이 재야 「표를 통째로
 * 비웠다」가 초록이 되지 않는다). 지운 줄은 올림 그대로라 같은 영상을 다시 올릴 자리가 안 열린다. 몇 번 불러도 결과가 같다.
 */
@AutoConfigureMockMvc
class YoutubeVideoForgetTest extends IntegrationTestSupport {

    private static final String INTERNAL = "test-only-internal-token-32bytes-long!!";
    private static final String 방송 = "s-forget";
    private static final String 주인 = "70001";
    private static final String 이웃 = "70002";
    private static final String 옛_채널 = "UColdoldoldoldoldoldoldo";
    private static final String 새_채널 = "UCnewnewnewnewnewnewnewn";

    private final MockMvc mvc;
    private final JdbcTemplate jdbc;

    YoutubeVideoForgetTest(MockMvc mvc, JdbcTemplate jdbc) {
        this.mvc = mvc;
        this.jdbc = jdbc;
    }

    private long 영상;

    @BeforeEach
    void 준비() {
        방송과_카드를_비운다(jdbc);
        RenderFixtures.방송을_넣는다(jdbc, 방송);
        long recipeId = RenderFixtures.편집본을_넣는다(jdbc, 방송, RenderFixtures.CUT_IN, RenderFixtures.CUT_OUT);
        long[] clipId = new long[1];
        RenderFixtures.주문을_넣는다(jdbc, 방송, recipeId, clipId);
        영상 = clipId[0];
    }

    @AfterEach
    void 지운다() {
        jdbc.update("DELETE FROM clip_uploads");
        방송과_카드를_비운다(jdbc);
    }

    @Test
    void 그_채널과_채널_모르는_줄만_지우고_올림은_그대로다() throws Exception {
        long 옛것 = 업로드("o1", 주인, "vidOLD00001", 옛_채널);
        long 모름 = 업로드("o2", 주인, "vidUNKNOWN1", null);
        long 새것 = 업로드("o3", 주인, "vidNEW00001", 새_채널);
        long 남의것 = 업로드("o4", 이웃, "vidNEIGHBOR", 옛_채널);

        잊어라(주인, 옛_채널).andExpect(status().isOk()).andExpect(jsonPath("$.forgotten").value(2));

        assertThat(줄(옛것)).isEqualTo("uploaded:null:null:forgotten");
        assertThat(줄(모름)).isEqualTo("uploaded:null:null:forgotten");
        assertThat(줄(새것)).isEqualTo("uploaded:vidNEW00001:" + 새_채널 + ":kept");
        assertThat(줄(남의것)).as("다른 회원 줄은 같은 채널이어도 남는다").isEqualTo("uploaded:vidNEIGHBOR:" + 옛_채널 + ":kept");
    }

    @Test
    void 몇_번_불러도_같다() throws Exception {
        long 옛것 = 업로드("o1", 주인, "vidOLD00001", 옛_채널);

        잊어라(주인, 옛_채널).andExpect(jsonPath("$.forgotten").value(1));
        잊어라(주인, 옛_채널).andExpect(status().isOk()).andExpect(jsonPath("$.forgotten").value(0));

        assertThat(줄(옛것)).isEqualTo("uploaded:null:null:forgotten");
    }

    @Test
    void 모양이_틀리면_400이고_토큰이_없으면_401이다() throws Exception {
        업로드("o1", 주인, "vidOLD00001", 옛_채널);

        잊어라("abc", 옛_채널).andExpect(status().isBadRequest());
        잊어라(주인, "채널 <x>").andExpect(status().isBadRequest());
        mvc.perform(post("/internal/youtube-videos/forget").contentType(MediaType.APPLICATION_JSON)
                .content(본문(주인, 옛_채널))).andExpect(status().isUnauthorized());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM clip_uploads WHERE youtube_video_id IS NOT NULL", Integer.class))
                .isEqualTo(1);
    }

    private ResultActions 잊어라(String owner, String channelId) throws Exception {
        return mvc.perform(post("/internal/youtube-videos/forget").header("X-Internal-Token", INTERNAL)
                .contentType(MediaType.APPLICATION_JSON).content(본문(owner, channelId)));
    }

    private static String 본문(String owner, String channelId) {
        return "{\"owner\":\"" + owner + "\",\"channelId\":\"" + channelId + "\"}";
    }

    private long 업로드(String outputId, String owner, String videoId, String channelId) {
        return jdbc.queryForObject("""
                INSERT INTO clip_uploads (clip_id, output_id, requested_by, channel_owner, title, status, payload,
                                          youtube_video_id, youtube_channel_id)
                VALUES (?, ?, ?, ?, '제목', 'uploaded', '{}'::jsonb, ?, ?) RETURNING id""", Long.class,
                영상, outputId, owner, owner, videoId, channelId);
    }

    private String 줄(long id) {
        return jdbc.queryForObject("""
                SELECT status || ':' || coalesce(youtube_video_id, 'null') || ':' || coalesce(youtube_channel_id, 'null')
                       || ':' || CASE WHEN video_forgotten_at IS NULL THEN 'kept' ELSE 'forgotten' END
                FROM clip_uploads WHERE id = ?""", String.class, id);
    }
}
