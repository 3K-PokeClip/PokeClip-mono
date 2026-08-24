package com.pokeclip.clip.config;

import com.pokeclip.web.CorsProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 필수 설정이 비어 있으면 부팅이 실패해야 한다. auth의 같은 이름 파일에서 clip에
 * 해당하는 갈래만 옮겼다 — 대조표는 아래 「auth와 나란히 본 결과」 주석에 있다.
 *
 * <p>DB 접속값에 기본값이 남으면 공개 저장소의 .env.example에 적힌 값으로 실제 DB에
 * 붙는 창이 열린다(POK-161). auth의 RequiredPropertiesTest와 같은 판정이다.
 *
 * <p>문자열로 훑지 않고 YAML로 파싱한다 — 문자열이면 주석 안 예시까지 매칭돼
 * 거짓 음성·거짓 양성이 양쪽으로 열린다.
 */
class RequiredPropertiesTest {

    /** application-test.yml·TestTokens와 같은 값. 갈리면 인증 시험이 통째로 죽는다. */
    private static final String VALID_JWT_SECRET = "test-only-jwt-secret-at-least-32-bytes!!";

    private static final String VALID_INTERNAL_TOKEN = "test-only-internal-token-32bytes-long!!";

    /**
     * <b>auth와 나란히 본 결과 (2026-08-18).</b> auth는 갈래 열둘, clip은 넷이다.
     * 나머지 여덟(JWT·구글·시크릿 저장소·내부 API 토큰·치지직)은 clip에 그 설정 자체가 없다.
     *
     * <p><b>2026-08-23(POK-118)에 JWT·내부 API 토큰이 clip에도 생겼다.</b> 그 둘의 갈래를
     * auth에서 옮겨 왔다 — 이제 clip은 일곱이다. 구글·시크릿 저장소·치지직은 여전히 없다.
     *
     * <p>이 파일이 처음 만들어질 때 auth에서 <b>DB 절만</b> 옮겨왔고, 그때 빠진 CORS 갈래가
     * 정확히 나중에 문제가 됐다 — README에 "clip은 환경변수 없이도 뜬다"·
     * "CORS_ALLOWED_ORIGINS는 빈 값이 허용된다"를 적었는데 둘 다 거짓이었고,
     * <b>아무 검사에도 안 걸렸다</b>(PR #82 봇 지적). 그래서 옮겨 온다.
     *
     * <p>{@code pokeclip.broadcast.intake.*}는 {@code IntakePropertiesTest}가 따로 잰다 —
     * auth가 한 파일에 모은 것과 달리 clip은 그쪽이 이미 파일을 갖고 있어 나눠 둔다.
     */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            // JwtConfig까지 넣는 이유: 시크릿 길이 검증이 바인딩이 아니라 빈 등록 시점에 돈다.
            // 프로퍼티 클래스만 등록하면 그 검증이 실행되지 않는다.
            .withUserConfiguration(BoundProperties.class, JwtConfig.class)
            // 새 필수 프로퍼티는 여기서 한 번만 채운다. 개별 테스트에 하나씩 더하는 방식은
            // 하나만 빠뜨려도 그 테스트가 "엉뚱한 이유로 실패하면서 hasFailed()로 초록"이 된다.
            // 비움을 검증하는 테스트는 아래에서 같은 키를 다시 걸어 덮어쓴다.
            .withPropertyValues("pokeclip.cors.allowed-origins=http://localhost:3000",
                    "pokeclip.jwt.secret=" + VALID_JWT_SECRET,
                    "pokeclip.internal-api.token=" + VALID_INTERNAL_TOKEN);

    /**
     * hasFailed()만 보면 안 된다. 나중에 BoundProperties에 클래스를 더 넣다가 필수 값을
     * 안 채우면 이 검사가 <b>CORS와 무관한 이유로</b> 실패하면서 그대로 초록이 되고,
     * 그 순간 이 파일의 보증이 조용히 사라진다. 그래서 실패가 CORS 때문인지까지 본다.
     */
    @Test
    void CORS_허용_출처가_비어_있으면_부팅이_실패한다() {
        runner.withPropertyValues(cors(""))
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
     */
    @Test
    void CORS_허용_출처에_와일드카드가_있으면_부팅이_실패한다() {
        runner.withPropertyValues(cors("*"))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(stackTraceOf(context.getStartupFailure()))
                            .as("와일드카드 차단이 아닌 다른 이유로 부팅이 실패했다")
                            .contains("CORS 허용 출처에 와일드카드를 둘 수 없다");
                });
    }

    /** 양성 대조. 검증이 지나치게 넓으면 멀쩡한 값으로도 안 뜬다. */
    @Test
    void 전부_채워져_있으면_부팅한다() {
        runner.withPropertyValues(cors("http://localhost:3000"))
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void JWT_시크릿이_비어_있으면_부팅이_실패한다() {
        runner.withPropertyValues("pokeclip.jwt.secret=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(stackTraceOf(context.getStartupFailure()))
                            .as("JWT 시크릿이 아닌 다른 이유로 부팅이 실패했다")
                            .contains("pokeclip.jwt.secret");
                });
    }

    /**
     * 32바이트에 한 글자 모자란 시크릿이 가장 위험하다 — 진짜 값일 가능성이 높은데
     * 검증에 걸린다. 이때 실패 메시지에 그 값이 들어가면 로그·CI 출력에 시크릿이
     * 평문으로 남는다. 그래서 길이 검증을 @Size가 아니라 빈 등록 시점에 뒀다.
     */
    @Test
    void JWT_시크릿이_32바이트_미만이면_부팅이_실패하고_값은_메시지에_없다() {
        String shortSecret = "only-31-bytes-long-secret-xxxxx";
        assertThat(shortSecret).hasSize(31);

        runner.withPropertyValues("pokeclip.jwt.secret=" + shortSecret)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(stackTraceOf(context.getStartupFailure()))
                            .as("길이 검증이 아닌 다른 이유로 부팅이 실패했다")
                            .contains("32바이트")
                            .as("실패 메시지에 시크릿이 평문으로 남았다")
                            .doesNotContain(shortSecret);
                });
    }

    @Test
    void 내부_토큰이_비어_있으면_부팅이_실패한다() {
        runner.withPropertyValues("pokeclip.internal-api.token=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(stackTraceOf(context.getStartupFailure()))
                            .as("내부 토큰이 아닌 다른 이유로 부팅이 실패했다")
                            .contains("pokeclip.internal-api.token");
                });
    }

    private String[] cors(String origins) {
        return new String[]{"pokeclip.cors.allowed-origins=" + origins};
    }

    private String stackTraceOf(Throwable failure) {
        StringWriter out = new StringWriter();
        failure.printStackTrace(new PrintWriter(out));
        return out.toString();
    }

    @EnableConfigurationProperties({CorsProperties.class, JwtProperties.class, InternalApiProperties.class})
    static class BoundProperties {
    }

    @Test
    void DB_접속값에_기본값이_남아_있지_않다() {
        Properties properties = applicationYml();

        assertThat(properties.getProperty("spring.datasource.username"))
                .as("POSTGRES_USER에 기본값이 붙었다")
                .isEqualTo("${POSTGRES_USER}");
        assertThat(properties.getProperty("spring.datasource.password"))
                .as("POSTGRES_PASSWORD에 기본값이 붙었다 — 공개된 비밀번호로 DB에 붙는 창이 열린다")
                .isEqualTo("${POSTGRES_PASSWORD}");
        assertThat(properties.getProperty("spring.datasource.url"))
                .as("POSTGRES_DB에 기본값이 붙었다")
                .endsWith("/${POSTGRES_DB}")
                .as("DB_HOST·DB_PORT의 기본값은 일부러 남긴 것이다 — .env에 없어 지우면 로컬이 깨진다")
                .contains("${DB_HOST:localhost}")
                .contains("${DB_PORT:5432}");
    }

    /**
     * 시크릿 둘은 DB 접속값과 반대로 <b>빈 기본값을 남긴다</b>. 기본값을 아예 안 주면
     * 리터럴 {@code "${JWT_SECRET}"}이 바인딩돼 서버는 뜨고 인증만 전부 401이 된다
     * (services/CLAUDE.md — 접속을 시도하지 않는 값이라 DB처럼 우연히 죽지도 않는다).
     * 여기서 재는 것은 "진짜 값이 박혀 있지 않다"와 "빈 기본값이 지워지지 않았다" 둘이다.
     */
    @Test
    void 시크릿_둘에_기본값이_남아_있지_않다() {
        Properties properties = applicationYml();

        assertThat(properties.getProperty("pokeclip.jwt.secret"))
                .as("JWT_SECRET에 값이 박혔거나 빈 기본값이 지워졌다")
                .isEqualTo("${JWT_SECRET:}");
        assertThat(properties.getProperty("pokeclip.internal-api.token"))
                .as("INTERNAL_API_TOKEN에 값이 박혔거나 빈 기본값이 지워졌다")
                .isEqualTo("${INTERNAL_API_TOKEN:}");
    }

    private Properties applicationYml() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties properties = yaml.getObject();

        assertThat(properties).as("application.yml을 읽지 못했다").isNotNull().isNotEmpty();
        return properties;
    }
}
