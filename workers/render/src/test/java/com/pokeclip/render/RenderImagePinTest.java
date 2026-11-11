package com.pokeclip.render;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 렌더 이미지의 ffmpeg 판이 조용히 바뀌지 않게 {@code Dockerfile}을 글자로 본다(PDT 설계 7.7 (가) · 예측 P1). 실행 단계의 기반
 * 이미지는 목록(manifest list) 다이제스트로, ffmpeg와 같은 소스의 라이브러리는 한 판으로 고정한다. 부동 태그로 되돌리거나 꾸러미 하나라도
 * 판을 빼면 여기서 깨진다. 설치 뒤 판 검사는 글자가 아니라 실제로 돌려 본다. workers-ci 렌더 잡이 판 글자 · 꾸러미 목록을 이 파일에서
 * 읽고 같은 검사를 하는지도 본다.
 *
 * <p>판을 올릴 때는 이 시험의 판 글자와 {@code Dockerfile}의 {@code FFMPEG_VERSION}을 함께 고친다. 라이브러리 이름(so 판)이 바뀌면 이
 * 시험의 꾸러미 목록과 {@code FFMPEG_PACKAGES}를 함께 고친다(workers-ci · README 명령은 그 두 값을 읽는다).
 */
class RenderImagePinTest {

    private static final String FFMPEG_VERSION = "7:8.0.1-3ubuntu2";
    /**
     * ffmpeg 소스 꾸러미가 내는 바이너리 꾸러미. ffmpeg는 라이브러리를 {@code >= 7:8.0.1} 하한으로만 물어서, 라이브러리 판을 같이 박지 않으면
     * 수정판이 올라온 날 ffmpeg만 고정 판이고 라이브러리는 새 판으로 섞여 깔린다.
     */
    private static final List<String> FFMPEG_PACKAGES = List.of("ffmpeg", "libavcodec62", "libavdevice62", "libavfilter11",
            "libavformat62", "libavutil60", "libswresample6", "libswscale9");
    private static final String PINNED_RUNTIME_BASE = "FROM eclipse-temurin:21-jre@sha256:[0-9a-f]{64}";
    /** 설치 줄. 목록의 꾸러미마다 판을 붙인다. */
    private static final String PINNED_INSTALL = "$(for p in $FFMPEG_PACKAGES; do echo \"$p=$FFMPEG_VERSION\"; done)";
    /** 설치 뒤 검사의 awk 프로그램. 넘기는 값의 이름은 Dockerfile과 workers-ci가 다르다. 프로그램은 작은따옴표 안에 있다. */
    private static final Pattern CHECK_AWK = Pattern.compile("awk -v want=\"[^\"]*\" -v need=\"[^\"]*\" '([^']*)'");
    private static final Path WORKERS_CI = Path.of("../../.github/workflows/workers-ci.yml");

    @Test
    void 실행_단계_기반_이미지는_목록_다이제스트로_고정한다() throws IOException {
        List<String> froms = instructions().stream().filter(line -> line.startsWith("FROM ")).toList();

        assertThat(froms).as("빌드 단계와 실행 단계 둘").hasSize(2);
        assertThat(froms.getLast()).as("부동 태그면 기반 이미지가 바뀔 때 ffmpeg 판도 같이 움직인다").matches(PINNED_RUNTIME_BASE);
    }

    @Test
    void ffmpeg_판은_실행_단계의_ARG_한_곳에서_정한다() throws IOException {
        assertThat(argLines("FFMPEG_VERSION")).as("판 글자가 여러 군데면 하나만 고치고 나머지가 남는다")
                .containsExactly("ARG FFMPEG_VERSION=" + FFMPEG_VERSION);
        assertArgVisibleToInstall("FFMPEG_VERSION");
    }

    @Test
    void ffmpeg와_같은_소스_꾸러미_목록은_실행_단계의_ARG_한_곳에서_정한다() throws IOException {
        List<String> args = argLines("FFMPEG_PACKAGES");

        assertThat(args).as("목록이 여러 군데면 하나만 고치고 나머지가 남는다").hasSize(1);
        assertThat(args.getFirst()).startsWith("ARG FFMPEG_PACKAGES=\"").endsWith("\"");
        String value = args.getFirst().substring("ARG FFMPEG_PACKAGES=\"".length(), args.getFirst().length() - 1);
        assertThat(value.split(" ")).containsExactlyElementsOf(FFMPEG_PACKAGES);
        assertArgVisibleToInstall("FFMPEG_PACKAGES");
    }

    @Test
    void 목록의_꾸러미는_모두_같은_판으로_깔고_목록_밖_ffmpeg_꾸러미는_따로_깔지_않는다() throws IOException {
        String install = aptInstall();
        List<String> loose = Arrays.stream(install.replace(PINNED_INSTALL, " ").split("\\s+"))
                .filter(token -> token.startsWith("ffmpeg") || token.startsWith("libav") || token.startsWith("libsw"))
                .toList();

        assertThat(install).as("판 없는 꾸러미는 아카이브의 그날 판이 깔린다").contains(PINNED_INSTALL);
        assertThat(loose).as("목록 밖에 따로 적은 꾸러미는 판 검사 목록에서도 빠진다").isEmpty();
    }

    @Test
    void 설치_뒤_검사는_판_글자와_꾸러미_목록을_받는다() throws IOException {
        String check = runtimeStage().stream()
                .flatMap(line -> Arrays.stream(line.split("&&")))
                .filter(command -> command.contains("dpkg-query"))
                .findFirst()
                .orElseThrow();

        assertThat(check).as("깔린 상태와 소스 꾸러미 이름이 있어야 「목록 밖 꾸러미」 · 「안 깔림」을 가른다")
                .contains("${db:Status-Abbrev} ${source:Package} ${Package} ${Version}")
                .contains("awk -v want=\"$FFMPEG_VERSION\" -v need=\"$FFMPEG_PACKAGES\"");
    }

    @Test
    void 설치_뒤_검사는_다_깔리고_판이_같으면_통과한다() throws Exception {
        String rows = installed(FFMPEG_PACKAGES, FFMPEG_VERSION) + "ii  fonts-noto-cjk fonts-noto-cjk 1:20240730+repack1-1\n";

        CheckRun run = runCheck(FFMPEG_VERSION, String.join(" ", FFMPEG_PACKAGES), rows);

        assertThat(run.exit()).as(run.output()).isZero();
    }

    @Test
    void 설치_뒤_검사는_목록의_꾸러미가_하나라도_없으면_멈춘다() throws Exception {
        CheckRun run = runCheck(FFMPEG_VERSION, String.join(" ", FFMPEG_PACKAGES),
                installed(FFMPEG_PACKAGES.subList(0, FFMPEG_PACKAGES.size() - 1), FFMPEG_VERSION));

        assertThat(run.exit()).isNotZero();
        assertThat(run.output()).contains("libswscale9");
    }

    @Test
    void 설치_뒤_검사는_지워진_채_설정만_남은_꾸러미를_깔린_것으로_세지_않는다() throws Exception {
        String rows = installed(FFMPEG_PACKAGES.subList(0, FFMPEG_PACKAGES.size() - 1), FFMPEG_VERSION)
                + "rc  ffmpeg libswscale9 " + FFMPEG_VERSION + "\n";

        CheckRun run = runCheck(FFMPEG_VERSION, String.join(" ", FFMPEG_PACKAGES), rows);

        assertThat(run.exit()).isNotZero();
        assertThat(run.output()).contains("libswscale9");
    }

    @Test
    void 설치_뒤_검사는_목록_밖_ffmpeg_꾸러미가_다른_판이면_멈춘다() throws Exception {
        String rows = installed(FFMPEG_PACKAGES, FFMPEG_VERSION)
                + "ii  ffmpeg libpostproc59 " + FFMPEG_VERSION + ".1\n";

        CheckRun run = runCheck(FFMPEG_VERSION, String.join(" ", FFMPEG_PACKAGES), rows);

        assertThat(run.exit()).isNotZero();
        assertThat(run.output()).contains("libpostproc59");
    }

    @Test
    void 설치_뒤_검사는_판_글자나_목록이_비면_멈춘다() throws Exception {
        String rows = installed(FFMPEG_PACKAGES, FFMPEG_VERSION);

        assertThat(runCheck("", String.join(" ", FFMPEG_PACKAGES), rows).exit()).as("판 글자 빔").isNotZero();
        assertThat(runCheck(FFMPEG_VERSION, "", rows).exit()).as("목록 빔").isNotZero();
    }

    @Test
    void workers_ci_렌더_잡은_Dockerfile의_판_글자와_목록을_읽는다() throws IOException {
        String ci = Files.readString(WORKERS_CI);

        assertThat(ci).as("판 글자 · 목록을 CI에 따로 적으면 판을 올릴 때 한쪽이 남는다")
                .contains("sed -n 's/^ARG FFMPEG_VERSION=//p' Dockerfile")
                .contains("sed -n 's/^ARG FFMPEG_PACKAGES=\"\\(.*\\)\"$/\\1/p' Dockerfile")
                .doesNotContain("libavcodec");
    }

    @Test
    void workers_ci는_설치_뒤_이미지와_같은_검사와_실행_파일_판_대조를_한다() throws IOException {
        String ci = Files.readString(WORKERS_CI);

        assertThat(oneSpaced(awkProgram(ci))).as("이미지와 다른 검사면 CI가 이미지에서는 멈출 판을 통과시킨다")
                .isEqualTo(oneSpaced(awkProgram(dockerfile())));
        assertThat(ci).as("꾸러미 판만 보면 PATH 앞의 다른 ffmpeg가 시험을 돌려도 모른다")
                .contains("\"ffmpeg version ${version#*:} \"*");
    }

    /** {@code ARG} 가 설치 RUN 보다 앞, 같은 실행 단계에 있어야 RUN 에서 보인다(단계 밖 ARG 는 빈 값이다). */
    private static void assertArgVisibleToInstall(String name) throws IOException {
        List<String> stage = runtimeStage();
        int arg = indexOf(stage, line -> line.startsWith("ARG " + name + "="));
        int install = indexOf(stage, line -> line.contains("apt-get install"));

        assertThat(arg).as("실행 단계의 ARG " + name).isNotNegative();
        assertThat(arg).as("설치 RUN 보다 앞").isLessThan(install);
    }

    private static int indexOf(List<String> lines, Predicate<String> match) {
        for (int i = 0; i < lines.size(); i++) {
            if (match.test(lines.get(i))) {
                return i;
            }
        }
        return -1;
    }

    /** Dockerfile 전체(모든 단계)에서 {@code ARG <name>=} 줄. */
    private static List<String> argLines(String name) throws IOException {
        return instructions().stream().filter(line -> line.startsWith("ARG " + name + "=")).toList();
    }

    private static String installed(List<String> packages, String version) {
        return packages.stream().map(p -> "ii  ffmpeg " + p + " " + version + "\n").collect(Collectors.joining());
    }

    private record CheckRun(int exit, String output) {
    }

    /**
     * Dockerfile 의 설치 뒤 검사 awk 를 그대로 돌린다. 도커처럼 줄 끝 {@code \}와 줄바꿈을 지워 한 줄로 넘긴다. {@code rows}는
     * dpkg-query 출력을 흉내 낸 것이다. 파이프가 아니라 파일로 넣는다. 빈 값 막기는 입력을 읽기 전에 끝나서, 파이프면 쓰는 쪽이
     * {@code Broken pipe}로 깨진다.
     */
    private static CheckRun runCheck(String want, String need, String rows) throws IOException, InterruptedException {
        String program = awkProgram(dockerfile()).replace("\\\n", "");
        Path input = Files.createTempFile("dpkg-rows", ".txt");
        try {
            Files.writeString(input, rows, StandardCharsets.UTF_8);
            Process process = new ProcessBuilder("awk", "-v", "want=" + want, "-v", "need=" + need, program)
                    .redirectInput(input.toFile())
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(process.waitFor(10, TimeUnit.SECONDS)).as("awk 가 끝나야 한다").isTrue();
            return new CheckRun(process.exitValue(), output);
        } finally {
            Files.delete(input);
        }
    }

    private static String awkProgram(String text) {
        Matcher m = CHECK_AWK.matcher(text);
        assertThat(m.find()).as("설치 뒤 검사 awk").isTrue();
        String program = m.group(1);
        assertThat(m.find()).as("검사 awk 는 하나").isFalse();
        return program;
    }

    /** Dockerfile 은 줄 끝 {@code \}로, workers-ci 는 줄바꿈으로 프로그램을 나눈다. 빈칸 묶음을 한 칸으로 맞춰 비교한다. */
    private static String oneSpaced(String program) {
        return program.replace("\\\n", " ").strip().replaceAll("\\s+", " ");
    }

    /** 실행 단계 RUN 의 {@code apt-get install} 명령 하나(앞뒤 {@code &&} 명령은 뺀다). */
    private static String aptInstall() throws IOException {
        return runtimeStage().stream()
                .flatMap(line -> Arrays.stream(line.split("&&")))
                .filter(command -> command.contains("apt-get install"))
                .findFirst()
                .orElseThrow();
    }

    /** 마지막 FROM 부터 끝까지. */
    private static List<String> runtimeStage() throws IOException {
        List<String> all = instructions();
        int from = 0;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).startsWith("FROM ")) {
                from = i;
            }
        }
        return all.subList(from, all.size());
    }

    /** 줄 끝 {@code \}로 이어진 줄을 한 명령으로 붙이고 주석 줄을 뺀다. 시험은 일꾼 폴더(빌드 컨텍스트)에서 돈다. */
    private static List<String> instructions() throws IOException {
        String joined = dockerfile().replace("\\\n", " ");
        return joined.lines().map(String::strip).filter(line -> !line.isEmpty() && !line.startsWith("#")).toList();
    }

    private static String dockerfile() throws IOException {
        return Files.readString(Path.of("Dockerfile"));
    }
}
