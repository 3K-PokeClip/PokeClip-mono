package com.pokeclip.render;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 렌더 이미지의 ffmpeg 판이 조용히 바뀌지 않게 {@code Dockerfile}을 글자로 본다(PDT 설계 7.7 (가) · 예측 P1). 실행 단계의 기반
 * 이미지는 목록(manifest list) 다이제스트로, ffmpeg와 같은 소스의 라이브러리는 한 판으로 고정한다. 부동 태그로 되돌리거나 꾸러미 하나라도
 * 판을 빼면 여기서 깨진다.
 *
 * <p>판을 올릴 때는 이 시험의 판 글자와 {@code Dockerfile}의 {@code FFMPEG_VERSION}을 함께 고친다(workers-ci · README 명령은 그 값을 읽는다).
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

    @Test
    void 실행_단계_기반_이미지는_목록_다이제스트로_고정한다() throws IOException {
        List<String> froms = instructions().stream().filter(line -> line.startsWith("FROM ")).toList();

        assertThat(froms).as("빌드 단계와 실행 단계 둘").hasSize(2);
        assertThat(froms.getLast()).as("부동 태그면 기반 이미지가 바뀔 때 ffmpeg 판도 같이 움직인다").matches(PINNED_RUNTIME_BASE);
    }

    @Test
    void ffmpeg_판은_실행_단계의_ARG_한_곳에서_정한다() throws IOException {
        assertThat(runtimeStage()).as("판 글자가 여러 군데면 하나만 고치고 나머지가 남는다")
                .contains("ARG FFMPEG_VERSION=" + FFMPEG_VERSION);
    }

    @Test
    void ffmpeg와_같은_소스_꾸러미는_모두_같은_판으로_고정한다() throws IOException {
        List<String> tokens = Arrays.stream(aptInstall().split("\\s+"))
                .filter(token -> token.startsWith("ffmpeg") || token.startsWith("libav") || token.startsWith("libsw"))
                .toList();

        assertThat(tokens).as("판 없는 꾸러미는 아카이브의 그날 판이 깔린다")
                .containsExactlyInAnyOrderElementsOf(FFMPEG_PACKAGES.stream().map(p -> p + "=${FFMPEG_VERSION}").toList());
    }

    @Test
    void 설치_뒤_ffmpeg_소스_꾸러미의_판을_다시_본다() throws IOException {
        String run = runtimeStage().stream().filter(line -> line.contains("apt-get install")).findFirst().orElseThrow();

        assertThat(run).as("목록 밖 ffmpeg 소스 꾸러미가 다른 판으로 끌려와도 빌드가 멈춰야 한다")
                .contains("dpkg-query").contains("${source:Package}").contains("$FFMPEG_VERSION");
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
        String joined = Files.readString(Path.of("Dockerfile")).replace("\\\n", " ");
        return joined.lines().map(String::strip).filter(line -> !line.isEmpty() && !line.startsWith("#")).toList();
    }
}
