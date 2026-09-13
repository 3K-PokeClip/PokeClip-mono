package com.pokeclip.chat.collector.session;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 후원 상태를 <b>등록부에 쓰는 자리가 하나</b>인지 센다.
 *
 * <p><b>왜 기계가 세나.</b> 이 세션에서 <b>「같은 뿌리인데 한 자리만 고쳤다」가 다섯 번</b>
 * 났고, 그중 둘이 바로 이 자리다 — 갈아끼움과 겹치는 쓰기를 막으려고 자물쇠를 걸었는데
 * 처음엔 재시도 콜백만 묶었고 <b>회수 프레임이 그대로 남았다</b>(봇 codex). 자리를 셋 두고
 * 「전부 묶었나」를 사람이 세는 방식은 이미 네 번 틀렸다. 그래서 <b>세는 일을 여기로 옮겼다.</b>
 *
 * <p>회수가 갈아끼움과 겹치면 새 방송이 앞 방송의 「구독 중」을 그대로 안고 가고,
 * 회수는 재시도를 깨우지 않으므로 그 방송은 <b>끝날 때까지</b> 틀린 값을 창구에 준다.
 *
 * <p>🔴 <b>이 검사가 못 재는 것 둘 — 「없다」가 아니라 「안 본다」이다.</b>
 * <ul>
 *   <li><b>그 한 자리가 자물쇠 안인지는 안 본다.</b> {@code synchronized} 를 지워도 초록이다.
 *       구조를 정규식으로 재려다 취약한 검사를 만드느니, <b>자리 수</b>만 잰다 —
 *       틀린 다섯 번이 전부 「자리를 놓쳤다」이지 「자물쇠를 잘못 썼다」가 아니었다.</li>
 *   <li><b>경합 자체는 안 열린다.</b> 소스를 읽는 검사라 실행 순서와 무관하다.</li>
 * </ul>
 */
class DonationStatusWriteSiteTest {

    /** Gradle 테스트의 작업 디렉터리는 모듈 폴더(services/chat-collector)다. */
    private static final Path SOURCE = Path.of(
            "src", "main", "java", "com", "pokeclip", "chat", "collector", "session", "StreamSession.java");

    @Test
    void 후원_상태를_쓰는_자리는_하나다() throws IOException {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);
        assertThat(source).as("StreamSession.java를 못 읽었다 — 작업 디렉터리가 바뀌었나").isNotBlank();

        int writes = source.split("donations\\.set\\(", -1).length - 1;
        assertThat(writes)
                .as("후원 상태를 쓰는 자리가 늘었다. setDonationStatus 를 거치게 하라 — "
                        + "자리마다 자물쇠를 거는 방식은 이 세션에서 다섯 번 한 자리를 놓쳤다")
                .isEqualTo(1);
        assertThat(source)
                .as("그 한 자리가 setDonationStatus 안에 없다")
                .contains("private void setDonationStatus(");
    }
}
