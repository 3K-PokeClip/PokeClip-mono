package com.pokeclip.auth.streamkey.api;

import com.pokeclip.auth.streamkey.StreamKeyMaterial;
import com.pokeclip.auth.streamkey.StreamKeyService;
import com.pokeclip.auth.support.IntegrationTestSupport;
import com.pokeclip.auth.token.TokenService;
import com.pokeclip.auth.user.User;
import com.pokeclip.auth.user.UserRepository;
import com.pokeclip.auth.user.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class StreamKeyControllerTest extends IntegrationTestSupport {

    private final MockMvc mockMvc;
    private final StreamKeyService streamKeyService;
    private final UserService userService;
    private final UserRepository userRepository;
    private final TokenService tokenService;
    private final JdbcTemplate jdbc;

    StreamKeyControllerTest(MockMvc mockMvc, StreamKeyService streamKeyService,
                            UserService userService, UserRepository userRepository,
                            TokenService tokenService, JdbcTemplate jdbc) {
        this.mockMvc = mockMvc;
        this.streamKeyService = streamKeyService;
        this.userService = userService;
        this.userRepository = userRepository;
        this.tokenService = tokenService;
        this.jdbc = jdbc;
    }

    @BeforeEach
    void setUp() {
        clearChildren();
        userRepository.deleteAll();
    }

    @AfterEach
    void tearDown() {
        clearChildren();
    }

    private void clearChildren() {
        // refresh_tokens도 users의 자식이다(V101:16). tokenService.issue가 행을
        // 만들므로 이것을 빼면 아래 userRepository.deleteAll()이 FK 위반으로 터진다.
        jdbc.update("DELETE FROM refresh_tokens");
        jdbc.update("DELETE FROM stream_keys");
        jdbc.update("DELETE FROM secrets");
    }

    @Test
    void 키가_없으면_issued가_false다() throws Exception {
        mockMvc.perform(get("/api/stream-keys").header("Authorization", bearer(newUser())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issued").value(false))
                .andExpect(jsonPath("$.createdAt").doesNotExist());
    }

    @Test
    void 키가_있으면_issued와_발급일을_준다() throws Exception {
        User user = newUser();
        streamKeyService.ensureKey(user.getId());

        mockMvc.perform(get("/api/stream-keys").header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issued").value(true))
                .andExpect(jsonPath("$.createdAt").exists());
    }

    /**
     * 응답에 긴 비밀이 절대 안 실린다. 편의로 streamid를 되돌려주는 변경이
     * 들어오면 여기서 걸린다 — 원문을 저장하지 않으므로 그런 변경은 곧
     * "원문을 저장하기 시작했다"는 뜻이다.
     */
    @Test
    void 응답에_streamid도_passphrase도_없다() throws Exception {
        User user = newUser();
        StreamKeyMaterial material = streamKeyService.ensureKey(user.getId());

        String body = mockMvc.perform(
                        get("/api/stream-keys").header("Authorization", bearer(user)))
                .andReturn().getResponse().getContentAsString();

        assertThat(body)
                .doesNotContain(material.streamToken())
                .doesNotContain(material.passphrase())
                .doesNotContain("passphrase")
                .doesNotContain("streamid");
    }

    @Test
    void 토큰이_없으면_401이다() throws Exception {
        mockMvc.perform(get("/api/stream-keys")).andExpect(status().isUnauthorized());
    }

    private String bearer(User user) {
        return "Bearer " + tokenService.issue(user).accessToken();
    }

    private User newUser() {
        return userService.findOrCreate(
                "sub-" + UUID.randomUUID(), "a@example.com", "김태현", null);
    }
}
