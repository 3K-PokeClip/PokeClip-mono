package com.pokeclip.chat.collector.status;

import com.pokeclip.chat.collector.ChzzkProperties;
import com.pokeclip.chat.collector.archive.ChatArchive;
import com.pokeclip.chat.collector.broadcast.EndedStream;
import com.pokeclip.chat.collector.broadcast.EndedStreamStore;
import com.pokeclip.chat.collector.fake.FakeChzzkBehavior;
import com.pokeclip.chat.collector.fake.FakeChzzkTest;
import com.pokeclip.chat.collector.persist.ChatBuffer;
import com.pokeclip.chat.collector.session.SessionKey;
import com.pokeclip.chat.collector.session.SessionRegistry;
import com.pokeclip.chat.collector.support.IntegrationTestSupport;
import com.pokeclip.chat.collector.support.TestPersistence;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 방송 번호 하나에 답 한 장을 만든다 — <b>등록부 → 메모 → unknown</b> 순서.
 * 선례는 {@code CollectorHealthTest}다(등록부를 직접 만들어 가짜 서버에 붙인다).
 *
 * <p>🔴 <b>이 검사들에서 {@code registry.onPermanentStop(...)}을 부르지 마라.</b> 등록이 아니라
 * <b>덮어쓰기</b>라 {@code new StoppedStreamRecorder(...)} 뒤에 한 번 더 부르면 레코더가 조용히
 * 떨어져 나가고, 메모가 안 남아 {@code stopped} 대신 {@code unknown}이 온다 — 원인이 안 보이는
 * 빨간불이다(critic A2 재현, {@code SessionRegistry.onPermanentStop} javadoc).
 */
@FakeChzzkTest
class ChatCollectionStatusResolverTest extends IntegrationTestSupport {

    private static final Duration AWAIT = Duration.ofSeconds(10);
    private static final Instant 지금 = Instant.parse("2026-08-22T12:00:00Z");

    @LocalServerPort int port;
    @Autowired FakeChzzkBehavior behavior;
    @Autowired RestClient.Builder restClientBuilder;
    @Autowired EndedStreamStore store;
    @Autowired JdbcTemplate jdbc;

    private SessionRegistry registry;
    private ChatCollectionStatusResolver resolver;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("DELETE FROM chat_ended_streams");
        registry = new SessionRegistry(
                new ChzzkProperties(true, "설정-토큰-쓰면-안-된다", "http://localhost:" + port,
                        Duration.ofSeconds(5), Duration.ofMillis(200), Duration.ofSeconds(60)),
                restClientBuilder, new ChatBuffer(1_000),
                TestPersistence.disabledPersister(), ChatArchive.NONE);
        resolver = new ChatCollectionStatusResolver(registry, store, () -> 지금);
    }

    @AfterEach
    void tearDown() throws Exception {
        registry.closeAll();
        behavior.reset();
    }

    // 문항 2: since()·attempt()가 null이라는 단언은 <b>늘 null인 구현</b>에도 참이다 —
    //         그 방향은 아래 두 검사(reconnecting의 since 있음 · stopped의 since 있음)가 맡는다.
    //         여기서는 observedAt이 시계에서 온다는 것을 같이 봐서 답이 통째로 빈손이 아님을 잡는다.
    @Test
    void 붙어_있으면_collecting이고_since는_null이다() throws Exception {
        registry.open(key("s1", 1L, "tokA"), "tokA");
        awaitUntil(AWAIT, () -> behavior.isConnected("tokA"));

        ChatCollectionStatus status = resolver.resolve("s1");
        assertThat(status.state()).isEqualTo("collecting");
        assertThat(status.since()).isNull();
        assertThat(status.attempt()).isNull();
        assertThat(status.needsRelink()).isFalse();
        assertThat(status.observedAt()).isEqualTo(지금);
    }

    // 문항 1: 세션 하나로는 「다른 방송」이 없다 — A를 끊어도 B가 멀쩡한지가 이 검사의 몸통이다.
    // 문항 4: A가 reconnecting이라는 단언만으로는 상태를 나눠 쓰는 구현도 지나간다 — B를 같이 본다.
    @Test
    void A를_끊으면_A는_reconnecting이고_B는_collecting_그대로다() throws Exception {
        registry.open(key("s1", 1L, "tokA"), "tokA");
        registry.open(key("s2", 2L, "tokB"), "tokB");
        awaitUntil(AWAIT, () -> behavior.isConnected("tokA") && behavior.isConnected("tokB"));
        behavior.failSessionCreateFor("tokA", 503);   // 다시 붙으려 해도 발급이 막혀 재연결 중에 머문다
        behavior.dropConnectionFor("tokA");

        awaitUntil(AWAIT, () -> "reconnecting".equals(resolver.resolve("s1").state()));
        ChatCollectionStatus a = resolver.resolve("s1");
        // 위 awaitUntil은 기다리기만 한다 — 시한을 넘겨도 조용히 빠져나오므로 상태를 여기서 한 번 더 못박는다.
        // 없으면 매핑이 틀렸을 때 10초 뒤 <b>since가 null</b>이라는 엉뚱한 이유로 빨간불이 된다(주입 T3-1 실측).
        assertThat(a.state()).isEqualTo("reconnecting");
        assertThat(a.since()).as("끊긴 시각이 실려야 「방금」과 「10분째」가 갈린다").isNotNull();
        assertThat(a.attempt()).isGreaterThanOrEqualTo(1);
        assertThat(resolver.resolve("s2").state()).isEqualTo("collecting");
        assertThat(behavior.isConnected("tokB")).as("B의 소켓이 실제로 살아 있다").isTrue();
    }

    // 문항 2: activeCount()==0 && find().isPresent()는 <b>애초에 안 열었어도</b> 앞이 참이다 —
    //         발급이 실제로 불렸는지(authCallCount>=1)를 같이 본다.
    // 문항 5: fromMemo에서 stopped() 갈래를 지우면(늘 ended) 빨간불(확인함).
    @Test
    void 발급_401로_포기하면_등록부에서_지워진_뒤에도_stopped에_needsRelink다() throws Exception {
        // 🔴 이 뒤에 registry.onPermanentStop(...)을 또 부르지 마라 — 등록이 아니라 <b>덮어쓰기</b>라
        // 레코더가 조용히 떨어져 나가고 메모가 안 남아 stopped 대신 unknown이 온다(critic A2, 재현함).
        new com.pokeclip.chat.collector.broadcast.StoppedStreamRecorder(registry, store, () -> 지금);
        behavior.failSessionCreateFor("tokA", 401);
        registry.open(key("s1", 1L, "tokA"), "tokA");

        awaitUntil(AWAIT, () -> registry.activeCount() == 0 && store.find("s1").isPresent());
        assertThat(behavior.authCallCount()).as("발급을 시도조차 안 했으면 위 await은 공짜다")
                .isGreaterThanOrEqualTo(1);
        ChatCollectionStatus status = resolver.resolve("s1");
        assertThat(status.state()).isEqualTo("stopped");
        assertThat(status.needsRelink()).isTrue();
        assertThat(status.since()).as("포기 메모의 created_at").isEqualTo(지금);
    }

    // 문항 4: state만 보면 since를 안 싣는 구현도 통과한다 — <b>편지의 종료 시각</b>이
    //         실렸는지를 같이 본다(메모를 남긴 시각이 아니다. 둘은 다른 칸이다).
    @Test
    void 종료_메모만_있으면_ended이고_since는_편지의_종료_시각이다() throws Exception {
        Instant 종료 = Instant.parse("2026-08-22T11:30:00Z");
        store.remember("s9", 4, 종료);

        ChatCollectionStatus status = resolver.resolve("s9");
        assertThat(status.state()).isEqualTo("ended");
        assertThat(status.since()).isEqualTo(종료);
        assertThat(status.needsRelink()).isFalse();
    }

    // 문항 4: 「unknown이 나온다」만 보면 <b>칸 폭 가드를 지워도 초록</b>이다 — 129자로 표를 물어도
    //         어차피 0행이라 unknown이 나온다. 가드가 하는 일은 「묻지 않는 것」이므로 표를 묻는
    //         횟수를 세야 그것이 재어진다. 앞줄이 그 횟수의 양성 대조다(모르는 번호는 한 번 묻는다).
    // 문항 5: MAX_STREAM_ID_LENGTH 검사를 지우면 둘째 lookups 단언이 1→2로 빨간불(확인함).
    @Test
    void 모르는_방송은_unknown이고_129자_번호는_DB를_묻지_않는다() throws Exception {
        java.util.concurrent.atomic.AtomicInteger lookups = new java.util.concurrent.atomic.AtomicInteger();
        ChatCollectionStatusResolver counting = new ChatCollectionStatusResolver(registry,
                new EndedStreamStore(jdbc) {
                    @Override
                    public Optional<EndedStream> find(String streamId) {
                        lookups.incrementAndGet();
                        return super.find(streamId);
                    }
                }, () -> 지금);

        assertThat(counting.resolve("never-heard").state()).isEqualTo("unknown");
        assertThat(lookups.get()).as("모르는 번호는 표를 한 번 묻는다 — 아래 단언의 양성 대조").isEqualTo(1);

        assertThat(counting.resolve("x".repeat(129)).state()).isEqualTo("unknown");
        assertThat(lookups.get()).as("칸 폭(128)을 넘는 번호는 표에 있을 수 없다 — 묻지 않는다").isEqualTo(1);
    }

    private static SessionKey key(String streamId, long streamerId, String channelId) {
        return new SessionKey(streamId, streamerId, channelId, Instant.EPOCH.plusSeconds(streamerId));
    }
}
