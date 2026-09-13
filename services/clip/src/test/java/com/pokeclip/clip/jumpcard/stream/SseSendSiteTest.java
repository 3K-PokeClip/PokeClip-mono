package com.pokeclip.clip.jumpcard.stream;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * emitter 자물쇠를 잡는 호출(<b>{@code send}·{@code complete}·{@code completeWithError}</b>)이 {@code WriteGate} 안이나
 * 아래 허용 목록에만 있는지 센다(POK-234 태스크 19 · 감사 라운드 2 L13).
 *
 * <p><b>왜 기계가 세나.</b> 막힌 연결 건너뛰기는 emitter 자물쇠를 잡는 자리 <b>전부</b>가 그 문을 지나야 뜻이 있다 —
 * 하나라도 문 밖에서 부르면 그 job이 막힌 연결의 emitter 자물쇠에서 약 60초를 기다리고 같은 스트라이프가 통째로
 * 막힌다. {@code complete()}도 같은 자물쇠다(spring-webmvc 7.0.8 {@code ResponseBodyEmitter.complete}).
 * 이 프로젝트에서 「같은 뿌리인데 한 자리만 고쳤다」가 세션마다 났다. 그래서 자리를 사람이 세지 않는다.
 *
 * <p><b>무엇을 세나</b> — {@code jumpcard/} 운영 소스 전부에서 주석 줄을 뺀 코드 줄의 {@code .send (}·{@code .complete(}·
 * {@code .completeWithError(}(점과 괄호 사이 공백 허용)와 메서드 참조 {@code ::send} 모양. 🔴 처음 판은 {@code .send(}
 * 글자만 한 파일에서 세어 {@code .send (} 공백 우회가 초록이었다(감사 주입 I5).
 *
 * <p>🔴 <b>못 재는 것 — 「없다」가 아니라 「안 본다」.</b> 같은 줄에 코드와 주석이 섞인 경우 · 리플렉션 · 다른 이름의
 * 변수로 받은 emitter의 다른 메서드({@code sendInternal} 같은 것은 우리 코드에 없다).
 */
class SseSendSiteTest {

    /** Gradle 테스트의 작업 디렉터리는 모듈 폴더(services/clip)다. */
    private static final Path ROOT = Path.of("src", "main", "java", "com", "pokeclip", "clip", "jumpcard");

    private static final Pattern CALL = Pattern.compile("(\\.\\s*|::\\s*)(send|complete|completeWithError)\\s*(\\(|\\b)");

    /**
     * 허용 목록 — 파일 이름과 줄(앞뒤 공백 제거). 늘릴 때는 그 자리가 emitter 자물쇠를 기다려도 되는 이유를 여기 적는다.
     * <ul>
     *   <li>{@code WriteGate} 안 셋: 보내기 · 종료 알림 뒤 닫기 · 건너뜀 난 연결 닫기 — 전부 문 자물쇠를 쥔 채다</li>
     *   <li>registry의 {@code send}가 문을 부르는 한 줄</li>
     *   <li>{@code CardStreamExecutor.Job}의 {@code completeWithError} — 보내기가 <b>던진 뒤</b>라 그 쓰기는 이미
     *       emitter 자물쇠를 놓았고, 문이 그 연결을 닫힘으로 표시해 뒤 job은 emitter에 안 닿는다</li>
     * </ul>
     */
    private static final List<String> ALLOWED = List.of(
            "CardStreamRegistry.java: emitter.send(event);",
            "CardStreamRegistry.java: emitter.complete();",
            "CardStreamRegistry.java: emitter.complete();",
            "CardStreamRegistry.java: WriteGate.Result result = conn.gate().send(event, completeAfter);",
            "CardStreamExecutor.java: emitter.completeWithError(e);");

    @Test
    void emitter_자물쇠를_잡는_호출은_WriteGate_안이나_허용_목록에만_있다() throws IOException {
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(ROOT)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    String line = raw.strip();
                    if (line.startsWith("*") || line.startsWith("/*") || line.startsWith("//")) {
                        continue;
                    }
                    if (CALL.matcher(line).find()) {
                        found.add(file.getFileName() + ": " + line);
                    }
                }
            }
        }
        assertThat(found).as("jumpcard 소스를 못 읽었다 — 작업 디렉터리가 바뀌었나").isNotEmpty();
        assertThat(found)
                .as("emitter 자물쇠를 잡는 자리가 바뀌었다. registry의 send(conn, event[, completeAfter])를 거치게 하라 — "
                        + "문 밖 호출은 막힌 연결에서 약 60초를 기다린다")
                .containsExactlyInAnyOrderElementsOf(ALLOWED);
    }
}
