package com.pokeclip.auth.user;

import com.pokeclip.auth.AuthException;
import com.pokeclip.auth.support.IntegrationTestSupport;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class UserServiceTest extends IntegrationTestSupport {

    private final UserService userService;
    private final UserRepository userRepository;

    UserServiceTest(UserService userService, UserRepository userRepository) {
        this.userService = userService;
        this.userRepository = userRepository;
    }

    @BeforeEach
    void clean() {
        userRepository.deleteAll();
    }

    @Test
    void 처음_보는_구글_사용자면_계정을_만든다() {
        User user = userService.findOrCreate("google-sub-1", "a@example.com", "김태현", null);

        assertThat(user.getId()).isNotNull();
        assertThat(user.getGoogleSub()).isEqualTo("google-sub-1");
        assertThat(user.getEmail()).isEqualTo("a@example.com");
        assertThat(userRepository.count()).isEqualTo(1);
    }

    @Test
    void 같은_사람이_두_번째_로그인하면_계정을_새로_만들지_않는다() {
        User first = userService.findOrCreate("google-sub-1", "a@example.com", "김태현", null);
        User second = userService.findOrCreate("google-sub-1", "a@example.com", "김태현", null);

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(userRepository.count()).isEqualTo(1);
    }

    @Test
    void 같은_구글_사용자가_동시에_로그인해도_계정은_하나만_생긴다() throws Exception {
        Callable<User> login =
                () -> userService.findOrCreate("google-sub-1", "a@example.com", "김태현", null);

        try (var pool = Executors.newFixedThreadPool(2)) {
            List<Future<User>> results = pool.invokeAll(List.of(login, login));
            for (Future<User> r : results) {
                assertThat(r.get().getId()).isNotNull();
            }
        }

        assertThat(userRepository.count()).isEqualTo(1);
    }

    /**
     * 구글은 소문자로 주지만 그 약속에 기대지 않는다. 저장이 통일돼 있지 않으면
     * users.email의 유일 제약이 대소문자만 다른 두 계정을 막지 못한다.
     */
    @Test
    void 대문자가_섞인_이메일은_소문자로_저장된다() {
        User user = userService.findOrCreate("sub-upper", "Foo@Example.COM", "이름", null);

        assertThat(user.getEmail()).isEqualTo("foo@example.com");
    }

    @Test
    void 소문자로_조회하면_대문자로_가입한_계정을_찾는다() {
        userService.findOrCreate("sub-find", "Bar@Example.COM", "이름", null);

        assertThat(userRepository.findAliveByEmail("bar@example.com")).isPresent();
    }

    /**
     * V108이 users.email에 유일 제약을 걸면서 열린 경로다 — 구글 계정을 지웠다 같은 주소로
     * 다시 만들면 sub가 바뀌어 여기 온다. 그전에는 그냥 두 번째 행이 생기고 끝났다.
     *
     * <p><b>재조회가 google_sub으로만 회수하므로 이 조합은 빈손이고, 예외가 그대로 밖으로
     * 나간다. 그 메시지에 이메일이 평문으로 들어 있다</b>(authz-auditor 라운드 1 중대 1).
     * 던지기 전에 Hibernate가 org.hibernate.orm.jdbc.error로 이미 WARN을 찍는데,
     * 이건 자바에서 잡아도 안 사라진다 — application.yml에서 그 로거를 낮춰야 한다.
     * 그래서 <b>예외와 로그 둘 다</b> 단언한다.
     *
     * <p>예외 타입·사유 이름은 <b>잠정</b>이다. 클라이언트에 어떤 코드로 답할지는 사용자
     * 결정을 기다리는 중이고, 지금은 401 일반 응답이라 새 정보가 나가지 않는다.
     */
    @Test
    void 같은_이메일_다른_sub로_가입해도_이메일이_예외에도_로그에도_안_실린다() {
        String email = "dup-check@example.com";
        userService.findOrCreate("sub-first", email, "먼저", null);

        Throwable thrown;
        try (LogCaptor logs = new LogCaptor()) {
            thrown = catchThrowable(() -> userService.findOrCreate("sub-second", email, "나중", null));

            assertThat(logs.messages()).noneMatch(m -> m.contains(email));
        }

        assertThat(thrown).isInstanceOf(AuthException.class);
        assertThat(String.valueOf(thrown)).doesNotContain(email);
        // 원인을 달면 체인에 이메일이 남는다. 스택트레이스를 찍는 곳이 하나만 생겨도 샌다.
        assertThat(thrown.getCause()).isNull();
        assertThat(userRepository.count()).isEqualTo(1);
    }
}
