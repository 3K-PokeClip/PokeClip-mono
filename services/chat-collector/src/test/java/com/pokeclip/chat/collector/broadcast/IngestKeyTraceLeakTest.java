package com.pokeclip.chat.collector.broadcast;

import ch.qos.logback.classic.Level;
import com.pokeclip.chat.collector.ChatLogLeakTest;
import com.pokeclip.chat.collector.fake.FakeChzzkTest;
import com.pokeclip.chat.collector.support.IntegrationTestSupport;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 🔴 <b>방송의 물리 키(지금은 스트림키와 같은 값)가 DB 로거로 새는지 잰다</b>(POK-409). 이 서버는 방송 번호 → 물리 키 표
 * ({@code chat_ingest_keys})를 JdbcTemplate으로 쓰고 읽는다. 매개변수 값을 찍는 로거 둘({@code org.springframework.jdbc} ·
 * {@code org.postgresql})은 채팅 본문 때문에 이미 info로 박혀 있고({@code ChatLogLeakTest}), 이 검사는 그 막이가 물리 키
 * 길에도 걸리는지를 같은 탐지기로 본다.
 *
 * <p>대조 갈래: 두 로거를 TRACE로 풀면 같은 쓰기 · 읽기가 실제로 키를 찍는다. 안 찍으면 막는 갈래는 아무것도 안 잰다.
 */
@FakeChzzkTest
class IngestKeyTraceLeakTest extends IntegrationTestSupport {

    private static final List<String> PINNED = List.of("org.springframework.jdbc", "org.postgresql");

    @Autowired IngestKeyStore store;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void 지운다() {
        jdbc.update("DELETE FROM chat_ingest_keys WHERE stream_id LIKE 'S-leak-%'");
    }

    @Test
    void root_TRACE에서_물리_키를_쓰고_읽어도_로그에_안_남는다() {
        String key = "leak-ingest-" + UUID.randomUUID();

        String probe = "probe-" + UUID.randomUUID();
        try (LogCaptor captor = new LogCaptor()) {
            atRootTrace(() -> {
                쓰고_읽는다("S-leak-1", key);
                // 캡터 자기검사: 박히지 않은 로거의 TRACE 줄이 잡혀야 root가 실제로 내려갔다(이 길은 박힌 로거만 지나 다른 줄이 없다)
                LoggerFactory.getLogger("com.pokeclip.leakprobe").trace("{}", probe);
            });
            assertThat(rendered(captor)).as("root TRACE가 안 걸렸다면 아래 부정 단언은 아무것도 안 본 것이다").contains(probe);
            ChatLogLeakTest.assertNoSecretsIn(rendered(captor), List.of(key));
        }
    }

    @Test
    void 대조_두_로거를_풀면_같은_길이_물리_키를_찍는다() {
        String key = "leak-ingest-" + UUID.randomUUID();
        Level jdbcBefore = ChatLogLeakTest.levelOf(PINNED.get(0));
        Level pgBefore = ChatLogLeakTest.levelOf(PINNED.get(1));

        try (LogCaptor captor = new LogCaptor()) {
            PINNED.forEach(name -> ChatLogLeakTest.setLevel(name, Level.TRACE));
            try {
                atRootTrace(() -> 쓰고_읽는다("S-leak-2", key));
            } finally {
                ChatLogLeakTest.setLevel(PINNED.get(0), jdbcBefore);
                ChatLogLeakTest.setLevel(PINNED.get(1), pgBefore);
            }
            assertThat(rendered(captor)).as("풀어도 키를 안 찍는다면 라이브러리가 바뀌었다: 위 갈래는 이제 아무것도 안 잰다")
                    .contains(key);
        }
    }

    private void 쓰고_읽는다(String streamId, String key) {
        store.remember(streamId, key);
        assertThat(store.keyOf(streamId)).isEqualTo(key);
    }

    private static String rendered(LogCaptor captor) {
        return ChatLogLeakTest.renderAll(captor);
    }

    private static void atRootTrace(Runnable body) {
        Level rootBefore = ChatLogLeakTest.levelOf(Logger.ROOT_LOGGER_NAME);
        ChatLogLeakTest.setLevel(Logger.ROOT_LOGGER_NAME, Level.TRACE);
        try {
            body.run();
        } finally {
            ChatLogLeakTest.setLevel(Logger.ROOT_LOGGER_NAME, rootBefore);
        }
    }
}
