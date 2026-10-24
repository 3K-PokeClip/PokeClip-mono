package com.pokeclip.auth.youtube.retention;

import com.pokeclip.auth.streamkey.secret.SecretStore;
import com.pokeclip.auth.token.TokenService;
import com.pokeclip.auth.user.User;
import com.pokeclip.auth.user.UserRepository;
import com.pokeclip.auth.user.UserService;
import com.pokeclip.auth.youtube.YoutubeChannelLink;
import com.pokeclip.auth.youtube.YoutubeChannelLinkRepository;
import com.pokeclip.auth.youtube.YoutubeCleanupExecutor;
import com.pokeclip.auth.youtube.YoutubeLinkStateCodec;
import com.pokeclip.auth.youtube.YoutubeLinkTestSupport;
import com.pokeclip.auth.youtube.YoutubeLinkWriter;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * clip이 「이 영상들이 유튜브에 아직 있나」를 묻는 문(POK-423, 30일 확인). 토큰은 auth 밖으로 안 나가고 여기서 그 회원 토큰으로 묻는다.
 */
class YoutubeVideoCheckControllerTest extends YoutubeLinkTestSupport {

    YoutubeVideoCheckControllerTest(MockMvc mockMvc, UserService userService, UserRepository userRepository,
                                    TokenService tokenService, YoutubeLinkStateCodec codec,
                                    YoutubeChannelLinkRepository linkRepository, SecretStore secretStore,
                                    YoutubeLinkWriter writer, JdbcTemplate jdbc, YoutubeCleanupExecutor cleanup) {
        super(mockMvc, userService, userRepository, tokenService, codec, linkRepository, secretStore, writer, jdbc,
                cleanup);
    }

    /** 그 회원 토큰으로 묻고, 물은 것 중 있는 것과 그 토큰의 채널을 준다. 토큰은 응답에 없다 */
    @Test
    void 그_회원_토큰으로_묻고_있는_것과_채널을_준다() throws Exception {
        User user = newUser();
        YoutubeChannelLink link = linked(user);
        YOUTUBE.videosExist("vidAAAAAAA1");

        String body = 묻는다(user.getId(), "[\"vidAAAAAAA1\",\"vidBBBBBBB2\"]")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linked").value(true))
                .andExpect(jsonPath("$.channelId").value(link.getChannelId()))
                .andExpect(jsonPath("$.existing", contains("vidAAAAAAA1")))
                .andReturn().getResponse().getContentAsString();

        assertThat(YOUTUBE.lastVideosBearer()).isEqualTo("Bearer at-old");
        assertThat(YOUTUBE.lastVideosQuery()).contains("part=id", "id=vidAAAAAAA1,vidBBBBBBB2");
        assertThat(body).doesNotContain("at-old");
    }

    @Test
    void 연동이_없으면_linked_false다() throws Exception {
        User user = newUser();

        묻는다(user.getId(), "[\"vidAAAAAAA1\"]").andExpect(status().isOk())
                .andExpect(jsonPath("$.linked").value(false));
        assertThat(YOUTUBE.videosCalls()).isZero();
    }

    /** 🔴 구글이 안 되면 503이다. 「없음」으로 답하면 clip이 멀쩡한 영상 번호를 지운다 */
    @Test
    void 구글이_안_되면_503이다() throws Exception {
        User user = newUser();
        linked(user);
        YOUTUBE.videosResponds(500, "{}");

        묻는다(user.getId(), "[\"vidAAAAAAA1\"]").andExpect(status().isServiceUnavailable());

        YOUTUBE.videosResponds(403, "{\"error\":{\"errors\":[{\"reason\":\"quotaExceeded\"}]}}");
        묻는다(user.getId(), "[\"vidAAAAAAA1\"]").andExpect(status().isServiceUnavailable());
    }

    @Test
    void 모양이_틀리면_400이다() throws Exception {
        User user = newUser();
        linked(user);
        StringBuilder 쉰하나 = new StringBuilder("[");
        for (int i = 0; i < 51; i++) {
            쉰하나.append(i == 0 ? "" : ",").append("\"vid").append(String.format("%08d", i)).append("\"");
        }

        묻는다(user.getId(), "[]").andExpect(status().isBadRequest());
        묻는다(user.getId(), 쉰하나 + "]").andExpect(status().isBadRequest());
        묻는다(user.getId(), "[\"a b\"]").andExpect(status().isBadRequest());
        assertThat(YOUTUBE.videosCalls()).isZero();
    }

    private ResultActions 묻는다(Long userId, String videoIds) throws Exception {
        return mockMvc.perform(post("/internal/youtube-videos/check").header("X-Internal-Token", INTERNAL_TOKEN)
                .contentType(APPLICATION_JSON).content("{\"userId\":" + userId + ",\"videoIds\":" + videoIds + "}"));
    }
}
