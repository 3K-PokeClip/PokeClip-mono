package com.pokeclip.auth.streamkey.api;

import com.pokeclip.auth.streamkey.SecretStore;
import com.pokeclip.auth.streamkey.StreamKey;
import com.pokeclip.auth.streamkey.StreamKeyMaterial;
import com.pokeclip.auth.streamkey.StreamKeyRepository;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class StreamKeyRotateTest extends IntegrationTestSupport {

    private static final String INTERNAL_TOKEN = "test-only-internal-token-32bytes-long!!";

    private final MockMvc mockMvc;
    private final StreamKeyService streamKeyService;
    private final StreamKeyRepository streamKeyRepository;
    private final SecretStore secretStore;
    private final UserService userService;
    private final UserRepository userRepository;
    private final TokenService tokenService;
    private final JdbcTemplate jdbc;

    StreamKeyRotateTest(MockMvc mockMvc, StreamKeyService streamKeyService,
                        StreamKeyRepository streamKeyRepository, SecretStore secretStore,
                        UserService userService, UserRepository userRepository,
                        TokenService tokenService, JdbcTemplate jdbc) {
        this.mockMvc = mockMvc;
        this.streamKeyService = streamKeyService;
        this.streamKeyRepository = streamKeyRepository;
        this.secretStore = secretStore;
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
        jdbc.update("DELETE FROM pairing_exchange_attempts");
        jdbc.update("DELETE FROM pairing_codes");
        jdbc.update("DELETE FROM stream_keys");
        jdbc.update("DELETE FROM secrets");
    }

    @Test
    void 재발급하면_새_키가_나온다() throws Exception {
        User user = newUser();
        StreamKeyMaterial before = streamKeyService.ensureKey(user.getId());

        mockMvc.perform(post("/api/stream-keys/rotate").header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rotatedAt").exists());

        StreamKeyMaterial after = streamKeyService.findMaterial(user.getId()).orElseThrow();
        assertThat(after.streamToken()).isNotEqualTo(before.streamToken());
        assertThat(after.passphrase()).isNotEqualTo(before.passphrase());
    }

    /** POK-68의 핵심. 이전 키로 검증하면 POK-71이 폐기됨을 돌려준다. */
    @Test
    void 이전_키로_검증하면_REVOKED다() throws Exception {
        User user = newUser();
        StreamKeyMaterial before = streamKeyService.ensureKey(user.getId());

        mockMvc.perform(post("/api/stream-keys/rotate").header("Authorization", bearer(user)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/internal/stream-keys/resolve")
                        .header("X-Internal-Token", INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"streamid\":\"" + before.streamId().toSrtFormat() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.reason").value("REVOKED"));
    }

    /** POK-68: Secrets Manager의 이전 passphrase도 정리한다. */
    @Test
    void 이전_secret이_지워진다() throws Exception {
        User user = newUser();
        streamKeyService.ensureKey(user.getId());
        String oldRef = streamKeyService.findAlive(user.getId()).orElseThrow().getPassphraseRef();

        mockMvc.perform(post("/api/stream-keys/rotate").header("Authorization", bearer(user)))
                .andExpect(status().isOk());

        assertThat(secretStore.get(oldRef))
                .as("옛 passphrase가 보관소에 남아 있다")
                .isEmpty();
    }

    /** 이력이 남아야 사고 조사가 된다. 부분 유니크 인덱스는 폐기 행을 안 센다. */
    @Test
    void 이전_행은_폐기_시각과_함께_남는다() throws Exception {
        User user = newUser();
        streamKeyService.ensureKey(user.getId());

        mockMvc.perform(post("/api/stream-keys/rotate").header("Authorization", bearer(user)));

        assertThat(streamKeyRepository.findAll())
                .hasSize(2)
                .filteredOn(StreamKey::isRevoked)
                .hasSize(1);
    }

    /** 조용히 새로 발급하면 "무효화가 일어났다"는 로그가 거짓이 된다. */
    @Test
    void 키가_없으면_404다() throws Exception {
        mockMvc.perform(post("/api/stream-keys/rotate").header("Authorization", bearer(newUser())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.reason").value("STREAM_KEY_NOT_FOUND"));
    }

    /** 두 번 재발급해도 살아있는 키는 늘 하나다. */
    @Test
    void 여러_번_재발급해도_살아있는_키는_하나다() throws Exception {
        User user = newUser();
        streamKeyService.ensureKey(user.getId());
        String bearer = bearer(user);

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/stream-keys/rotate").header("Authorization", bearer))
                    .andExpect(status().isOk());
        }

        assertThat(streamKeyRepository.findByUserIdAndRevokedAtIsNull(user.getId())).isPresent();
        assertThat(streamKeyRepository.findAll()).hasSize(4);
    }

    @Test
    void 토큰이_없으면_401이다() throws Exception {
        mockMvc.perform(post("/api/stream-keys/rotate")).andExpect(status().isUnauthorized());
    }

    private String bearer(User user) {
        return "Bearer " + tokenService.issue(user).accessToken();
    }

    private User newUser() {
        return userService.findOrCreate(
                "sub-" + UUID.randomUUID(), "a@example.com", "김태현", null);
    }
}
