package com.pokeclip.auth.streamkey.secret;

import com.pokeclip.auth.support.SecretsLocalStackFixture;
import com.pokeclip.auth.token.TokenService;
import com.pokeclip.auth.user.UserService;
import com.pokeclip.auth.withdrawal.WithdrawalTestSupport;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 스트림키 저장소를 aws(LocalStack)로 켠 컨텍스트 하나를 여러 시험 클래스가 함께 쓴다(POK-272). 등록을 이 한 곳에
 * 두어야 컨텍스트 캐시 키가 같아진다. 클래스마다 따로 등록하면 같은 값이어도 컨텍스트가 하나씩 더 뜬다.
 *
 * <p>탈퇴 시험 지원을 물려받는다. 회원을 심고 번호로 거두는 정리가 거기 있다.
 */
abstract class AwsSecretStoreTestSupport extends WithdrawalTestSupport {

    protected AwsSecretStoreTestSupport(MockMvc mockMvc, UserService userService, TokenService tokenService,
                                        JdbcTemplate jdbc) {
        super(mockMvc, userService, tokenService, jdbc);
    }

    @DynamicPropertySource
    static void awsStore(DynamicPropertyRegistry registry) {
        SecretsLocalStackFixture.registerAws(registry);
    }
}
