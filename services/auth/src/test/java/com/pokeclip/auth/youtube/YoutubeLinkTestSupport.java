package com.pokeclip.auth.youtube;

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

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 유튜브 연동 통합 테스트의 공용 베이스. {@code ChzzkLinkTestSupport} 미러다.
 *
 * <p><b>태스크 6부터 있어야 한다</b> — 유튜브 연동 행을 남기는 순간 {@code userRepository.deleteAll()}을
 * 부르는 다른 13개 클래스가 FK로 터진다. 단독 실행에서는 통과하고 <b>전체 실행에서만</b> 보이는 함정이라
 * 정리 주체를 처음부터 갖고 시작한다(`services/CLAUDE.md`).
 *
 * <p>하위 클래스는 생성자에서 같은 인자를 받아 {@code super(...)}로 넘긴다 — 필드 주입은 커밋 훅이 막는다.
 */
@AutoConfigureMockMvc
public abstract class YoutubeLinkTestSupport extends IntegrationTestSupport {

    /** application-test.yml의 pokeclip.internal-api.token과 같아야 한다. */
    protected static final String INTERNAL_TOKEN = "test-only-internal-token-32bytes-long!!";

    protected final MockMvc mockMvc;
    protected final UserService userService;
    protected final UserRepository userRepository;
    protected final TokenService tokenService;
    protected final YoutubeLinkStateCodec codec;
    protected final YoutubeChannelLinkRepository linkRepository;
    protected final SecretStore secretStore;
    protected final YoutubeLinkWriter writer;
    protected final JdbcTemplate jdbc;
    protected final YoutubeCleanupExecutor cleanup;

    protected YoutubeLinkTestSupport(MockMvc mockMvc, UserService userService, UserRepository userRepository,
                                     TokenService tokenService, YoutubeLinkStateCodec codec,
                                     YoutubeChannelLinkRepository linkRepository, SecretStore secretStore,
                                     YoutubeLinkWriter writer, JdbcTemplate jdbc, YoutubeCleanupExecutor cleanup) {
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
        YOUTUBE.reset();
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

    /** FK: youtube_channel_links·refresh_tokens는 users의 자식이다. 이 정리를 빼면 다른 테스트의 deleteAll이 터진다. */
    private void clear() {
        jdbc.update("DELETE FROM refresh_tokens");
        jdbc.update("DELETE FROM youtube_channel_links");
        jdbc.update("DELETE FROM secrets");
        userRepository.deleteAll();
    }

    /** 이메일에도 유일 제약이 있다(V108). 한 테스트가 계정을 여럿 만드므로 주소도 흩는다. */
    protected User newUser() {
        String id = UUID.randomUUID().toString();
        return userService.findOrCreate("sub-" + id, id + "@example.com", "김태현", null);
    }

    protected String bearer(User u) {
        return "Bearer " + tokenService.issue(u).accessToken();
    }

    /** 구글을 거치지 않고 "이미 연동된 회원"을 만든다. 토큰은 at-old/rt-old, 채널은 매번 다르다. */
    protected YoutubeChannelLink linked(User u, String accessToken, String refreshToken) {
        return writer.create(u.getId(), new YoutubeChannel("UC-" + UUID.randomUUID(), "채널"),
                new YoutubeTokens(accessToken, refreshToken, Duration.ofHours(1), null));
    }

    protected YoutubeChannelLink linked(User u) {
        return linked(u, "at-old", "rt-old");
    }

    /**
     * access가 「이만큼 남은」 상태로 만든다. 구글 access는 1시간짜리라 즉석 갱신·임박 갈래를 재려면
     * 만료를 앞당겨야 한다 — 요구 수명을 새 토큰 수명보다 크게 잡는 방식은 갱신 뒤에도 임박이라
     * 아무것도 안 잰다. 음수 Duration이면 이미 만료된 행이다.
     */
    protected void accessRemaining(YoutubeChannelLink link, Duration remaining) {
        jdbc.update("UPDATE youtube_channel_links SET access_expires_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().plus(remaining)), link.getId());
    }

    protected int secretCount() {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM secrets", Integer.class);
        return count == null ? -1 : count;
    }

    /** 정상 state를 붙인 완료 요청. */
    protected MockHttpServletRequestBuilder link(User u) {
        return post("/api/youtube-link").header("Authorization", bearer(u)).contentType(APPLICATION_JSON)
                .content("{\"code\":\"c\",\"state\":\"" + codec.issue(u.getId(), Instant.now()) + "\"}");
    }

    protected MockHttpServletRequestBuilder resolve(Long userId) {
        return post("/internal/youtube-link/resolve").header("X-Internal-Token", INTERNAL_TOKEN)
                .contentType(APPLICATION_JSON).content("{\"userId\":" + userId + "}");
    }
}
