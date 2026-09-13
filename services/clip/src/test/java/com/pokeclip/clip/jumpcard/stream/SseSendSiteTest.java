package com.pokeclip.clip.jumpcard.stream;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * emitter에 <b>쓰는 자리가 {@code WriteGate} 안 하나</b>인지 센다(POK-234 태스크 19).
 *
 * <p><b>왜 기계가 세나.</b> 막힌 연결 건너뛰기는 쓰는 자리 <b>전부</b>가 그 문을 지나야 뜻이 있다 — 하나라도
 * {@code emitter.send}를 직접 부르면 그 job이 막힌 연결의 emitter 자물쇠에서 약 60초를 기다리고 같은 스트라이프가
 * 통째로 막힌다. 이번에 고친 자리가 다섯(초기 전송 둘 · 카드 · 종료 알림 · ping · 채팅)이었고, 이 프로젝트에서
 * 「같은 뿌리인데 한 자리만 고쳤다」가 세션마다 났다. 그래서 자리를 사람이 세지 않는다.
 *
 * <p>🔴 <b>못 재는 것 — 「없다」가 아니라 「안 본다」.</b> 주석 줄(javadoc·{@code //})은 뺀다. 같은 줄에 코드와 주석이
 * 섞인 경우나 다른 클래스에서 emitter에 쓰는 경우는 안 본다(지금 {@code jumpcard/stream} 밖에서 emitter에 쓰는 코드는
 * grep으로 0건이었다 — 2026-09-13).
 */
class SseSendSiteTest {

    /** Gradle 테스트의 작업 디렉터리는 모듈 폴더(services/clip)다. */
    private static final Path SOURCE = Path.of(
            "src", "main", "java", "com", "pokeclip", "clip", "jumpcard", "stream", "CardStreamRegistry.java");

    @Test
    void emitter에_쓰는_자리는_WriteGate_안_하나다() throws IOException {
        List<String> code = Files.readAllLines(SOURCE, StandardCharsets.UTF_8).stream()
                .map(String::strip)
                .filter(line -> !line.startsWith("*") && !line.startsWith("/*") && !line.startsWith("//"))
                .toList();
        assertThat(code).as("CardStreamRegistry.java를 못 읽었다 — 작업 디렉터리가 바뀌었나").isNotEmpty();

        List<String> sends = code.stream().filter(line -> line.contains(".send(")).toList();
        assertThat(sends)
                .as("emitter에 직접 쓰는 자리가 늘었다. registry의 send(conn, event)를 거치게 하라 — "
                        + "안 거친 job은 막힌 연결에서 약 60초를 기다린다")
                .containsExactly("emitter.send(event);", "if (conn.gate().send(conn.emitter(), event)) {");
    }
}
