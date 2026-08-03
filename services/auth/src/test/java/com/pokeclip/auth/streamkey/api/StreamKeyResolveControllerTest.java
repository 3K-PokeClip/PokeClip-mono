package com.pokeclip.auth.streamkey.api;

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
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class StreamKeyResolveControllerTest extends IntegrationTestSupport {

    /** application-test.yml의 pokeclip.internal-api.token과 같아야 한다. */
    private static final String INTERNAL_TOKEN = "test-only-internal-token-32bytes-long!!";

    private final MockMvc mockMvc;
    private final StreamKeyService streamKeyService;
    private final StreamKeyRepository streamKeyRepository;
    private final UserService userService;
    private final UserRepository userRepository;
    private final TokenService tokenService;
    private final TransactionTemplate transactionTemplate;
    private final JdbcTemplate jdbc;

    StreamKeyResolveControllerTest(MockMvc mockMvc, StreamKeyService streamKeyService,
                                   StreamKeyRepository streamKeyRepository,
                                   UserService userService, UserRepository userRepository,
                                   TokenService tokenService,
                                   TransactionTemplate transactionTemplate, JdbcTemplate jdbc) {
        this.mockMvc = mockMvc;
        this.streamKeyService = streamKeyService;
        this.streamKeyRepository = streamKeyRepository;
        this.userService = userService;
        this.userRepository = userRepository;
        this.tokenService = tokenService;
        this.transactionTemplate = transactionTemplate;
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
    void 유효한_키면_userId와_passphrase를_준다() throws Exception {
        User user = newUser();
        StreamKeyMaterial material = streamKeyService.ensureKey(user.getId());

        resolve(material.streamId().toSrtFormat())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.userId").value(user.getId()))
                .andExpect(jsonPath("$.passphrase").value(material.passphrase()));
    }

    /**
     * 계약의 핵심. valid:false도 200이다 — Media 입장에서 "키가 틀림"(연결 거절)과
     * "Auth 장애"(판단 불가)는 조치가 정반대인데, 둘 다 4xx면 Go 쪽에서 구분이 안 된다.
     */
    @Test
    void 없는_키도_HTTP_200에_valid_false로_돌려준다() throws Exception {
        String unknown = "#!::r=7ZK3M9QW2XJ4NB6TC8VDFG5HRP,m=publish";

        resolve(unknown)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.reason").value("NOT_FOUND"))
                .andExpect(jsonPath("$.passphrase").doesNotExist());
    }

    /** POK-68이 여기에 기댄다 — 이전 키로 검증하면 폐기됨이 나와야 한다. */
    @Test
    void 폐기된_키는_REVOKED다() throws Exception {
        User user = newUser();
        StreamKeyMaterial material = streamKeyService.ensureKey(user.getId());

        // revokeAlive는 @Modifying(flushAutomatically=true)라 실제 트랜잭션을 요구한다.
        // 준비 단계만 감싼다 — 단언은 아래 그대로다.
        //
        // 저장소 메서드에 @Transactional을 붙이는 쪽을 택하지 않았다. 그러면
        // 트랜잭션 밖 호출이 조용히 성공해 "대체 키 없이 폐기만 커밋되는" 경로가
        // 열린다 — 그 스트리머는 송출도 못 하고 rotate도 404를 받아 복구 경로가
        // 없다. rotate에서 폐기와 삽입은 한 원자 단위여야 하고, 지금 던지는 동작이
        // 그것을 강제하는 안전장치다. 기존 RefreshTokenRepository의
        // revokeIfAlive·revokeAllOfUser도 같은 이유로 @Transactional이 없다.
        transactionTemplate.executeWithoutResult(status ->
                streamKeyRepository.revokeAlive(user.getId(), Instant.now()));

        resolve(material.streamId().toSrtFormat())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.reason").value("REVOKED"))
                .andExpect(jsonPath("$.passphrase").doesNotExist());
    }

    @Test
    void 형식이_깨진_streamid는_MALFORMED다() throws Exception {
        resolve("7ZK3M9QW2XJ4NB6TC8VDFG5HRP")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.reason").value("MALFORMED"));
    }

    /** m=publish가 아니면 송출 요청이 아니다. */
    @Test
    void publish_모드가_아니면_MALFORMED다() throws Exception {
        StreamKeyMaterial material = streamKeyService.ensureKey(newUser().getId());

        resolve("#!::r=" + material.streamToken() + ",m=request")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.reason").value("MALFORMED"));
    }

    @Test
    void 내부_토큰이_없으면_401이다() throws Exception {
        mockMvc.perform(post("/internal/stream-keys/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("#!::r=7ZK3M9QW2XJ4NB6TC8VDFG5HRP,m=publish")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 내부_토큰이_틀리면_401이다() throws Exception {
        mockMvc.perform(post("/internal/stream-keys/resolve")
                        .header("X-Internal-Token", "wrong-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("#!::r=7ZK3M9QW2XJ4NB6TC8VDFG5HRP,m=publish")))
                .andExpect(status().isUnauthorized());
    }

    /**
     * 사용자 JWT로는 이 경로에 못 들어온다. 두 인증 수단이 섞이면 "어느 쪽으로든
     * 통과"가 되는데, 이 엔드포인트는 passphrase를 내려주므로 그 대가가 크다.
     *
     * <p><b>진짜 유효한 access 토큰이어야 한다.</b> 아무 문자열이나 넣으면 바로 위
     * 내부_토큰이_없으면_401이다 와 완전히 같은 경로가 되고, 두 체인의 순서를
     * 뒤집어도 초록으로 남아 아무것도 검증하지 않는다.
     */
    @Test
    void 유효한_사용자_JWT가_있어도_내부_API에_못_들어온다() throws Exception {
        String accessToken = tokenService.issue(newUser()).accessToken();

        mockMvc.perform(post("/internal/stream-keys/resolve")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("#!::r=7ZK3M9QW2XJ4NB6TC8VDFG5HRP,m=publish")))
                .andExpect(status().isUnauthorized());
    }

    /** 응답 record는 passphrase를 담는다. toString()으로 새는 경로를 막는다. */
    @Test
    void 거절_응답에는_passphrase_필드가_아예_없다() throws Exception {
        String responseBody = resolve("#!::r=7ZK3M9QW2XJ4NB6TC8VDFG5HRP,m=publish")
                .andReturn().getResponse().getContentAsString();

        assertThat(responseBody).doesNotContain("passphrase");
    }

    private org.springframework.test.web.servlet.ResultActions resolve(String streamid)
            throws Exception {
        return mockMvc.perform(post("/internal/stream-keys/resolve")
                .header("X-Internal-Token", INTERNAL_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(streamid)));
    }

    private String body(String streamid) {
        return "{\"streamid\":\"" + streamid + "\"}";
    }

    private User newUser() {
        return userService.findOrCreate(
                "sub-" + UUID.randomUUID(), "a@example.com", "김태현", null);
    }
}
