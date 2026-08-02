package com.pokeclip.core.auth.config;

import com.pokeclip.core.auth.google.GoogleAuthProperties;
import com.pokeclip.core.auth.token.JwtConfig;
import com.pokeclip.core.auth.token.JwtProperties;
import com.pokeclip.core.web.CorsProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 필수 설정이 비어 있으면 부팅이 실패해야 한다.
 *
 * <p>이 테스트가 없어서 실제 결함이 났다. `${JWT_SECRET}`처럼 기본값 없이 쓰면
 * 환경변수가 없을 때 부팅이 죽는 것이 아니라 **리터럴 문자열 "${JWT_SECRET}"이
 * 값으로 바인딩된다.** 서버는 멀쩡히 뜨고, 로그인만 전부 401이 되고, 원인을
 * 가리키는 신호가 아무 데도 없다. 배포와 헬스체크는 통과한다.
 *
 * <p>그래서 yml은 `${VAR:}`로 빈 기본값을 주고, 빈 값을 여기 검증으로 막는다.
 */
class RequiredPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class))
            // JwtConfig까지 넣는 이유: 시크릿 길이 검증이 바인딩이 아니라 빈 등록
            // 시점에 돈다. 프로퍼티 클래스만 등록하면 그 검증이 실행되지 않는다.
            .withUserConfiguration(BoundProperties.class, JwtConfig.class);

    @Test
    void JWT_시크릿이_비어_있으면_부팅이_실패한다() {
        runner.withPropertyValues(jwt(""))
                .withPropertyValues(google("id", "secret"))
                .withPropertyValues(cors("http://localhost:3000"))
                .run(context -> assertThat(context).hasFailed());
    }

    /**
     * 32자에 한 글자 모자란 시크릿이 가장 위험하다 — 진짜 값일 가능성이 높은데
     * 검증에 걸린다. 이때 실패 메시지에 그 값이 들어가면 로그·CI 출력에 시크릿이
     * 평문으로 남는다. 그래서 길이 검증을 빈 등록 시점으로 옮겼다.
     */
    @Test
    void 짧은_시크릿은_부팅을_실패시키되_값을_남기지_않는다() {
        String almostLongEnough = "abcdefghijklmnopqrstuvwxyz01234";
        assertThat(almostLongEnough).hasSize(31);

        runner.withPropertyValues(jwt(almostLongEnough))
                .withPropertyValues(google("id", "secret"))
                .withPropertyValues(cors("http://localhost:3000"))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(stackTraceOf(context.getStartupFailure()))
                            .as("실패 메시지에 시크릿이 평문으로 남았다")
                            .doesNotContain(almostLongEnough);
                });
    }

    private String stackTraceOf(Throwable failure) {
        StringWriter out = new StringWriter();
        failure.printStackTrace(new PrintWriter(out));
        return out.toString();
    }

    @Test
    void 구글_클라이언트_id가_비어_있으면_부팅이_실패한다() {
        runner.withPropertyValues(jwt("test-only-secret-key-at-least-32-bytes-long!!"))
                .withPropertyValues(google("", "secret"))
                .withPropertyValues(cors("http://localhost:3000"))
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void 구글_클라이언트_시크릿이_비어_있으면_부팅이_실패한다() {
        runner.withPropertyValues(jwt("test-only-secret-key-at-least-32-bytes-long!!"))
                .withPropertyValues(google("id", ""))
                .withPropertyValues(cors("http://localhost:3000"))
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void 전부_채워져_있으면_부팅한다() {
        runner.withPropertyValues(jwt("test-only-secret-key-at-least-32-bytes-long!!"))
                .withPropertyValues(google("id", "secret"))
                .withPropertyValues(cors("http://localhost:3000"))
                .run(context -> assertThat(context).hasNotFailed());
    }

    /**
     * hasFailed()만 보면 안 된다. 나중에 BoundProperties에 클래스를 더 넣다가
     * 필수 값을 안 채우면 이 테스트가 <b>CORS와 무관한 이유로</b> 실패하면서
     * 그대로 초록이 되고, 그 순간 이 파일의 보증이 조용히 사라진다.
     * 그래서 실패가 CORS 때문인지까지 본다.
     */
    @Test
    void CORS_허용_출처가_비어_있으면_부팅이_실패한다() {
        runner.withPropertyValues(jwt("test-only-secret-key-at-least-32-bytes-long!!"))
                .withPropertyValues(google("id", "secret"))
                .withPropertyValues(cors(""))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(stackTraceOf(context.getStartupFailure()))
                            .as("CORS가 아닌 다른 이유로 부팅이 실패했다")
                            .contains("allowedOrigins");
                });
    }

    /**
     * allowCredentials=false라 CORS 명세상으로는 "*"가 허용된다. 아무도 안 막아준다는 뜻이다.
     * 우리 API는 쿠키가 아니라 Authorization 헤더로 토큰을 받으므로, "*"가 들어가면
     * 아무 사이트나 우리 API를 부를 수 있게 된다. 부팅에서 막는다.
     *
     * <p>막고 싶은 것이 컴팩트 생성자의 그 예외 하나이므로, 아무거나 터지면
     * 통과하는 hasFailed()로는 부족하다. 메시지까지 못박는다.
     * 거부된 출처가 스택트레이스에 평문으로 나오는 것은 괜찮다 — 허용 출처는 비밀이 아니다.
     */
    @Test
    void CORS_허용_출처에_와일드카드가_있으면_부팅이_실패한다() {
        runner.withPropertyValues(jwt("test-only-secret-key-at-least-32-bytes-long!!"))
                .withPropertyValues(google("id", "secret"))
                .withPropertyValues(cors("*"))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(stackTraceOf(context.getStartupFailure()))
                            .as("와일드카드 차단이 아닌 다른 이유로 부팅이 실패했다")
                            .contains("CORS 허용 출처에 와일드카드를 둘 수 없다");
                });
    }

    private String[] jwt(String secret) {
        return new String[]{
                "pokeclip.jwt.secret=" + secret,
                "pokeclip.jwt.access-token-ttl=PT30M",
                "pokeclip.jwt.refresh-token-ttl=P14D"};
    }

    private String[] google(String clientId, String clientSecret) {
        return new String[]{
                "pokeclip.google.client-id=" + clientId,
                "pokeclip.google.client-secret=" + clientSecret,
                "pokeclip.google.redirect-uri=http://localhost:3000/auth/callback",
                "pokeclip.google.token-uri=https://oauth2.googleapis.com/token",
                "pokeclip.google.jwk-set-uri=https://www.googleapis.com/oauth2/v3/certs"};
    }

    private String[] cors(String origins) {
        return new String[]{"pokeclip.cors.allowed-origins=" + origins};
    }

    @EnableConfigurationProperties({JwtProperties.class, GoogleAuthProperties.class, CorsProperties.class})
    static class BoundProperties {
    }
}
