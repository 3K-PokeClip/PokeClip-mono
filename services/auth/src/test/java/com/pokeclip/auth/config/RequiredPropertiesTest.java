package com.pokeclip.auth.config;

import com.pokeclip.auth.chzzk.ChzzkProperties;
import com.pokeclip.auth.google.GoogleAuthProperties;
import com.pokeclip.auth.profile.PhotoProperties;
import com.pokeclip.auth.streamkey.secret.SecretStoreConfig;
import com.pokeclip.auth.streamkey.secret.SecretStoreProperties;
import com.pokeclip.auth.token.JwtConfig;
import com.pokeclip.auth.token.JwtProperties;
import com.pokeclip.auth.youtube.YoutubeProperties;
import com.pokeclip.web.CorsProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Base64;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

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

    /** 디코딩하면 정확히 32바이트다. application-test.yml과 같은 값. */
    private static final String VALID_SECRET_STORE_KEY =
            "dGVzdC1vbmx5LWFlcy0yNTYta2V5LTMyYnl0ZXMhISE=";

    private static final String VALID_INTERNAL_TOKEN = "test-only-internal-token-32bytes-long!!";

    private static final String[] VALID_CHZZK =
            chzzk("cid", "csecret", "http://localhost:8081/oauth/chzzk/callback");

    private static final String[] VALID_YOUTUBE =
            youtube("ycid", "ycsecret", "http://localhost:8081/oauth/youtube/callback");

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class))
            // JwtConfig까지 넣는 이유: 시크릿 길이 검증이 바인딩이 아니라 빈 등록
            // 시점에 돈다. 프로퍼티 클래스만 등록하면 그 검증이 실행되지 않는다.
            // SecretStoreConfig도 같은 이유다.
            .withUserConfiguration(BoundProperties.class, JwtConfig.class, SecretStoreConfig.class)
            // 새 필수 프로퍼티는 여기서 한 번만 채운다. 개별 테스트에 하나씩 더하는
            // 방식은 하나만 빠뜨려도 그 테스트가 "엉뚱한 이유로 실패하면서
            // hasFailed()로 초록"이 된다 — 아래 CORS 테스트 주석이 경고하는
            // 그 상황이다. 비움을 검증하는 테스트는 아래에서 덮어쓴다.
            .withPropertyValues(secretStore(VALID_SECRET_STORE_KEY))
            .withPropertyValues(internalApi(VALID_INTERNAL_TOKEN))
            .withPropertyValues(VALID_CHZZK)
            .withPropertyValues(VALID_YOUTUBE);

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

    @Test
    void 시크릿_저장소_키가_비어_있으면_부팅이_실패한다() {
        runner.withPropertyValues(jwt("test-only-secret-key-at-least-32-bytes-long!!"))
                .withPropertyValues(google("id", "secret"))
                .withPropertyValues(cors("http://localhost:3000"))
                .withPropertyValues(secretStore(""))
                .run(context -> assertThat(context).hasFailed());
    }

    /** JWT 시크릿과 같은 이유다 — 실패 메시지에 키가 평문으로 남으면 안 된다. */
    @Test
    void 짧은_시크릿_저장소_키는_부팅을_실패시키되_값을_남기지_않는다() {
        String twentyFourBytes = Base64.getEncoder().encodeToString(new byte[24]);

        runner.withPropertyValues(jwt("test-only-secret-key-at-least-32-bytes-long!!"))
                .withPropertyValues(google("id", "secret"))
                .withPropertyValues(cors("http://localhost:3000"))
                .withPropertyValues(secretStore(twentyFourBytes))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(stackTraceOf(context.getStartupFailure()))
                            .as("실패 메시지에 키가 평문으로 남았다")
                            .doesNotContain(twentyFourBytes);
                });
    }

    private String[] secretStore(String key) {
        return new String[]{"pokeclip.secret-store.key=" + key};
    }

    @Test
    void 내부_API_토큰이_비어_있으면_부팅이_실패한다() {
        runner.withPropertyValues(jwt("test-only-secret-key-at-least-32-bytes-long!!"))
                .withPropertyValues(google("id", "secret"))
                .withPropertyValues(cors("http://localhost:3000"))
                .withPropertyValues(secretStore(VALID_SECRET_STORE_KEY))
                .withPropertyValues(internalApi(""))
                .run(context -> assertThat(context).hasFailed());
    }

    private String[] internalApi(String token) {
        return new String[]{"pokeclip.internal-api.token=" + token};
    }

    /**
     * 치지직 앱 설정 셋(ID·시크릿·redirect)은 한 앱의 것이라 하나만 빠져도 나머지가
     * 무의미하다. 원인이 세 갈래로 흩어지지 않게 한 메시지로 모은다 — 그리고 그
     * 메시지에 시크릿 값이 남으면 안 된다(JWT 시크릿과 같은 이유).
     */
    @Test
    void 치지직_앱_설정_중_하나라도_비면_부팅이_실패하고_원인이_하나로_모인다() {
        String secretNeedle = "LEAK-chzzk-secret-" + UUID.randomUUID();
        for (String[] broken : List.of(
                chzzk("", secretNeedle, "http://x"),
                chzzk("i", "", "http://x"),
                chzzk("i", secretNeedle, ""))) {
            runner.withPropertyValues(jwt("test-only-secret-key-at-least-32-bytes-long!!"))
                    .withPropertyValues(google("id", "secret"))
                    .withPropertyValues(cors("http://localhost:3000"))
                    .withPropertyValues(broken)
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(stackTraceOf(context.getStartupFailure()))
                                .as("치지직 앱 설정이 아닌 다른 이유로 부팅이 실패했다")
                                .contains("CHZZK_CLIENT_ID·CHZZK_CLIENT_SECRET·CHZZK_REDIRECT_URI")
                                .as("실패 메시지에 치지직 시크릿이 평문으로 남았다")
                                .doesNotContain(secretNeedle);
                    });
        }
    }

    /**
     * 위의 다른 테스트들이 컨텍스트를 띄워 <b>부팅 거동</b>을 재는 것과 달리, 이것은
     * <b>설정 파일에 적힌 값</b>을 잰다. DB 접속값은
     * {@code spring.datasource.*}라 우리 @ConfigurationProperties 클래스를 거치지 않아
     * @NotBlank를 걸 자리가 없다 — 그래서 yml에서 기본값 자체를 없앤다.
     *
     * <p>커밋된 yml에 비밀번호 기본값이 있으면, compose를 거치지 않는 실행(IDE 단독
     * 기동·AWS 배포)에서 <b>저장소에 공개된 비밀번호로 DB에 붙는 창</b>이 열린다.
     * public 저장소라 그 값은 누구나 본다.
     *
     * <p>보증하는 것은 "yml에 이 값이 적혀 있다"까지다. 부팅이 실제로 거부되는지는
     * 재지 못한다 — 그쪽은 실측으로 확인하고 기록을 남겼다(POK-161).
     *
     * <p><b>문자열이 아니라 YAML로 읽는 이유:</b> 파일을 통째로 문자열로 훑으면
     * 주석 안의 문자열도 매칭된다. 그러면 실제 설정 줄을 지우고 설명 주석만 남겨도
     * 초록이 되고, 반대로 주석에 예시를 하나 적으면 멀쩡한 설정이 빨간불이 된다.
     * 값으로 비교하면 둘 다 닫힌다.
     *
     * <p>{@code DB_HOST}·{@code DB_PORT}는 기본값을 <b>일부러 남겼다</b> —
     * `.env`에 없는 값이라 지우면 로컬 기동이 즉시 깨진다. 그 의도까지 함께 못박아,
     * 누가 "일관성"을 이유로 넷 다 지우면 빨간불이 되게 한다.
     */
    @Test
    void DB_접속값에_기본값이_남아_있지_않다() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties properties = yaml.getObject();

        // 파일을 못 읽으면 아래 단언들이 전부 null 비교가 되어 의미를 잃는다.
        assertThat(properties).as("application.yml을 읽지 못했다").isNotNull().isNotEmpty();

        assertThat(properties.getProperty("spring.datasource.username"))
                .as("POSTGRES_USER에 기본값이 붙었다 — 환경변수를 깜빡해도 조용히 굴러간다")
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
     * 위 두 사진 테스트는 <b>값이 주어졌을 때</b>의 거동을 잰다. 이것은 yml에 그 자리가 실제로
     * <b>선언돼 있는지</b>를 잰다 — 사진 설정 블록을 통째로 지워도 아무것도 안 깨지던 자리다
     * (주입으로 확인: 블록 삭제 → 전 시험 초록).
     *
     * <p>DeploymentEnvVarsTest가 필수 변수 목록을 <b>이 yml에서 뽑기 때문에</b> 그렇다 —
     * 선언을 지우면 요구도 같이 사라져 스스로를 못 지킨다. 그래서 선언 자체를 여기서 못박는다.
     * {@code ${VAR:}}(빈 기본값)가 「부팅 검증으로 잡는 필수 값」이라는 표시이고,
     * 그 모양이 아니면 그 검사가 compose·.env.dev.example을 강제하지 않는다.
     *
     * <p>{@code force-path-style}만 기본값이 있다 — 켜고 끄는 스위치라 값이 필요 없고,
     * 그래서 <b>DeploymentEnvVarsTest가 이 하나만 못 잡는다</b>(손으로 챙긴 자리다).
     * 그 의도까지 함께 못박아, 누가 "일관성"을 이유로 넷과 같은 모양으로 바꾸면 빨간불이 되게 한다.
     */
    @Test
    void 사진_설정이_yml에_빈_기본값으로_선언돼_있다() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties properties = yaml.getObject();

        assertThat(properties).as("application.yml을 읽지 못했다").isNotNull().isNotEmpty();

        assertThat(properties.getProperty("pokeclip.profile-photo.bucket"))
                .as("창고 이름 선언이 없다 — 사진 기능의 켜짐/꺼짐 스위치가 사라진다")
                .isEqualTo("${PROFILE_PHOTO_S3_BUCKET:}");
        assertThat(properties.getProperty("pokeclip.profile-photo.endpoint"))
                .isEqualTo("${PROFILE_PHOTO_S3_ENDPOINT:}");
        assertThat(properties.getProperty("pokeclip.profile-photo.token-secret"))
                .as("표 서명키 선언이 없다 — 배포 파일 검사가 이 변수를 강제하지 않게 된다")
                .isEqualTo("${PROFILE_PHOTO_TOKEN_SECRET:}");
        assertThat(properties.getProperty("pokeclip.profile-photo.base-url"))
                .isEqualTo("${PROFILE_PHOTO_BASE_URL:}");
        assertThat(properties.getProperty("pokeclip.profile-photo.force-path-style"))
                .as("스위치라 기본값을 일부러 뒀다 — 넷과 같은 모양으로 바꾸면 값 없이 뜨는 길이 막힌다")
                .isEqualTo("${PROFILE_PHOTO_S3_FORCE_PATH_STYLE:false}");
    }

    /**
     * 유튜브 앱 설정 셋도 치지직과 같은 이유로 한 덩어리다 — 하나만 빠지면 나머지가 무의미하고,
     * 실패 메시지에 시크릿이 남으면 안 된다. 치지직 앱과 <b>다른 GCP 앱</b>이라 값이 별개다.
     */
    @Test
    void 유튜브_앱_설정_중_하나라도_비면_부팅이_실패하고_원인이_하나로_모인다() {
        String secretNeedle = "LEAK-youtube-secret-" + UUID.randomUUID();
        for (String[] broken : List.of(
                youtube("", secretNeedle, "http://x"),
                youtube("i", "", "http://x"),
                youtube("i", secretNeedle, ""))) {
            runner.withPropertyValues(jwt("test-only-secret-key-at-least-32-bytes-long!!"))
                    .withPropertyValues(google("id", "secret"))
                    .withPropertyValues(cors("http://localhost:3000"))
                    .withPropertyValues(broken)
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(stackTraceOf(context.getStartupFailure()))
                                .as("유튜브 앱 설정이 아닌 다른 이유로 부팅이 실패했다")
                                .contains("YOUTUBE_CLIENT_ID·YOUTUBE_CLIENT_SECRET·YOUTUBE_REDIRECT_URI")
                                .as("실패 메시지에 유튜브 시크릿이 평문으로 남았다")
                                .doesNotContain(secretNeedle);
                    });
        }
    }

    /**
     * <b>레코드 단위 시험(PhotoPropertiesTest)은 생성자만 잰다</b> — 그것이 부팅 실패로 이어지는지는
     * 컨텍스트를 띄워 봐야 안다. 창고 이름만 채우고 나머지를 비우면 사진을 올릴 수는 있는데 볼 수가
     * 없는 상태가 되고, 그대로 뜨면 사진을 올리는 순간에야 원인 모를 오류가 난다.
     *
     * <p>PhotoToken이 이 검증에 기대고 있다 — 빈 서명키는 {@code SecretKeySpec}에서
     * {@code IllegalArgumentException}이 되는데 그것은 {@code sign}의 catch를 빠져나간다.
     */
    @Test
    void 창고_이름만_있고_나머지가_비면_부팅이_실패하고_서명키가_남지_않는다() {
        String secretNeedle = "LEAK-photo-secret-" + UUID.randomUUID();
        for (String[] broken : List.of(
                photo("bucket", "", "http://localhost:8082"),
                photo("bucket", secretNeedle, ""))) {
            runner.withPropertyValues(jwt("test-only-secret-key-at-least-32-bytes-long!!"))
                    .withPropertyValues(google("id", "secret"))
                    .withPropertyValues(cors("http://localhost:3000"))
                    .withPropertyValues(broken)
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(stackTraceOf(context.getStartupFailure()))
                                .as("사진 설정이 아닌 다른 이유로 부팅이 실패했다")
                                .contains("PROFILE_PHOTO_")
                                .as("실패 메시지에 사진 표 서명키가 평문으로 남았다")
                                .doesNotContain(secretNeedle);
                    });
        }
    }

    /** 창고 이름이 비면 나머지가 없어도 정상이다 — 사진 기능만 꺼진 채 서버가 뜬다(CI·팀원 로컬 기본). */
    @Test
    void 창고_이름이_비면_나머지가_없어도_부팅한다() {
        runner.withPropertyValues(jwt("test-only-secret-key-at-least-32-bytes-long!!"))
                .withPropertyValues(google("id", "secret"))
                .withPropertyValues(cors("http://localhost:3000"))
                .withPropertyValues(photo("", "", ""))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(PhotoProperties.class).enabled()).isFalse();
                });
    }

    private static String[] photo(String bucket, String tokenSecret, String baseUrl) {
        return new String[]{
                "pokeclip.profile-photo.bucket=" + bucket,
                "pokeclip.profile-photo.region=ap-northeast-2",
                "pokeclip.profile-photo.endpoint=",
                "pokeclip.profile-photo.force-path-style=false",
                "pokeclip.profile-photo.token-secret=" + tokenSecret,
                "pokeclip.profile-photo.base-url=" + baseUrl};
    }

    private static String[] chzzk(String clientId, String clientSecret, String redirectUri) {
        return new String[]{
                "pokeclip.chzzk.app.client-id=" + clientId,
                "pokeclip.chzzk.app.client-secret=" + clientSecret,
                "pokeclip.chzzk.app.redirect-uri=" + redirectUri,
                "pokeclip.chzzk.authorize-uri=https://chzzk.naver.com/account-interlock",
                "pokeclip.chzzk.api-base-uri=https://openapi.chzzk.naver.com",
                "pokeclip.chzzk.state-ttl=PT10M",
                "pokeclip.chzzk.refresh-ahead=PT6H",
                "pokeclip.chzzk.resolve-min-remaining=PT12H",
                "pokeclip.chzzk.refresh.enabled=true",
                "pokeclip.chzzk.refresh.interval=PT10M"};
    }

    /**
     * {@code @NotBlank}·{@code @NotNull}이 걸린 값을 <b>전부</b> 채운다. 하나라도 빠지면 위 테스트가
     * 유튜브 앱 검증이 아닌 이유로 실패하면서 {@code hasFailed()}로 초록이 된다.
     */
    private static String[] youtube(String clientId, String clientSecret, String redirectUri) {
        return new String[]{
                "pokeclip.youtube.app.client-id=" + clientId,
                "pokeclip.youtube.app.client-secret=" + clientSecret,
                "pokeclip.youtube.app.redirect-uri=" + redirectUri,
                "pokeclip.youtube.authorize-uri=https://accounts.google.com/o/oauth2/v2/auth",
                "pokeclip.youtube.token-uri=https://oauth2.googleapis.com/token",
                "pokeclip.youtube.revoke-uri=https://oauth2.googleapis.com/revoke",
                "pokeclip.youtube.api-base-uri=https://www.googleapis.com",
                "pokeclip.youtube.state-ttl=PT10M",
                "pokeclip.youtube.resolve-min-remaining=PT30M",
                "pokeclip.youtube.check.enabled=true",
                "pokeclip.youtube.check.interval=PT1H",
                "pokeclip.youtube.check.staleness=PT24H"};
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

    @EnableConfigurationProperties({JwtProperties.class, GoogleAuthProperties.class, CorsProperties.class,
            SecretStoreProperties.class, InternalApiProperties.class, ChzzkProperties.class,
            YoutubeProperties.class, PhotoProperties.class})
    static class BoundProperties {
    }
}
