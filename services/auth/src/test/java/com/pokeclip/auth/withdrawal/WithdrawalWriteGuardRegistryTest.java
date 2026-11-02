package com.pokeclip.auth.withdrawal;

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
 * <b>「탈퇴한 회원인가」를 묻는 자리의 전수 명부가 실제와 맞는지 기계로 센다.</b>
 *
 * <p>🔴 <b>왜 만들었나</b> — 이 저장소에서 「같은 뿌리인데 한 자리만 고침」이 네 세션 연속 났고
 * (POK-118·120·121·174), 그때마다 <b>전수를 세어 표로 만든 판에서만</b> 나머지가 안 올라왔다.
 * 그런데 사람이 세는 표는 <b>세는 시점이 늘 자기 PR의 중간</b>이라 뒤에 들어온 자리를 놓친다 —
 * {@code TokenSubjectRegistryTest}가 정확히 그 이유로 생겼고 여기는 그 골격을 그대로 쓴다.
 *
 * <p>이 검사가 잡는 것은 <b>자리가 늘거나 줄 때</b>다. 새 쓰기 경로에 가드를 붙이면 숫자가 늘어
 * 빨간불이 되고, 명부를 고치면서 <b>그 자리가 어느 표를 지키는지</b>를 적게 된다.
 * 반대로 <b>가드 밖에서 회원 행 락을 잡는 자리</b>도 함께 센다 — 거기가 늘어나는 것이
 * 「가드를 안 거치는 새 쓰기 경로가 생겼다」의 가장 이른 신호다.
 *
 * <p>파일만 읽으므로 컨텍스트도 DB도 없다. {@code build.gradle}이 main 소스를 테스트 입력으로
 * 선언해 둔 덕에 <b>주석만 바꾼 커밋에서도 이 검사가 돈다</b>(그 선언이 없으면 명부를 틀린 숫자로
 * 고쳐도 Gradle이 {@code UP-TO-DATE}로 건너뛴다 — 이 저장소가 세 번 겪은 함정이다).
 */
class WithdrawalWriteGuardRegistryTest {

    private static final Path MAIN = Path.of("src/main/java/com/pokeclip/auth");

    /** 명부가 사는 자리. 커밋되는 파일이라 여기가 정본이고 CLAUDE.md는 사본이다. */
    private static final Path REGISTRY = MAIN.resolve("user/ActiveUserGuard.java");

    /** 회원 행 락을 선언한 자리. 그 자체는 자리가 아니다. */
    private static final Path REPOSITORY = MAIN.resolve("user/UserRepository.java");

    private static final Pattern CENSUS = Pattern.compile(
            "전수 명부 — 막는 자리 (\\d+)자리 · (\\d+)파일 · 가드 밖 회원 행 락 (\\d+)자리");

    private record Site(String file, int line) {
        @Override
        public String toString() {
            return file + ":" + line;
        }
    }

    /**
     * 자리를 센다. <b>javadoc 안의 인용은 자리가 아니다</b> — 이 명부가 자기 이름을 여러 번 적으므로
     * 그것을 안 거르면 숫자가 저절로 늘어난다.
     */
    private static List<Site> sitesOf(String needle, Path... except) throws IOException {
        List<Path> excluded = List.of(except);
        List<Site> found = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(MAIN)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                if (excluded.contains(path)) {
                    continue;
                }
                List<String> lines = Files.readAllLines(path);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    if (line.contains(needle) && !line.trim().startsWith("*") && !line.trim().startsWith("//")) {
                        found.add(new Site(path.getFileName().toString(), i + 1));
                    }
                }
            }
        }
        return found;
    }

    /** 가드를 부르는 자리. 가드 자신은 뺀다 — 그 안의 호출은 세 진입점이 서로를 부르는 것이다. */
    private static List<Site> guarded() throws IOException {
        return sitesOf("requireAlive", REGISTRY);
    }

    /** 가드를 안 거치고 회원 행 락을 잡는 자리. 선언부와 가드 자신은 뺀다. */
    private static List<Site> rawLocks() throws IOException {
        return sitesOf("findByIdForUpdate(", REGISTRY, REPOSITORY);
    }

    @Test
    void 명부에_적힌_숫자가_실제_자리_수와_같다() throws IOException {
        List<Site> guarded = guarded();
        List<Site> raw = rawLocks();
        long files = guarded.stream().map(Site::file).distinct().count();

        Matcher census = CENSUS.matcher(Files.readString(REGISTRY));
        assertThat(census.find())
                .as("%s에서 전수 표기를 못 찾았다 — 명부가 사라졌거나 형식이 바뀌었다. 형식: %s",
                        REGISTRY, CENSUS.pattern())
                .isTrue();

        assertThat(guarded).as("가드를 부르는 자리를 하나도 못 찾았다 — 검사가 낡았다").isNotEmpty();
        assertThat(List.of(census.group(1), census.group(2), census.group(3)))
                .as("""
                        명부(%s)와 실제가 어긋난다.
                        실제: 막는 자리 %d · %d파일 · 가드 밖 회원 행 락 %d
                        막는 자리: %s
                        가드 밖 락: %s
                        🔴 자리가 늘었다면 그 자리가 어느 표를 지키는지 명부의 표에 한 줄 더 적는다.
                        🔴 가드 밖 락이 늘었다면 「왜 면제인가」를 적을 수 있어야 한다 — 못 적으면 가드를 써야 하는 자리다.""",
                        REGISTRY, guarded.size(), files, raw.size(), guarded, raw)
                .containsExactly(String.valueOf(guarded.size()), String.valueOf(files),
                        String.valueOf(raw.size()));
    }

    /** 숫자만 맞고 파일 이름이 빠지면 명부를 읽는 사람이 그 자리를 못 찾는다. */
    @Test
    void 명부가_자리마다_어느_파일인지까지_적는다() throws IOException {
        String registry = Files.readString(REGISTRY);

        List<Site> all = new ArrayList<>(guarded());
        all.addAll(rawLocks());
        assertThat(all)
                .allSatisfy(site -> assertThat(registry)
                        .as("명부에 %s가 없다", site)
                        .contains(site.file().replace(".java", "")));
    }
}
