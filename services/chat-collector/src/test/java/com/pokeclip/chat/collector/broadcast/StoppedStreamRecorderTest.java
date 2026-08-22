package com.pokeclip.chat.collector.broadcast;

import com.pokeclip.chat.collector.ChzzkProperties;
import com.pokeclip.chat.collector.StopReason;
import com.pokeclip.chat.collector.archive.ChatArchive;
import com.pokeclip.chat.collector.fake.FakeChzzkBehavior;
import com.pokeclip.chat.collector.fake.FakeChzzkTest;
import com.pokeclip.chat.collector.persist.ChatBuffer;
import com.pokeclip.chat.collector.session.SessionKey;
import com.pokeclip.chat.collector.session.SessionRegistry;
import com.pokeclip.chat.collector.support.IntegrationTestSupport;
import com.pokeclip.chat.collector.support.TestPersistence;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 포기한 방송이 <b>두 길 다</b> 메모로 남는가. 창구(POK-128)가 「중단」을 「없음」과 가르는 근거다.
 *
 * <p><b>길이 둘이다.</b> ⓐ 붙어 있다가 끊긴 뒤 재연결에서 영구 사유 → {@code stopOne} ·
 * ⓑ <b>첫 수립</b>에서 영구 사유 → {@code SessionRegistry.open()}의 catch. ⓑ에는 원래 알림이
 * 없었다 — 빼먹으면 첫 발급 401인 방송은 영영 {@code unknown}이고, 시작 편지는 회차마다
 * 영원히 다시 온다.
 *
 * <p><b>다중 세션 문항</b>({@code .claude/skills/multi-session-test-reality}) — 이 검사는
 * 「세션 하나가 포기하는 두 길」을 재므로 <b>문항 1(세션 하나로 돌려도 통과하는가)은 잴 대상이
 * 없다</b>. 재 보지 않은 것이 아니라 다중화가 이 검사의 주제가 아니다. 나머지 넷은 갈래마다
 * 주석으로 답을 남겼다.
 */
@FakeChzzkTest
class StoppedStreamRecorderTest extends IntegrationTestSupport {

    private static final Duration AWAIT = Duration.ofSeconds(10);
    private static final Instant 지금 = Instant.parse("2026-08-22T12:00:00Z");

    @LocalServerPort int port;
    @Autowired FakeChzzkBehavior behavior;
    @Autowired RestClient.Builder restClientBuilder;
    @Autowired EndedStreamStore store;
    @Autowired JdbcTemplate jdbc;

    private SessionRegistry registry;

    @BeforeEach
    void 표를_비운다() throws Exception {
        jdbc.update("DELETE FROM chat_ended_streams");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (registry != null) registry.closeAll();
        behavior.reset();
    }

    // 길 ⓐ — 붙어 있다가 끊긴 뒤 재연결 발급이 401. stopOne을 탄다.
    // 문항 4: 메모가 「첫 수립 401」 경로로 남아도 이 단언은 통과한다 — 소켓이 실제로 한 번
    //         붙었는지(connectionCount>=1)를 같이 봐야 재연결 경로를 탔다는 뜻이 된다.
    // 문항 5: stopOne의 알림 한 줄을 지우면 메모가 영영 안 남아 빨간불.
    @Test
    void 재연결_발급이_401이면_포기_메모가_남는다() throws Exception {
        registry = newRegistry();
        new StoppedStreamRecorder(registry, store, () -> 지금);
        registry.open(new SessionKey("s1", 1L, "chA", Instant.EPOCH), "tokA");
        awaitUntil(AWAIT, () -> behavior.isConnected("tokA"));
        assertThat(behavior.connectionCount())
                .as("소켓이 한 번은 붙어야 재연결 경로다 — 안 붙었으면 이 검사는 길 ⓑ를 재고 있다")
                .isGreaterThanOrEqualTo(1);
        behavior.failSessionCreateFor("tokA", 401);
        behavior.dropConnectionFor("tokA");

        awaitUntil(AWAIT, () -> store.find("s1").isPresent());
        EndedStream memo = store.find("s1").orElseThrow();
        assertThat(memo.stopReason()).isEqualTo(StopReason.SESSION_AUTH_REJECTED.name());
        assertThat(memo.createdAt()).isEqualTo(지금);
        assertThat(memo.lastSequence()).isZero();
        awaitUntil(AWAIT, () -> registry.activeCount() == 0);
        assertThat(registry.activeCount()).isZero();
    }

    // 길 ⓑ — 첫 수립에서 발급이 401. stopOne이 아니라 open()의 catch다. 거기서도 알려야 한다.
    // 문항 5: open() catch의 알림을 지우면 메모가 안 남아 빨간불 (2026-08-22 코드 확인: 지금은 안 남는다).
    @Test
    void 첫_발급이_401이면_포기_메모가_남는다() throws Exception {
        registry = newRegistry();
        new StoppedStreamRecorder(registry, store, () -> 지금);
        behavior.failSessionCreateFor("tokA", 401);

        assertThat(registry.open(new SessionKey("s1", 1L, "chA", Instant.EPOCH), "tokA")).isFalse();

        EndedStream memo = store.find("s1").orElseThrow();   // open()이 동기라 기다릴 것이 없다
        assertThat(memo.stopReason()).isEqualTo(StopReason.SESSION_AUTH_REJECTED.name());
        assertThat(registry.activeCount()).isZero();
    }

    // 첫 발급 503은 포기가 아니다. 메모가 남으면 그 방송의 재전송 편지가 낡은 것으로 지워져 영영 안 붙는다.
    // 문항 2: find().isEmpty()는 <b>애초에 아무 일도 안 일어났을 때도 참</b>이다 —
    //         발급이 실제로 불렸는지(authCallCount>=1)를 먼저 본다.
    @Test
    void 첫_발급이_503이면_메모가_남지_않는다() throws Exception {
        registry = newRegistry();
        new StoppedStreamRecorder(registry, store, () -> 지금);
        behavior.failSessionCreateFor("tokA", 503);

        assertThat(registry.open(new SessionKey("s1", 1L, "chA", Instant.EPOCH), "tokA")).isFalse();

        assertThat(behavior.authCallCount()).as("발급을 시도조차 안 했으면 아래 isEmpty는 공짜다")
                .isGreaterThanOrEqualTo(1);
        assertThat(store.find("s1")).isEmpty();
    }

    // 문항 4: 「메모가 안 남았다」만 단언하면 알림 자체가 없는 구현도 초록이다 — open이 false로 끝나고 경고가 남는지를 같이 본다.
    @Test
    void 메모_저장이_던져도_등록부는_멀쩡하고_경고만_남는다() throws Exception {
        registry = newRegistry();
        // 모든 update가 던지는 저장소. TestPersistence.rejecting22는 넷째 인자를 채팅 본문으로
        // 캐스팅하는 적재 전용 헬퍼라 여기 쓰면 우연에 기댄다 — 직접 만든다.
        EndedStreamStore broken = new EndedStreamStore(new JdbcTemplate(jdbc.getDataSource()) {
            @Override
            public int update(String sql, Object... args) {
                throw new org.springframework.dao.DataAccessResourceFailureException(
                        "jdbc:postgresql://secret-host:5432/pokeclip 연결 실패");   // 메시지에 주소가 있다 — 로그에 안 실려야 한다
            }
        });
        new StoppedStreamRecorder(registry, broken, () -> 지금);
        behavior.failSessionCreateFor("tokA", 401);

        try (LogCaptor captor = new LogCaptor()) {
            assertThat(registry.open(new SessionKey("s1", 1L, "chA", Instant.EPOCH), "tokA"))
                    .as("리스너가 던져도 open은 정상으로 false를 돌려준다").isFalse();
            assertThat(captor.messages())
                    .anyMatch(m -> m.startsWith("chat.broadcast.stopped_memo_failed"))
                    .as("예외 메시지(DB 주소)가 로그에 통째로 실리면 안 된다 — 타입 이름만")
                    .noneMatch(m -> m.contains("secret-host"));
            assertThat(captor.events())
                    .as("throwable을 붙이면 스택트레이스가 통째로 나간다(POK-127 봇 지적과 같은 모양)")
                    .noneMatch(e -> e.getThrowableProxy() != null
                            && e.getFormattedMessage().contains("stopped_memo_failed"));
        }
        assertThat(registry.activeCount()).isZero();
        assertThat(store.find("s1")).as("진짜 표에는 안 남는다 — 이것이 「알려진 한계」다").isEmpty();
    }

    private SessionRegistry newRegistry() {
        return new SessionRegistry(
                new ChzzkProperties(true, "설정-토큰-쓰면-안-된다", "http://localhost:" + port,
                        Duration.ofSeconds(5), Duration.ofMillis(200), Duration.ofSeconds(60)),
                restClientBuilder, new ChatBuffer(1_000),
                TestPersistence.disabledPersister(), ChatArchive.NONE);
    }
}
