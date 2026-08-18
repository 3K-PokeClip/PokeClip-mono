package com.pokeclip.auth.delegation;

import com.pokeclip.auth.support.IntegrationTestSupport;
import com.pokeclip.auth.token.TokenService;
import com.pokeclip.auth.user.User;
import com.pokeclip.auth.user.UserRepository;
import com.pokeclip.auth.user.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.concurrent.atomic.AtomicInteger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * IntegrationTestSupport에는 @AutoConfigureMockMvc가 없다 — MockMvc가 필요한 하위 지원
 * 클래스가 각자 붙인다(ChzzkLinkTestSupport와 같다). 빠뜨리면 생성자 주입이
 * "No qualifying bean of type MockMvc"로 실패해 이 계층의 테스트가 한 건도 안 돈다.
 *
 * <p>임포트는 Boot 4 경로다 — org.springframework.boot.webmvc.test.autoconfigure.
 */
@AutoConfigureMockMvc
public abstract class DelegationTestSupport extends IntegrationTestSupport {

    private static final AtomicInteger SEQ = new AtomicInteger();

    protected final MockMvc mockMvc;
    protected final UserService userService;
    protected final UserRepository userRepository;
    protected final TokenService tokenService;
    protected final EditorInvitationRepository invitations;
    protected final EditorDelegationRepository delegations;
    protected final JdbcTemplate jdbc;

    protected DelegationTestSupport(MockMvc mockMvc, UserService userService, UserRepository userRepository,
                                    TokenService tokenService, EditorInvitationRepository invitations,
                                    EditorDelegationRepository delegations, JdbcTemplate jdbc) {
        this.mockMvc = mockMvc;
        this.userService = userService;
        this.userRepository = userRepository;
        this.tokenService = tokenService;
        this.invitations = invitations;
        this.delegations = delegations;
        this.jdbc = jdbc;
    }

    /**
     * FK: editor_invitations·editor_delegations는 users의 자식이고, 위임은 초대의 자식이기도
     * 하다. 이 정리를 빼면 <b>다른 테스트 클래스</b>의 userRepository.deleteAll()이 FK로 터진다
     * (plan-critic 실측: StreamKeyControllerTest 4건). 지우는 순서는 위임 → 초대 → 토큰 → 계정이다.
     */
    @BeforeEach
    @AfterEach
    void clear() {
        jdbc.update("DELETE FROM editor_delegations");
        jdbc.update("DELETE FROM editor_invitations");
        jdbc.update("DELETE FROM refresh_tokens");
        userRepository.deleteAll();
    }

    /** 테스트마다 새 계정. 이메일 유일 제약이 있어 매번 달라야 한다. */
    protected User newUser() {
        int n = SEQ.incrementAndGet();
        return userService.findOrCreate("sub-" + n, "user" + n + "@example.com", "사용자" + n, null);
    }

    /** TokenService.issue는 User를 받는다(Long이 아니다). ChzzkLinkTestSupport와 같은 사용례다. */
    protected String accessTokenOf(User user) {
        return tokenService.issue(user).accessToken();
    }

    protected MockHttpServletRequestBuilder invite(User streamer, String email) {
        return post("/api/editor-invitations")
                .header("Authorization", "Bearer " + accessTokenOf(streamer))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\"}");
    }
}
