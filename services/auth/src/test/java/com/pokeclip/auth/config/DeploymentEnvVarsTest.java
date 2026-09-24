package com.pokeclip.auth.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 필수 환경변수는 <b>세 곳</b>에 있어야 한다 — {@code application.yml}(앱이 읽는 곳) ·
 * {@code docker-compose.dev.yml}(dev 컨테이너에 넘기는 곳) · {@code .env.dev.example}(운영자가 채우는 곳).
 *
 * <p>🔴 <b>한 곳만 빠뜨리면 dev 배포에서만 터진다.</b> 컨테이너는 호스트 셸의 변수를 자동으로 물려받지
 * 않으므로 compose에 없는 값은 그냥 비고, 앱은 부팅 검증에서 죽어 재시작 루프에 빠진다.
 * <b>로컬·CI는 전부 초록이다</b> — 테스트는 `application-test.yml`이 값을 채우고, compose를 안 쓰기 때문이다.
 *
 * <p>이 검사가 없던 동안 두 번 났다: POK-127이 chat-collector에서 편지 변수 넷을 표에만 적어 수집기가
 * 편지를 한 통도 안 먹었고(health는 초록이었다), POK-121이 유튜브 셋을 똑같이 빠뜨려 봇 리뷰가 잡았다.
 * `services/README.md`의 규칙 줄이 「그것을 지키는 검사가 저장소에 하나도 없다」고 적고 있던 자리다.
 *
 * <p>파일을 읽을 뿐이라 컨텍스트도 DB도 필요 없다.
 */
class DeploymentEnvVarsTest {

    /** {@code ${VAR:}} — 빈 기본값은 「부팅 검증으로 잡는 필수 값」이라는 표시다(README의 두 갈래 중 앱 시크릿 쪽). */
    private static final Pattern REQUIRED_IN_YML = Pattern.compile("\\$\\{([A-Z][A-Z0-9_]*):}");

    private static final Path SERVICES = Path.of("..");

    private static Set<String> requiredAppSecrets() throws IOException {
        String yml = Files.readString(SERVICES.resolve("auth/src/main/resources/application.yml"));
        Set<String> names = new LinkedHashSet<>();
        Matcher m = REQUIRED_IN_YML.matcher(yml);
        while (m.find()) {
            names.add(m.group(1));
        }
        return names;
    }

    @Test
    void 앱이_필수로_요구하는_변수가_dev_compose의_auth_블록에_전부_있다() throws IOException {
        Set<String> required = requiredAppSecrets();
        String composeAuthBlock = withoutComments(authBlockOf(Files.readString(SERVICES.resolve("docker-compose.dev.yml"))));

        assertThat(required).as("필수 변수를 하나도 못 찾았다 — 정규식이 낡았다").isNotEmpty();
        assertThat(required).allSatisfy(name -> assertThat(composeAuthBlock)
                .as("%s가 dev compose의 auth 블록에 없다 — 컨테이너는 호스트 변수를 안 물려받는다", name)
                .contains(name + ":"));
    }

    @Test
    void 앱이_필수로_요구하는_변수가_env_dev_example에_전부_있다() throws IOException {
        Set<String> required = requiredAppSecrets();
        String example = withoutComments(Files.readString(SERVICES.resolve(".env.dev.example")));

        assertThat(required).isNotEmpty();
        assertThat(required).allSatisfy(name -> assertThat(example)
                .as("%s가 .env.dev.example에 없다 — 운영자가 채울 자리가 없다", name)
                .contains(name + "="));
    }

    /**
     * 기본값이 있어 위 정규식({@code ${VAR:}})에 안 걸리는 배포 변수. 없어도 부팅은 되지만 compose·예시 파일에
     * 없으면 <b>dev에서 켤 수단이 없다.</b> 사진 카드가 이 사각을 「손으로 챙긴다」로 남겼고 POK-89가 하나 더
     * 만들면서 명부로 닫았다. 새로 기본값 있는 배포 변수를 만들면 여기에 넣는다.
     *
     * <p>{@code GOOGLE_REDIRECT_URI}는 원래 빠져 있었다(리뷰 라운드 3). yml에 로컬 콜백 주소가 기본값으로 있어
     * 부팅은 되지만 dev compose가 {@code :?}(없으면 컨테이너 기동 거부)로 걸어 뒀다 — 예시 파일에서 그 줄이
     * 빠지면 세 시험이 전부 초록인 채 dev auth 컨테이너만 안 뜬다(주입으로 확인).
     */
    private static final Set<String> DEFAULTED_DEPLOY_VARS = Set.of(
            "PROFILE_PHOTO_S3_FORCE_PATH_STYLE", "FORWARD_HEADERS_STRATEGY", "GOOGLE_REDIRECT_URI");

    /**
     * 셋째 파일(yml)도 본다 — 명부는 상수라 yml 쪽 변수 이름을 오타 내면(`FORWARD_HEADER_STRATEGY`) compose·예시에
     * 바른 이름이 있어도 초록이고, 운영에서 `native`를 줘도 조용히 안 켜진다(plan-critic 발견 C). 그것이 이 카드가 막으려는 실패다.
     */
    @Test
    void 기본값이_있는_배포_변수도_yml과_dev_compose와_예시_파일에_있다() throws IOException {
        String yml = withoutComments(Files.readString(SERVICES.resolve("auth/src/main/resources/application.yml")));
        String composeAuthBlock = withoutComments(authBlockOf(Files.readString(SERVICES.resolve("docker-compose.dev.yml"))));
        String example = withoutComments(Files.readString(SERVICES.resolve(".env.dev.example")));

        assertThat(DEFAULTED_DEPLOY_VARS).allSatisfy(name -> {
            assertThat(yml).as("%s를 application.yml이 ${%s:…}로 읽지 않는다 — 이름이 어긋났다", name, name).contains("${" + name + ":");
            assertThat(composeAuthBlock).as("%s가 dev compose의 auth 블록에 없다", name).contains(name + ":");
            assertThat(example).as("%s가 .env.dev.example에 없다", name).contains(name + "=");
        });
    }

    /**
     * 주석 줄을 뺀다 — {@code contains}는 {@code # FORWARD_HEADERS_STRATEGY: …}처럼 주석 처리된 줄도 통과시킨다
     * (리뷰 라운드 1 재현: 세 파일의 그 줄을 주석 처리해도 셋 다 초록이었다). 세 파일 다 {@code #} 줄 주석이라
     * 한 헬퍼로 충분하고, YAML 파싱은 {@code .env.dev.example}·compose의 {@code ${VAR:-기본}} 값에는 맞지 않는다.
     */
    private static String withoutComments(String text) {
        return text.lines()
                .filter(line -> !line.strip().startsWith("#"))
                .collect(Collectors.joining("\n"));
    }

    /** compose의 auth 서비스 블록만 잘라 낸다 — 다른 서비스의 같은 이름 변수에 속지 않으려고. */
    private static String authBlockOf(String compose) {
        int start = compose.indexOf("\n  auth:");
        assertThat(start).as("compose에 auth 서비스가 없다").isGreaterThan(0);
        int next = compose.indexOf("\n  clip:", start);
        return next > 0 ? compose.substring(start, next) : compose.substring(start);
    }
}
