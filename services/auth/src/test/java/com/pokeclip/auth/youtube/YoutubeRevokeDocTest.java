package com.pokeclip.auth.youtube;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * `services/README.md`의 <b>revoke 호출 지점 표</b>가 코드와 일치하는지 잰다.
 *
 * <p>🔴 그 표는 <b>웹·워커 팀이 보는 계약 문서</b>다. 이 PR에서 해제(DELETE)의 revoke를 걷어내고도
 * 표를 「부른다」로 남겨 두었고, 봇 리뷰가 잡았다 — 그대로 머지됐으면 <b>프론트가 반대 전제로 구현</b>했을 것이다
 * (바로 다음 절이 「구글 권한 페이지를 안내하라」고 요구하는데도).
 *
 * <p>「같은 뿌리, 한 자리만」이 이 세션에서 여러 번 났고 <b>이번엔 문서였다.</b> 코드만 고치고 표를 잊는 것을
 * 사람 눈으로 막을 수 없어서 검사로 만든다 — 표의 「부른다」 행 수와 <b>실제 revoke 호출 자리 수</b>를 대조한다.
 *
 * <p>파일만 읽으므로 컨텍스트도 DB도 없다. 두 파일은 `build.gradle`이 테스트 입력으로 선언한다
 * (안 그러면 문서만 바뀐 커밋에서 Gradle이 이 검사를 건너뛴다 — `DeploymentEnvVarsTest`에서 겪은 함정).
 */
class YoutubeRevokeDocTest {

    private static final Path SERVICES = Path.of("..");

    /** 프로덕션에서 구글 revoke를 실제로 부르는 자리 — `YoutubeTokenDiscarder.discard`를 호출하는 곳. */
    private static List<String> revokeCallSites() throws IOException {
        try (var paths = Files.walk(SERVICES.resolve("auth/src/main/java/com/pokeclip/auth/youtube"))) {
            return paths.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.getFileName().toString().equals("YoutubeTokenDiscarder.java"))
                    .flatMap(p -> {
                        try {
                            return Files.readAllLines(p).stream()
                                    .filter(line -> line.contains("discarder.discard("))
                                    .map(line -> p.getFileName() + ": " + line.trim());
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .toList();
        }
    }

    /** README 유튜브 절의 revoke 표에서 「부른다」로 적힌 행. 「안 부른다」는 제외한다. */
    private static List<String> docRowsSayingCalls() throws IOException {
        List<String> table = tableRows();
        return table.stream().filter(row -> row.contains("**부른다**")).toList();
    }

    private static List<String> tableRows() throws IOException {
        String readme = Files.readString(SERVICES.resolve("README.md"));
        int header = readme.indexOf("| 경로 | revoke | 왜 |");
        assertThat(header).as("README에서 revoke 호출 지점 표를 못 찾았다 — 표가 사라졌거나 형식이 바뀌었다")
                .isGreaterThan(0);
        String after = readme.substring(header);
        return after.lines().skip(2).takeWhile(line -> line.startsWith("|")).toList();
    }

    @Test
    void 표의_부른다_행_수가_실제_revoke_호출_자리_수와_같다() throws IOException {
        List<String> callSites = revokeCallSites();
        List<String> saysCalls = docRowsSayingCalls();

        assertThat(callSites).as("revoke 호출 자리를 하나도 못 찾았다 — 검사가 낡았다").isNotEmpty();
        assertThat(saysCalls)
                .as("README 표가 「부른다」로 적은 행 %d개 vs 코드의 실제 호출 자리 %d개%n코드: %s%n문서: %s",
                        saysCalls.size(), callSites.size(), callSites, saysCalls)
                .hasSameSizeAs(callSites);
    }

    /** 그 하나가 <b>갱신 거부</b>인지까지 본다 — 개수만 맞고 자리가 다르면 문서가 여전히 거짓이다. */
    @Test
    void 부른다로_적힌_행이_갱신_거부다() throws IOException {
        assertThat(docRowsSayingCalls()).singleElement()
                .as("「부른다」 행이 갱신 거부가 아니다 — 코드는 YoutubeTokenRefresher.reject 하나만 부른다")
                .satisfies(row -> assertThat(row).contains("갱신 거부"));
        assertThat(revokeCallSites()).singleElement()
                .satisfies(site -> assertThat(site).startsWith("YoutubeTokenRefresher.java"));
    }

    /** 표에 「안 부른다」 행이 실제로 있어야 한다 — 위 둘이 「표가 비어서」 통과하지 않게. */
    @Test
    void 표에_안_부른다_행도_있다() throws IOException {
        assertThat(tableRows()).filteredOn(row -> row.contains("**안 부른다**"))
                .as("해제·재연동·실패 정리 셋이 있어야 한다").hasSize(3);
    }
}
