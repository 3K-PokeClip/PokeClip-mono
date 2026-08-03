package com.pokeclip.auth.user;

import com.pokeclip.auth.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

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
}
