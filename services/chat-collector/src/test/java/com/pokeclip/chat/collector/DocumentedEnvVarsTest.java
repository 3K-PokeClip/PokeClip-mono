package com.pokeclip.chat.collector;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * application.yml이 읽는 환경변수가 커밋되는 문서(services/README.md)에 전부 적혀 있어야 한다.
 *
 * <p><b>왜 필요한가.</b> POK-127이 환경변수를 아홉 개 늘렸는데(SQS 다섯·auth 둘·메모 둘)
 * README는 한 줄도 안 바뀌었다. 그 상태로도 빌드는 초록이고 서버도 뜬다 — 모자란 것은
 * <b>운영자가 무엇을 채워야 하는지 알 길</b>뿐이라, 배포하는 날에만 드러난다.
 * 사람 눈 대조는 태스크 지시서에 적어도 매번 새로 해야 하므로 여기 박는다.
 *
 * <p><b>문자열이 아니라 YAML로 읽는 이유:</b> 파일을 통째로 문자열로 훑으면 주석 안의
 * 예시까지 매칭된다. 그러면 설정 줄을 지우고 설명 주석만 남겨도 초록이 되고, 반대로
 * 주석에 예시를 하나 적으면 멀쩡한 설정이 빨간불이 된다. auth의 RequiredPropertiesTest가
 * 같은 이유로 같은 방식을 쓴다(services/CLAUDE.md 「함정」).
 *
 * <p><b>이 검사가 보증하지 않는 것 둘 — 「없다」가 아니라 「안 본다」이다.</b>
 * <ul>
 *   <li><b>어느 서버 절에 적혔는지는 안 본다.</b> README는 서버 넷을 한 파일에 담으므로,
 *       같은 이름이 clip·auth 표에 이미 있으면 chat-collector 절이 비어도 통과한다
 *       (실제로 {@code BROADCAST_INTAKE_ENABLED}·{@code INTERNAL_API_TOKEN}·{@code AWS_REGION}이
 *       그 상태다). 절을 경계로 자르는 판도 만들어 봤으나, chat-collector 환경변수는
 *       기동 절과 서버 절 <b>두 곳</b>에 나뉘어 있어 그 판은 문서를 중복시켜야 통과한다 —
 *       한쪽만 낡는 구조를 만들지 않는 편을 골랐다.</li>
 *   <li><b>반대 방향(README에만 있고 yml에 없는 변수)은 안 본다.</b> 없어진 변수가
 *       문서에 남는 것은 실행을 막지 않고, 시험용 변수({@code CHZZK_LIVE_PROBE} 등)가
 *       정상적으로 그 상태다.</li>
 * </ul>
 */
class DocumentedEnvVarsTest {

    /** {@code ${VAR}} · {@code ${VAR:기본값}} 둘 다 잡는다. 환경변수는 대문자·숫자·밑줄뿐이다. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Z][A-Z0-9_]*)[:}]");

    /**
     * Gradle 테스트의 작업 디렉터리는 모듈 폴더(services/chat-collector)다.
     * 못 찾으면 아래 단언이 크게 터진다 — 조용히 통과하지 않는다.
     *
     * <p>🔴 <b>이 파일은 {@code build.gradle}에 test 태스크의 입력으로 선언돼 있다.</b>
     * 그 줄을 지우면 README만 고친 실행에서 test가 <b>UP-TO-DATE로 통째로 건너뛰어</b>
     * 문서에서 환경변수를 지워도 초록이 된다 — 결함 주입으로 실측했다(2026-08-19).
     * 검사 밖에 보증이 하나 걸려 있는 드문 모양이라 여기 적어 둔다.
     */
    private static final Path README = Path.of("..", "README.md");

    @Test
    void application_yml이_읽는_환경변수가_전부_README에_적혀_있다() throws IOException {
        Set<String> declared = envVarsInApplicationYml();
        // 정규식이나 파싱이 망가지면 빈 집합이 되고, 그러면 아래 루프가 0바퀴라
        // "전부 적혀 있다"가 공짜로 참이 된다.
        assertThat(declared).as("application.yml에서 환경변수를 하나도 못 읽었다").isNotEmpty();

        String readme = Files.readString(README, StandardCharsets.UTF_8);
        assertThat(readme).as("services/README.md를 못 읽었다 — 작업 디렉터리가 바뀌었나").isNotBlank();

        assertThat(declared)
                .as("application.yml에 있는데 services/README.md에 안 적힌 환경변수다 — "
                        + "운영자가 무엇을 채워야 하는지 알 길이 없다")
                .allMatch(readme::contains, "README.md에 이름이 나온다");
    }

    private Set<String> envVarsInApplicationYml() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties properties = yaml.getObject();
        assertThat(properties).as("application.yml을 읽지 못했다").isNotNull().isNotEmpty();

        Set<String> names = new TreeSet<>();
        for (String value : properties.stringPropertyNames().stream()
                .map(properties::getProperty).toList()) {
            Matcher matcher = PLACEHOLDER.matcher(value);
            while (matcher.find()) {
                names.add(matcher.group(1));
            }
        }
        return names;
    }
}
