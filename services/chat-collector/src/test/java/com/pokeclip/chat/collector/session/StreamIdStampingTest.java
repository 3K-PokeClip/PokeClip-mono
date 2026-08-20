package com.pokeclip.chat.collector.session;

import com.pokeclip.chat.collector.ChzzkProperties;
import com.pokeclip.chat.collector.archive.ChatArchive;
import com.pokeclip.chat.collector.fake.FakeChzzkBehavior;
import com.pokeclip.chat.collector.fake.FakeChzzkTest;
import com.pokeclip.chat.collector.persist.ChatBuffer;
import com.pokeclip.chat.collector.persist.ChatPersister;
import com.pokeclip.chat.collector.support.IntegrationTestSupport;
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
 * <b>채팅이 「그 시점의 세션이 든 방송 번호」로 찍히는가.</b>
 *
 * <p>{@code ChatPersisterTest}의 갈래들과 성격이 다르다 — 그쪽은 값을 <b>손으로 넣고</b>
 * 저장 경로만 본다. 방송 번호를 어디서 읽는지(세션이냐 공유 상태냐, 열 때냐 받을 때냐)는
 * 세션이 여럿이거나 번호가 갈아끼워질 때만 갈리므로 여기서 처음 잰다
 * ({@code .claude/skills/multi-session-test-reality} 문항 1).
 *
 * <p>바구니를 실제 {@code ChatPersister}에 물린다 — 등록부 검사들은
 * {@code disabledPersister()}를 써서 표까지 안 간다.
 */
@FakeChzzkTest
class StreamIdStampingTest extends IntegrationTestSupport {

    private static final Duration AWAIT = Duration.ofSeconds(10);

    @LocalServerPort int port;
    @Autowired FakeChzzkBehavior behavior;
    @Autowired RestClient.Builder restClientBuilder;
    @Autowired JdbcTemplate jdbc;

    private SessionRegistry registry;
    private ChatPersister persister;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM chat_messages");
    }

    @AfterEach
    void tearDown() {
        if (registry != null) registry.closeAll();
        if (persister != null) persister.close();   // 멱등
        behavior.reset();
    }

    /**
     * <b>두 방송의 채팅이 각자의 번호로 남는다.</b> 남의 번호가 찍히면 판별기가
     * 다른 방송의 반응을 자기 하이라이트 근거로 읽는다.
     */
    // 문항 1: 세션 하나면 「번호를 세션이 든다」와 「프로세스에 하나를 나눠 쓴다」가
    //         구분되지 않는다 — 하나짜리로 바꾸면 후자도 초록이다.
    // 문항 3: 동시성을 재는 갈래가 아니다. 순차로 쏴도 같은 것을 잰다 — 겹칠 필요가 없다.
    // 문항 4: 「둘 다 번호가 채워졌다」·「서로 다른 번호 둘이 있다」는 <b>바뀌어 찍혀도</b>
    //         통과한다. 본문→번호를 짝으로 본다.
    // 문항 5: 번호를 세션 밖 공유 필드에서 읽게 되돌리면 둘 다 마지막 번호로 찍혀
    //         빨간불 — 확인함(주입 Q).
    @Test
    void 두_방송의_채팅이_각자의_방송_번호로_찍힌다() throws Exception {
        givenRegistry();
        assertThat(registry.open(key("stamp-s1", 1L, "chA"), "tokA")).isTrue();
        assertThat(registry.open(key("stamp-s2", 2L, "chB"), "tokB")).isTrue();

        behavior.emitChatTo("tokA", chatJson("stamp-chA", "A방송", 1_723_610_000_001L));
        behavior.emitChatTo("tokB", chatJson("stamp-chB", "B방송", 1_723_610_000_002L));

        assertThat(awaitStreamIdOf("A방송")).isEqualTo("stamp-s1");
        assertThat(awaitStreamIdOf("B방송")).isEqualTo("stamp-s2");
    }

    /**
     * <b>번호를 갈아끼운 뒤에 온 채팅은 새 번호로 남는다.</b>
     *
     * <p>같은 스트리머가 방송을 껐다 켜면 번호만 바뀌고 세션·소켓은 그대로다
     * ({@link StreamSession#retarget}) — 닫았다 열면 그 사이 채팅이 유실되기 때문이다.
     * 그래서 세션이 번호를 <b>열 때 한 번</b> 붙들면, 새 방송의 채팅이 계속 이 소켓으로
     * 들어오면서 <b>끝난 방송 번호로</b> 기록된다.
     */
    // 문항 1: 세션 하나로도 성립하는 시나리오지만, 재는 것은 세션 수가 아니라
    //         <b>번호를 읽는 시점</b>이다. 갈아끼우기가 없으면 이 자리가 아예 안 열린다.
    // 문항 4: 「갈고 나서」만 보면 <b>이미 저장된 앞 채팅까지 새 번호로 바뀌는</b> 구현도
    //         통과한다(예: 저장 시점에 공유 「현재 번호」를 읽는 구현). 앞의 것도 같이 본다.
    // 문항 5: 번호를 세션 생성 시점에 final로 붙들게 되돌리면 「갈고나서」가 옛 번호로
    //         남아 빨간불 — 확인함(주입 R).
    @Test
    void 방송_번호를_갈아끼운_뒤의_채팅은_새_번호로_찍힌다() throws Exception {
        givenRegistry();
        registry.open(key("stamp-old", 42L, "chC"), "tok42");
        behavior.emitChatTo("tok42", chatJson("stamp-chC", "갈기전", 1_723_610_100_001L));
        // 갈아끼우기 <b>전에</b> 표까지 갔다는 것을 먼저 확인한다 — 안 그러면 아래 단언이
        // "번호를 언제 읽나"가 아니라 "언제 저장되나"에 좌우된다.
        assertThat(awaitStreamIdOf("갈기전")).isEqualTo("stamp-old");

        assertThat(registry.open(key("stamp-new", 42L, "chC"), "tok42"))
                .as("같은 스트리머의 새 방송이다 — 갈아끼우고 편지를 지운다")
                .isTrue();

        behavior.emitChatTo("tok42", chatJson("stamp-chC", "갈고나서", 1_723_610_100_002L));

        assertThat(awaitStreamIdOf("갈고나서"))
                .as("갈아낀 뒤의 채팅이 끝난 방송 번호로 남으면 판별기가 남의 방송 반응을 읽는다")
                .isEqualTo("stamp-new");
        assertThat(awaitStreamIdOf("갈기전"))
                .as("앞 방송의 채팅이 새 번호로 바뀌면 끝난 방송의 반응이 새 방송 것이 된다")
                .isEqualTo("stamp-old");
    }

    // ------------------------------------------------------------------
    // 도우미
    // ------------------------------------------------------------------

    private void givenRegistry() {
        ChatBuffer buffer = new ChatBuffer(1_000);
        persister = new ChatPersister(jdbc, buffer);
        persister.start();
        registry = new SessionRegistry(
                // 설정 토큰은 안 쓰인다 — 세션마다 자기 토큰으로 붙는다(SessionRegistryTest).
                new ChzzkProperties(true, "설정-토큰-쓰면-안-된다",
                        "http://localhost:" + port, Duration.ofSeconds(5),
                        Duration.ofMillis(200), Duration.ofSeconds(60)),
                restClientBuilder, buffer, persister, ChatArchive.NONE);
    }

    /**
     * <b>부르는 순서대로 방송 시작 시각이 늦어진다.</b> 대부분의 검사가 「먼저 연 방송 →
     * 나중 방송」 순으로 부르므로 그 의도와 맞는다. 앞뒤를 뒤집어 재는 검사는
     * {@link #keyStartedAt} 로 시각을 직접 준다.
     */
    private static SessionKey key(String streamId, long streamerId, String channelId) {
        return keyStartedAt(streamId, streamerId, channelId,
                Instant.EPOCH.plusSeconds(KEY_SEQ.incrementAndGet()));
    }

    private static SessionKey keyStartedAt(String streamId, long streamerId, String channelId,
                                           Instant startedAt) {
        return new SessionKey(streamId, streamerId, channelId, startedAt);
    }

    private static final java.util.concurrent.atomic.AtomicLong KEY_SEQ =
            new java.util.concurrent.atomic.AtomicLong();

    private static String chatJson(String channelId, String content, long messageTime) {
        return "{\"channelId\":\"" + channelId + "\",\"senderChannelId\":\"u-1\","
                + "\"content\":\"" + content + "\",\"messageTime\":" + messageTime + "}";
    }

    /**
     * 그 본문의 행이 표에 뜰 때까지 기다렸다가 방송 번호를 읽는다. 저장은
     * {@code chzzk-persist} 스레드가 1초 주기로 하므로 폴링이다.
     */
    private String awaitStreamIdOf(String content) throws Exception {
        awaitUntil(AWAIT, () -> rowsOf(content) == 1);
        assertThat(rowsOf(content))
                .as("바늘이 표까지 안 갔다 — 아래 단언은 배선이 아니라 지연을 보는 것이 된다")
                .isEqualTo(1);
        return jdbc.queryForObject(
                "SELECT stream_id FROM chat_messages WHERE content = ?", String.class, content);
    }

    private long rowsOf(String content) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM chat_messages WHERE content = ?", Long.class, content);
    }
}
