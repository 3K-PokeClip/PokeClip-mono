package com.pokeclip.auth.profile;

import com.pokeclip.auth.token.JwtProperties;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 🔴 <b>두 서명키가 같으면 부팅을 세운다.</b> 「사진 표로는 그림 한 장 말고 아무것도 못 한다」를
 * 오늘 실제로 지키는 것은 <b>키가 아니라 문법</b>이다 — 로그인 토큰은 {@code b64.b64.b64} 세 칸이고
 * 사진 표는 {@code 숫자.숫자.숫자.서명} 네 칸이라 서로의 파서가 상대를 못 읽는다(감사자 실측:
 * 키를 같게 해도 창구가 하나도 안 뚫린다).
 *
 * <p><b>그래서 위험하다.</b> 표 형식을 JWT로 바꾸는 순간 — 가장 흔한 리팩터링이다 — 그 방어가
 * 통째로 사라지고 <b>사진 표가 곧 로그인 토큰</b>이 된다. 키가 같게 배포돼 있으면 그날 뚫린다.
 * 형식이 지키는 것을 키로도 지키게 하는 자리가 여기다.
 *
 * <p>{@code PhotoProperties}가 아니라 조립부에 두는 이유: {@code PhotoProperties}는
 * {@code JwtProperties}를 못 본다. 대가로 {@code profile} → {@code token} 의존이 하나 생기는데
 * <b>의도한 방향이다</b> — 잘못 배포하면 부팅이 죽는 쪽이 사진 표가 로그인 토큰이 되는 쪽보다 낫다.
 *
 * <p>DB도 컨테이너도 안 띄운다({@code ApplicationContextRunner}) — 이 검사 하나 때문에
 * 통합 컨텍스트를 하나 더 만들면 커넥션이 마른다(태스크 5에서 13건이 그렇게 죽었다).
 */
class PhotoConfigurationTest {

    /** 이 글자가 실패 메시지에 나타나면 시크릿이 로그·CI 출력으로 나간 것이다. */
    private static final String SHARED = "LEAK-shared-signing-secret-32bytes!!";

    private static PhotoProperties photo(String bucket, String tokenSecret) {
        return new PhotoProperties(bucket, "ap-northeast-2", "http://localhost:14566", true,
                tokenSecret, "http://localhost:8082");
    }

    private static JwtProperties jwt(String secret) {
        return new JwtProperties(secret, Duration.ofMinutes(30), Duration.ofDays(14));
    }

    private static ApplicationContextRunner runnerWith(PhotoProperties photo, JwtProperties jwt) {
        return new ApplicationContextRunner()
                .withUserConfiguration(PhotoConfiguration.class)
                .withBean(PhotoProperties.class, () -> photo)
                .withBean(JwtProperties.class, () -> jwt);
    }

    @Test
    void 두_서명키가_같으면_부팅이_죽는다() {
        runnerWith(photo("bucket", SHARED), jwt(SHARED)).run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootMessageOf(context))
                    .as("어느 값을 고쳐야 하는지가 메시지에 있어야 한다")
                    .contains("PROFILE_PHOTO_TOKEN_SECRET")
                    .contains("JWT_SECRET");
        });
    }

    /** 🔴 값을 실으면 바인딩 실패 리포트가 그렇듯 시크릿이 평문으로 로그·CI에 남는다. */
    @Test
    void 실패_메시지에_키_값이_실리지_않는다() {
        runnerWith(photo("bucket", SHARED), jwt(SHARED)).run(context -> {
            assertThat(context).hasFailed();
            assertThat(fullStackOf(context))
                    .as("스택 전체 어디에도 값이 없어야 한다 — cause에 숨어도 로그에는 찍힌다")
                    .doesNotContain(SHARED);
        });
    }

    @Test
    void 두_서명키가_다르면_그대로_뜬다() {
        runnerWith(photo("bucket", SHARED), jwt("a-totally-different-login-secret-32b!!")).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).getBean(PhotoStorage.class).isNotSameAs(PhotoStorage.NONE);
        });
    }

    /**
     * 꺼진 상태에서는 비교하지 않는다 — 표 서명키가 빈 문자열이라 비교할 값 자체가 없고,
     * 「창고 이름이 비면 아무 부품도 안 만든다」가 이 검사보다 앞이다.
     */
    @Test
    void 사진이_꺼져_있으면_비교하지_않는다() {
        runnerWith(photo("", ""), jwt(SHARED)).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).getBean(PhotoStorage.class).isSameAs(PhotoStorage.NONE);
        });
    }

    /**
     * 🔴 <b>부팅 시점에 신호가 없으면 운영자가 503의 원인을 못 본다</b>(실기동 NG 1건).
     * 조용히 {@code PhotoStorage.NONE}이 돌아가고 아무 데도 줄이 안 남는다.
     * 태스크 7이 <b>요청 시점</b>에 WARN을 넣었지만 그것은 「사진을 이미 올린 회원」 갈래뿐이고,
     * 창고를 한 번도 안 켠 배포에서는 그 줄조차 안 난다. chat-collector
     * {@code ArchiveConfiguration}의 {@code chat.archive.disabled}와 같은 자리다.
     *
     * <p><b>{@code @SpringBootTest}로는 이 줄을 못 잡는다</b> — 컨텍스트가 캐시돼서 시험 메서드가
     * 돌기 <b>전에</b> 이미 떠 있고, 그때는 LogCaptor가 아직 안 붙어 있다. 여기서는
     * {@code ApplicationContextRunner}가 {@code run()} 안에서 컨텍스트를 만들어서 잡힌다
     * (선례도 조립 메서드를 직접 부른다).
     */
    @Test
    void 사진이_꺼지면_부팅_로그에_한_줄_남는다() {
        try (LogCaptor logs = new LogCaptor()) {
            runnerWith(photo("", ""), jwt(SHARED)).run(context ->
                    assertThat(context).hasNotFailed());

            assertThat(logs.messages())
                    .as("운영자가 503의 원인을 로그로 볼 수 있어야 한다")
                    .anyMatch(m -> m.equals("auth.profile.photo.disabled reason=no_bucket"));
        }
    }

    /**
     * 켜진 쪽에도 한 줄 남긴다 — 꺼짐만 찍으면 「줄이 없다」가 <b>꺼진 것</b>과
     * <b>이 코드가 안 돈 것</b> 둘 다를 뜻하게 된다.
     *
     * <p>🔴 <b>창고 이름과 서명키는 안 찍는다.</b> 선례가 그렇고(「버킷 이름은 안 찍는다」),
     * 서명키는 말할 것도 없다. 찍는 것은 붙는 방법(지역·엔드포인트 유무·path-style)뿐이다 —
     * 창고에 못 붙을 때 운영자가 봐야 하는 것이 그 셋이다.
     */
    @Test
    void 사진이_켜지면_부팅_로그에_한_줄_남고_창고_이름과_키는_안_실린다() {
        String bucket = "some-private-bucket-name";
        try (LogCaptor logs = new LogCaptor()) {
            runnerWith(photo(bucket, SHARED), jwt("a-totally-different-login-secret-32b!!")).run(context ->
                    assertThat(context).hasNotFailed());

            String line = logs.messages().stream()
                    .filter(m -> m.startsWith("auth.profile.photo.enabled"))
                    .findFirst()
                    .orElse(null);
            assertThat(line).as("켜진 것도 한 줄로 보여야 한다").isNotNull();
            assertThat(line)
                    .as("창고 이름·서명키는 로그에 남기지 않는다")
                    .doesNotContain(bucket)
                    .doesNotContain(SHARED);
        }
    }

    private static String rootMessageOf(AssertableApplicationContext context) {
        Throwable t = context.getStartupFailure();
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return String.valueOf(t.getMessage());
    }

    private static String fullStackOf(AssertableApplicationContext context) {
        StringWriter text = new StringWriter();
        context.getStartupFailure().printStackTrace(new PrintWriter(text));
        return text.toString();
    }
}
