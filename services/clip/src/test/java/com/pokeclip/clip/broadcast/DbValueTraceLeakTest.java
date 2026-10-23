package com.pokeclip.clip.broadcast;

import ch.qos.logback.classic.Level;
import com.pokeclip.clip.delegation.AuthClientTokenLeakTest;
import com.pokeclip.clip.support.IntegrationTestSupport;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 🔴 <b>방송의 물리 키(지금은 스트림키와 같은 값)가 DB 로거로 새는지 잰다</b>(POK-409).
 *
 * <p>표적은 우리 코드가 아니라 {@code application.yml}의 {@code logging.level} 세 줄이다. root를 TRACE로 올리면
 * DB를 오가는 값을 찍는 로거가 넷이다(실측): {@code org.hibernate.orm.jdbc.extract}(읽은 칸 값) ·
 * {@code org.hibernate.orm.core}(DEBUG에서 엔티티 통째) · {@code org.springframework.jdbc.core.StatementCreatorUtils}
 * (JdbcTemplate 매개변수) · {@code org.postgresql.core.v3.QueryExecutorImpl}(보내는 값). 세 트리를 info로 박으면 다 막힌다.
 *
 * <p><b>대조 갈래가 있어야 이 검사가 무언가를 잰다.</b> 세 트리를 TRACE로 풀면 같은 읽기가 실제로 키를 찍는다는 것을
 * 먼저 보인다. 그게 안 나오면(라이브러리가 조용해졌다) 막는 갈래는 아무것도 안 보고 초록이 된다.
 */
class DbValueTraceLeakTest extends IntegrationTestSupport {

    /** yml이 박는 트리. 하나라도 빠지면 위 넷 중 하나가 샌다. */
    private static final List<String> PINNED = List.of("org.hibernate.orm", "org.springframework.jdbc", "org.postgresql");

    private final BroadcastRepository broadcasts;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    DbValueTraceLeakTest(BroadcastRepository broadcasts, JdbcTemplate jdbc, TransactionTemplate tx) {
        this.broadcasts = broadcasts;
        this.jdbc = jdbc;
        this.tx = tx;
    }

    @BeforeEach
    @AfterEach
    void 방송을_비운다() {
        방송과_카드를_비운다(jdbc);
    }

    @Test
    void 세_트리가_yml에서_info로_박혀_있다() {
        for (String name : PINNED) {
            assertThat(AuthClientTokenLeakTest.levelOf(name)).as("%s가 yml에서 info로 안 박혔다", name).isEqualTo(Level.INFO);
        }
    }

    @Test
    void root_TRACE에서_방송을_읽어도_물리_키가_로그에_안_남는다() {
        String key = 방송을_심는다();

        String probe = "probe-" + UUID.randomUUID();
        try (LogCaptor captor = new LogCaptor()) {
            atRootTrace(() -> {
                세_길로_읽는다(key);
                // 캡터 자기검사: 박히지 않은 로거의 TRACE 줄이 잡혀야 root가 실제로 내려갔다
                LoggerFactory.getLogger("com.pokeclip.leakprobe").trace("{}", probe);
            });
            assertThat(captor.messages()).as("root TRACE가 안 걸렸다면 아래 부정 단언은 아무것도 안 본 것이다").contains(probe);
            AuthClientTokenLeakTest.assertNoSecretsIn(captor, List.of(key));
        }
    }

    /** 대조: 세 트리를 풀면 같은 읽기가 실제로 키를 찍는다. 그래야 위 갈래가 「막아서 안 나온다」를 잰다. */
    @Test
    void 대조_세_트리를_풀면_같은_읽기가_물리_키를_찍는다() {
        String key = 방송을_심는다();
        Map<String, Level> before = new LinkedHashMap<>();
        PINNED.forEach(name -> before.put(name, AuthClientTokenLeakTest.levelOf(name)));

        try (LogCaptor captor = new LogCaptor()) {
            PINNED.forEach(name -> AuthClientTokenLeakTest.setLevel(name, Level.TRACE));
            try {
                atRootTrace(() -> 세_길로_읽는다(key));
            } finally {
                before.forEach(AuthClientTokenLeakTest::setLevel);
            }
            assertThat(captor.messages()).as("풀어도 키를 안 찍는다면 라이브러리가 바뀌었다: 위 갈래는 이제 아무것도 안 잰다")
                    .anySatisfy(m -> assertThat(m).contains(key));
        }
    }

    private String 방송을_심는다() {
        String key = "leak-ingest-" + UUID.randomUUID();
        jdbc.update("INSERT INTO broadcasts (stream_id, streamer_id, status, ingest_stream_id, last_sequence) "
                + "VALUES ('S-leak', 'u', 'live', ?, 1)", key);
        return key;
    }

    /** 이 서버가 방송을 읽는 길 셋: JPA 엔티티 · 네이티브 칸 하나 · JdbcTemplate 매개변수. */
    private void 세_길로_읽는다(String key) {
        tx.executeWithoutResult(s -> broadcasts.findByStreamId("S-leak").orElseThrow());
        assertThat(broadcasts.findIngestKeyByStreamId("S-leak")).contains(key);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM broadcasts WHERE ingest_stream_id = ?", Integer.class, key))
                .isEqualTo(1);
    }

    private static void atRootTrace(Runnable body) {
        Level rootBefore = AuthClientTokenLeakTest.levelOf(Logger.ROOT_LOGGER_NAME);
        AuthClientTokenLeakTest.setLevel(Logger.ROOT_LOGGER_NAME, Level.TRACE);
        try {
            body.run();
        } finally {
            AuthClientTokenLeakTest.setLevel(Logger.ROOT_LOGGER_NAME, rootBefore);
        }
    }
}
