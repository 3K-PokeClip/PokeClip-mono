package com.pokeclip.auth.streamkey.api;

import com.pokeclip.auth.streamkey.secret.SecretStore;
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

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
        // 회원까지 비운다 — 남기면 다음 클래스가 같은 이메일(a@example.com)로 가입하다 「이미 가입된 이메일」로 막힌다.
        // 메서드 순서는 JUnit이 이름 해시로 정해서, 회원을 안 만드는 테스트가 마지막에 돌던 동안에만 우연히 안 보였다
        // (PR #539: 테스트를 하나 더하자 순서가 바뀌어 TokenServiceTest 10건이 setUp에서 터졌다)
        userRepository.deleteAll();
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

    /**
     * 재발급은 새 키다. 옛 키를 받은 플러그인은 더는 못 붙으니(위 REVOKED) 「연결했다」도 같이 사라져야 한다 —
     * 남으면 웹이 송출이 안 되는 회원에게 연결됐다고 말한다(POK-607).
     */
    @Test
    void 재발급하면_연결_시각이_비워진다() throws Exception {
        User user = newUser();
        StreamKeyMaterial material = streamKeyService.ensureKey(user.getId());
        streamKeyService.markPaired(material, Instant.now());
        mockMvc.perform(get("/api/stream-keys").header("Authorization", bearer(user)))
                .andExpect(jsonPath("$.lastPairedAt").exists());

        mockMvc.perform(post("/api/stream-keys/rotate").header("Authorization", bearer(user)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/stream-keys").header("Authorization", bearer(user)))
                .andExpect(jsonPath("$.issued").value(true))
                .andExpect(jsonPath("$.lastPairedAt").doesNotExist());
    }

    /**
     * 교환이 옛 키를 읽은 뒤 재발급이 커밋되는 경합(PR #539 리뷰). 연결 시각은 넘겨준 그 자격증명의 키에만 적는다 —
     * 회원의 살아있는 키로 고르면 플러그인이 받지 못한 새 키에 「연결했다」가 찍혀, 송출할 수 없는 회원에게
     * 웹이 연결됐다고 말한다.
     */
    @Test
    void 재발급_뒤에_옛_자격증명으로_적어도_새_키에는_남지_않는다() throws Exception {
        User user = newUser();
        StreamKeyMaterial old = streamKeyService.ensureKey(user.getId());

        mockMvc.perform(post("/api/stream-keys/rotate").header("Authorization", bearer(user)))
                .andExpect(status().isOk());
        // 교환이 재발급 전에 읽어 둔 자격증명으로 커밋 뒤 기록이 도는 순간
        streamKeyService.markPaired(old, Instant.now());

        mockMvc.perform(get("/api/stream-keys").header("Authorization", bearer(user)))
                .andExpect(jsonPath("$.issued").value(true))
                .andExpect(jsonPath("$.lastPairedAt").doesNotExist());
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

    /**
     * 동시 재발급이 secret을 고아로 남기지 않는다.
     *
     * <p>읽기(previous) · 폐기(revokeAlive) · 삭제(staleRef)가 세 문장으로 갈려 있어
     * 직렬화가 없으면 셋이 서로 다른 키를 가리킬 수 있다. R2가 previous=K0를 읽은 뒤
     * R1이 커밋하면, READ COMMITTED에서 R2의 revokeAlive는 <b>R1이 방금 만든 K1</b>을
     * 지우는데 삭제는 K0의 ref로 나간다 — <b>K1의 secret이 영영 안 지워진다.</b>
     *
     * <p>그래서 살아있는 키 개수만으로는 못 잡는다. 어느 쪽으로 갈리든 살아있는 키는
     * 늘 하나이기 때문이다. <b>secrets 표의 행 수</b>가 이 결함을 드러내는 자리다.
     */
    @Test
    void 동시에_재발급해도_secret이_고아로_남지_않는다() throws Exception {
        User user = newUser();
        streamKeyService.ensureKey(user.getId());
        String bearer = bearer(user);
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Callable<Integer>> jobs = IntStream.range(0, threads)
                    .<Callable<Integer>>mapToObj(i -> () -> {
                        start.await();
                        return mockMvc.perform(post("/api/stream-keys/rotate")
                                        .header("Authorization", bearer))
                                .andReturn().getResponse().getStatus();
                    })
                    .toList();

            // submit → countDown → get. invokeAll은 전부 끝날 때까지 블록하는데
            // 작업들이 start.await()에 걸려 있어 countDown이 뒤면 데드락이다.
            List<Future<Integer>> futures = jobs.stream().map(pool::submit).toList();
            start.countDown();
            List<Integer> statuses = futures.stream().map(f -> {
                try {
                    return f.get();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }).toList();

            // 직렬화되면 각 요청이 자기 차례에 살아있는 키를 새로 읽으므로 전부 성공한다.
            // 락이 없으면 서로의 키를 지우거나 못 찾아 404·500이 섞인다.
            assertThat(statuses)
                    .as("동시 재발급이 서로를 밟았다")
                    .containsOnly(200);
            assertThat(streamKeyRepository.findByUserIdAndRevokedAtIsNull(user.getId()))
                    .as("살아있는 키가 하나가 아니다")
                    .isPresent();
            Integer secrets = jdbc.queryForObject("SELECT count(*) FROM secrets", Integer.class);
            assertThat(secrets)
                    .as("고아 secret이 남았다. 폐기한 키와 삭제한 ref가 서로 다른 키다")
                    .isEqualTo(1);
        }
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
