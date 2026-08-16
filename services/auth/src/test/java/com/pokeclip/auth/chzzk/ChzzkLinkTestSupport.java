package com.pokeclip.auth.chzzk;

import com.pokeclip.auth.streamkey.secret.SecretStore;
import com.pokeclip.auth.support.IntegrationTestSupport;
import com.pokeclip.auth.token.TokenService;
import com.pokeclip.auth.user.User;
import com.pokeclip.auth.user.UserRepository;
import com.pokeclip.auth.user.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 치지직 연동 통합 테스트의 공용 베이스. 헬퍼를 각 클래스에 복사하지 않는다 —
 * chat-collector가 네 벌 복사했다가 하나로 모은 기록이 있다.
 *
 * <p>하위 클래스는 생성자에서 같은 인자를 받아 {@code super(...)}로 넘긴다 — 필드 주입은
 * 커밋 훅이 막는다.
 */
@AutoConfigureMockMvc
public abstract class ChzzkLinkTestSupport extends IntegrationTestSupport {

    /** application-test.yml의 pokeclip.internal-api.token과 같아야 한다. */
    protected static final String INTERNAL_TOKEN = "test-only-internal-token-32bytes-long!!";

    protected final MockMvc mockMvc;
    protected final UserService userService;
    protected final UserRepository userRepository;
    protected final TokenService tokenService;
    protected final ChzzkLinkStateCodec codec;
    protected final ChzzkChannelLinkRepository linkRepository;
    protected final SecretStore secretStore;
    protected final ChzzkLinkWriter writer;
    protected final JdbcTemplate jdbc;
    protected final ChzzkCleanupExecutor cleanup;

    protected ChzzkLinkTestSupport(MockMvc mockMvc, UserService userService, UserRepository userRepository,
                                   TokenService tokenService, ChzzkLinkStateCodec codec,
                                   ChzzkChannelLinkRepository linkRepository, SecretStore secretStore,
                                   ChzzkLinkWriter writer, JdbcTemplate jdbc, ChzzkCleanupExecutor cleanup) {
        this.mockMvc = mockMvc;
        this.userService = userService;
        this.userRepository = userRepository;
        this.tokenService = tokenService;
        this.codec = codec;
        this.linkRepository = linkRepository;
        this.secretStore = secretStore;
        this.writer = writer;
        this.jdbc = jdbc;
        this.cleanup = cleanup;
    }

    @BeforeEach
    void resetAll() {
        CHZZK.reset();
        clear();
    }

    /** 커밋 뒤 정리(secrets 삭제·revoke)는 전용 스레드에서 돈다 — 끝나기 전에 다음 테스트가 reset()하면 기록이 섞인다. */
    @AfterEach
    void clearAll() {
        try {
            awaitCleanup();
        } finally {
            clear();   // 대기 단언이 실패해도 FK 자식 행은 지운다 — 안 그러면 다른 클래스의 정리가 연쇄로 터진다
        }
    }

    /**
     * "결국 지워진다·버려진다"를 재는 폴링. 제출 카운터(submitted)는 응답 전(커밋 동기화)에 오르므로
     * 응답을 받은 뒤 finished == submitted면 이 요청이 만든 정리는 끝난 것이다.
     */
    protected void awaitCleanup() {
        assertThat(cleanup.awaitIdle(Duration.ofSeconds(5))).as("커밋 뒤 정리가 5초 안에 끝나지 않았다").isTrue();
    }

    /** FK: chzzk_channel_links·refresh_tokens는 users의 자식이다. 이 정리를 빼면 다른 테스트의 deleteAll이 터진다. */
    private void clear() {
        jdbc.update("DELETE FROM refresh_tokens");
        jdbc.update("DELETE FROM chzzk_channel_links");
        jdbc.update("DELETE FROM secrets");
        userRepository.deleteAll();
    }

    protected User newUser() {
        return userService.findOrCreate("sub-" + UUID.randomUUID(), "a@example.com", "김태현", null);
    }

    protected String bearer(User u) {
        return "Bearer " + tokenService.issue(u).accessToken();
    }

    /** 치지직을 거치지 않고 "이미 연동된 회원"을 만든다. 토큰은 at-old/rt-old, 채널은 매번 다르다. */
    protected ChzzkChannelLink linked(User u, Duration expiresIn) {
        return writer.create(u.getId(), new ChzzkMe("chan-" + UUID.randomUUID(), "채널"),
                new ChzzkTokens("at-old", "rt-old", expiresIn, null), Instant.now());
    }

    /** 정상 state를 붙인 완료 요청. */
    protected MockHttpServletRequestBuilder link(User u) {
        return post("/api/chzzk-link").header("Authorization", bearer(u)).contentType(APPLICATION_JSON)
                .content("{\"code\":\"c\",\"state\":\"" + codec.issue(u.getId(), Instant.now()) + "\"}");
    }

    protected MockHttpServletRequestBuilder resolve(Long userId) {
        return post("/internal/chzzk-link/resolve").header("X-Internal-Token", INTERNAL_TOKEN)
                .contentType(APPLICATION_JSON).content("{\"userId\":" + userId + "}");
    }
}
