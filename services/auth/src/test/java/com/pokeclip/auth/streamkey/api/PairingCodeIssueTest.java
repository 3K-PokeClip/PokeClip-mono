package com.pokeclip.auth.streamkey.api;

import com.pokeclip.auth.streamkey.pairing.PairingCodeRepository;
import com.pokeclip.auth.streamkey.StreamKeyRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class PairingCodeIssueTest extends IntegrationTestSupport {

    private final MockMvc mockMvc;
    private final PairingCodeRepository pairingCodeRepository;
    private final StreamKeyRepository streamKeyRepository;
    private final UserService userService;
    private final UserRepository userRepository;
    private final TokenService tokenService;
    private final JdbcTemplate jdbc;

    PairingCodeIssueTest(MockMvc mockMvc, PairingCodeRepository pairingCodeRepository,
                         StreamKeyRepository streamKeyRepository, UserService userService,
                         UserRepository userRepository, TokenService tokenService,
                         JdbcTemplate jdbc) {
        this.mockMvc = mockMvc;
        this.pairingCodeRepository = pairingCodeRepository;
        this.streamKeyRepository = streamKeyRepository;
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
        jdbc.update("DELETE FROM pairing_codes");
        jdbc.update("DELETE FROM stream_keys");
        jdbc.update("DELETE FROM secrets");
    }

    /** ADR-019: 8자 Crockford를 XXXX-XXXX로 표기하고 10분 뒤 만료된다. */
    @Test
    void 코드는_XXXX_XXXX_형식이고_만료시각이_함께_온다() throws Exception {
        mockMvc.perform(post("/api/stream-keys/pairing-codes")
                        .header("Authorization", bearer(newUser())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(
                        org.hamcrest.Matchers.matchesPattern("[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}")))
                .andExpect(jsonPath("$.expiresAt").exists());
    }

    /** 내려줄 키가 있어야 하므로 발급이 ensureKey를 부른다. */
    @Test
    void 키가_없으면_코드를_발급하면서_키도_만든다() throws Exception {
        User user = newUser();
        assertThat(streamKeyRepository.count()).isZero();

        mockMvc.perform(post("/api/stream-keys/pairing-codes")
                        .header("Authorization", bearer(user)))
                .andExpect(status().isCreated());

        assertThat(streamKeyRepository.count()).isEqualTo(1);
    }

    /** 코드 원문을 저장하지 않는다. refresh_tokens와 같은 규칙이다. */
    @Test
    void 표에는_코드_해시만_남는다() throws Exception {
        String body = mockMvc.perform(post("/api/stream-keys/pairing-codes")
                        .header("Authorization", bearer(newUser())))
                .andReturn().getResponse().getContentAsString();
        String code = com.jayway.jsonpath.JsonPath.read(body, "$.code");

        String dump = jdbc.queryForObject(
                // COALESCE가 없으면 표가 빌 때 null이 와서 AssertJ가
                // "not to be null"로 터진다. 검증하려던 것과 다른 메시지다.
                "SELECT COALESCE(string_agg(t::text, ' '), '') FROM pairing_codes t", String.class);

        assertThat(dump).doesNotContain(code);
        assertThat(dump).doesNotContain(code.replace("-", ""));
    }

    /**
     * ADR-019: 계정당 분당 3회. 8자(40bit)는 그 자체로 짧아서, 이 제한이
     * 없으면 찍어서 뚫린다. 이 테스트가 빠지면 8자를 쓸 근거가 무너진다.
     */
    @Test
    void 계정당_분당_3회를_넘으면_429다() throws Exception {
        // 토큰을 한 번만 만들어 재사용한다. bearer()를 부를 때마다
        // refresh_tokens에 행이 하나씩 쌓여 검사와 무관한 잡음이 된다.
        String bearer = bearer(newUser());

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/stream-keys/pairing-codes")
                            .header("Authorization", bearer))
                    .andExpect(status().isCreated());
        }

        mockMvc.perform(post("/api/stream-keys/pairing-codes")
                        .header("Authorization", bearer))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.reason").value("PAIRING_CODE_RATE_LIMITED"));

        assertThat(pairingCodeRepository.count())
                .as("429인데 코드가 발급됐다")
                .isEqualTo(3);
    }

    /** 제한은 계정 단위다. 남의 발급이 내 한도를 깎으면 안 된다. */
    @Test
    void 다른_계정의_발급은_내_한도를_깎지_않는다() throws Exception {
        String other = bearer(newUser());
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/stream-keys/pairing-codes")
                    .header("Authorization", other));
        }

        mockMvc.perform(post("/api/stream-keys/pairing-codes")
                        .header("Authorization", bearer(newUser())))
                .andExpect(status().isCreated());
    }

    @Test
    void 토큰이_없으면_401이다() throws Exception {
        mockMvc.perform(post("/api/stream-keys/pairing-codes"))
                .andExpect(status().isUnauthorized());
    }

    private String bearer(User user) {
        return "Bearer " + tokenService.issue(user).accessToken();
    }

    /** 이메일에도 유일 제약이 있다(V108). 한 테스트가 계정을 여럿 만드므로 주소도 흩는다. */
    private User newUser() {
        String id = UUID.randomUUID().toString();
        return userService.findOrCreate("sub-" + id, id + "@example.com", "김태현", null);
    }
}
