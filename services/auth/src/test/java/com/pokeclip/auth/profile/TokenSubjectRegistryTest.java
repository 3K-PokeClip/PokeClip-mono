package com.pokeclip.auth.profile;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>{@code Long.valueOf(jwt.getSubject())} 명부가 실제 자리 수와 맞는지 기계로 센다.</b>
 *
 * <p>🔴 <b>왜 만들었나 — 사람 눈으로 두 번 연속 틀렸다.</b> 그 명부는 「감싼 자리와 안 감싼 자리」를
 * 전수로 적어 두는 문장인데(auth/CLAUDE.md 「알려진 구멍」 22), <b>세는 시점이 늘 자기 PR의 중간</b>이라
 * 뒤에 들어온 자리가 안 잡힌다. POK-207은 한 파일에 두 자리인 것을 놓쳤고(리뷰가 잡았다),
 * POK-171은 <b>같은 PR의 한 커밋 앞에서 자기가 만든 자리</b>({@code WithdrawnAccountFilter})를 못 셌다
 * (감사 2회차가 잡았다). 두 번 다 초록인 채로 틀려 있었다.
 *
 * <p>이 검사가 잡는 것은 <b>자리가 늘거나 줄 때</b>다 — 그때 명부를 안 고치면 빨간불이다.
 * 반대로 <b>안 감싼 일곱을 감싸는 것은 막지 않는다</b>(자리 수가 안 변한다). 「알려진 구멍」 22가
 * 「일곱이 안 감싸져 있다」를 시험으로 굳히지 말라고 한 이유가 그것이고, 여기는 그 금을 안 넘는다.
 *
 * <p>「감쌌다」의 판정은 <b>그 줄 바로 뒤 두 줄에 {@code catch (NumberFormatException}이 있는가</b>다.
 * 지금 감싼 넷이 전부 {@code try { return …; } catch (NumberFormatException e)} 한 모양이라 그것으로 충분하다.
 * 다른 모양으로 감싸는 자리가 생기면 이 판정부터 고친다.
 *
 * <p>파일만 읽으므로 컨텍스트도 DB도 없다. <b>{@code build.gradle}이 main 소스를 테스트 입력으로 선언한다</b> —
 * 명부는 주석이라 그 선언이 없으면 <b>주석만 바꾼 커밋에서 Gradle이 이 검사를 건너뛴다</b>
 * ({@code DeploymentEnvVarsTest}·{@code YoutubeRevokeDocTest}에서 두 번 겪은 함정).
 */
class TokenSubjectRegistryTest {

    private static final Path MAIN = Path.of("src/main/java/com/pokeclip/auth");
    private static final String SITE = "Long.valueOf(jwt.getSubject())";

    /** 명부가 자기 숫자를 기계가 읽을 수 있게 적어 둔 한 줄. */
    private static final Pattern CENSUS =
            Pattern.compile("전수 (\\d+)자리 · (\\d+)파일 · 감싼 것 (\\d+) · 안 감싼 것 (\\d+)");

    /** 명부가 사는 자리. 커밋되는 파일이라 여기가 정본이고, CLAUDE.md는 사본이다. */
    private static final Path REGISTRY = MAIN.resolve("profile/api/ProfilePhotoController.java");

    private record Site(String file, int line, boolean wrapped) {
        @Override
        public String toString() {
            return file + ":" + line + (wrapped ? " (감쌈)" : " (안 감쌈)");
        }
    }

    private static List<Site> sites() throws IOException {
        List<Site> found = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(MAIN)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                List<String> lines = Files.readAllLines(path);
                for (int i = 0; i < lines.size(); i++) {
                    if (!lines.get(i).contains(SITE) || lines.get(i).trim().startsWith("*")) {
                        continue;   // javadoc 안의 인용은 자리가 아니다
                    }
                    String after = String.join("\n", lines.subList(i + 1, Math.min(i + 3, lines.size())));
                    found.add(new Site(path.getFileName().toString(), i + 1,
                            after.contains("catch (NumberFormatException")));
                }
            }
        }
        return found;
    }

    private static Matcher census() throws IOException {
        Matcher matcher = CENSUS.matcher(Files.readString(REGISTRY));
        assertThat(matcher.find())
                .as("%s에서 전수 표기를 못 찾았다 — 명부가 사라졌거나 형식이 바뀌었다. 형식: %s",
                        REGISTRY, CENSUS.pattern())
                .isTrue();
        return matcher;
    }

    @Test
    void 명부에_적힌_숫자가_실제_자리_수와_같다() throws IOException {
        List<Site> sites = sites();
        long files = sites.stream().map(Site::file).distinct().count();
        long wrapped = sites.stream().filter(Site::wrapped).count();
        Matcher census = census();

        assertThat(sites).as("자리를 하나도 못 찾았다 — 검사가 낡았다").isNotEmpty();
        assertThat(List.of(census.group(1), census.group(2), census.group(3), census.group(4)))
                .as("명부(%s)와 실제가 어긋난다.%n실제: %d자리 · %d파일 · 감싼 것 %d · 안 감싼 것 %d%n자리: %s%n"
                                + "🔴 명부를 고칠 자리가 셋이다 — 이 javadoc · WithdrawalController.userId javadoc · "
                                + "auth/CLAUDE.md 「알려진 구멍」 22",
                        REGISTRY, sites.size(), files, wrapped, sites.size() - wrapped, sites)
                .containsExactly(String.valueOf(sites.size()), String.valueOf(files),
                        String.valueOf(wrapped), String.valueOf(sites.size() - wrapped));
    }

    @Test
    void 명부가_자리마다_어느_파일인지까지_적는다() throws IOException {
        String registry = Files.readString(REGISTRY);

        assertThat(sites())
                .as("숫자만 맞고 파일 이름이 빠지면 명부를 읽는 사람이 그 자리를 못 찾는다")
                .allSatisfy(site -> assertThat(registry)
                        .as("명부에 %s가 없다", site)
                        .contains(site.file().replace(".java", "")));
    }
}
