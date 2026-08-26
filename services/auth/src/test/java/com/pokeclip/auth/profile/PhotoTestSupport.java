package com.pokeclip.auth.profile;

import com.pokeclip.auth.support.PhotoLocalStackFixture;
import com.pokeclip.auth.token.TokenService;
import com.pokeclip.auth.user.UserRepository;
import com.pokeclip.auth.user.UserService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 사진이 <b>켜진</b> 컨텍스트. ProfileTestSupport(newUser·bearer·mockMvc)에 가짜 창고 주입만 더한다.
 *
 * <p>컨텍스트가 하나 더 뜬다 — application-test.yml은 창고 이름이 비어 있어 사진이 꺼진 상태이고,
 * 웹 계층을 통과하는 종단 검증에는 켜진 것이 필요하다.
 */
public abstract class PhotoTestSupport extends ProfileTestSupport {

    protected PhotoTestSupport(MockMvc mockMvc, UserRepository userRepository, UserService userService,
                               TokenService tokenService, JdbcTemplate jdbc) {
        super(mockMvc, userRepository, userService, tokenService, jdbc);
    }

    @DynamicPropertySource
    static void photoProperties(DynamicPropertyRegistry registry) {
        PhotoLocalStackFixture.register(registry);
    }
}
