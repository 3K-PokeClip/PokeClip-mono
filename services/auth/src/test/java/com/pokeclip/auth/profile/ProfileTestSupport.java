package com.pokeclip.auth.profile;

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

import java.util.UUID;

/**
 * 회원정보 수정 통합 테스트의 공용 베이스. ChzzkLinkTestSupport·DelegationTestSupport와 같은 모양이다 —
 * 기능별 추상 베이스에 newUser()·bearer()를 두고 하위가 상속한다.
 *
 * <p>IntegrationTestSupport에는 {@code @AutoConfigureMockMvc}가 없다. 여기서 붙이지 않으면 하위의
 * 생성자 주입이 "No qualifying bean of type MockMvc"로 실패해 이 계층이 한 건도 안 돈다.
 *
 * <p>하위 클래스는 생성자에서 같은 인자를 받아 {@code super(...)}로 넘긴다 — 이 프로젝트는 필드 주입을
 * 쓰지 않는다.
 */
@AutoConfigureMockMvc
public abstract class ProfileTestSupport extends IntegrationTestSupport {

    protected final MockMvc mockMvc;
    protected final UserRepository userRepository;
    /**
     * 재로그인 회귀 시험이 {@code findOrCreate}·{@code updateName}을 창구 없이 직접 부른다.
     * ChzzkLinkTestSupport·DelegationTestSupport도 이 필드를 protected로 둔다.
     */
    protected final UserService userService;
    private final TokenService tokenService;
    private final JdbcTemplate jdbc;

    protected ProfileTestSupport(MockMvc mockMvc, UserRepository userRepository, UserService userService,
                                 TokenService tokenService, JdbcTemplate jdbc) {
        this.mockMvc = mockMvc;
        this.userRepository = userRepository;
        this.userService = userService;
        this.tokenService = tokenService;
        this.jdbc = jdbc;
    }

    /**
     * FK: refresh_tokens는 users의 자식이다. bearer()가 토큰을 발급하므로 이 계층도 자식 행을 남긴다 —
     * 정리를 빼면 <b>다른 테스트 클래스</b>의 userRepository.deleteAll()이 FK로 터진다.
     *
     * <p>앞뒤로 모두 도는 이유는 DelegationTestSupport와 같다. 뒤에만 두면 이 클래스 이전에 돈 테스트가
     * 남긴 행 위에서 시작하고, 앞에만 두면 우리가 남긴 행이 다른 클래스로 넘어간다.
     */
    @BeforeEach
    @AfterEach
    void clear() {
        jdbc.update("DELETE FROM refresh_tokens");
        userRepository.deleteAll();
    }

    /**
     * 이메일에도 유일 제약이 있다(V108의 uq_users_email). <b>주소를 흩지 않으면 다른 테스트와 부딪혀
     * 단독 실행에서만 빨간불이 난다</b> — 나중에 "무관한 커밋이 깨뜨렸다"로 보이는 자리다.
     *
     * <p>구글 사진 주소를 넣어 둔다. 재로그인 회귀 시험이 이름뿐 아니라 이 칸도 안 덮이는 것을 재는데,
     * null로 시작하면 "안 덮였다"와 "원래 비어 있었다"가 구분되지 않는다.
     */
    protected User newUser() {
        String id = UUID.randomUUID().toString();
        return userService.findOrCreate("sub-" + id, id + "@example.com", "김태현",
                "https://lh3.googleusercontent.com/" + id);
    }

    protected String bearer(User u) {
        return "Bearer " + tokenService.issue(u).accessToken();
    }
}
