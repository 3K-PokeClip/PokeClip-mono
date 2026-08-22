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

        // 🔴 <b>상태만 기다리면 안 된다 — attempt는 그보다 늦게 오른다.</b> 절단 콜백이 락 안에서
        // status.reconnecting(…, status.attempt())로 <b>0</b>을 먼저 찍고(StreamSession:718),
        // attempt++는 재연결 스레드가 cleanUpOnce(구독 반납 REST + 소켓 닫기)를 <b>끝낸 뒤</b>다(:803).
        // 그 사이가 「reconnecting인데 attempt=0」 창이고 <b>매번 지나간다</b> — 촘촘히 폴링하면 20/20으로
        // 잡힌다(2026-08-23 프로브). 평소 초록인 것은 창이 awaitUntil의 20ms 간격보다 좁아서일 뿐이라,
        // 부하가 걸린 모듈 전체 실행에서 폴링이 창 안에 떨어져 <b>아래 attempt 단언이 실제로 빨간불이 났다</b>
        // (0 >= 1 실패). 그래서 <b>단언할 것을 그대로 기다린다.</b> 상품 쪽은 안 고친다 — 뒷정리 중에
        // 「아직 0회 시도」라고 답하는 것은 거짓이 아니고, 배너는 state로 켜진다.
        awaitUntil(AWAIT, () -> {
            ChatCollectionStatus s = resolver.resolve("s1");
            return "reconnecting".equals(s.state()) && s.attempt() != null && s.attempt() >= 1;
        });
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

    // <b>ⓐ(재연결 중 포기)의 「메모가 남기 전 찰나」를 재는 유일한 검사다</b> — 앞 구현자가 넘긴 공백이었다.
    // 그 자리는 fromLive의 case STOPPED이고, 위 「발급_401로 …」 검사는 <b>메모가 남은 뒤</b>를 보므로
    // 이 갈래를 통째로 지워도(=default와 합쳐도) 초록이었다.
    //
    // 🔴 <b>레코더를 걸지 않는다.</b> onPermanentStop은 등록이 아니라 덮어쓰기라, latch 리스너와
    // new StoppedStreamRecorder(...)를 둘 다 걸면 <b>레코더가 조용히 떨어져 나간다</b>(critic A2).
    // 이 검사가 재는 것은 메모가 아니라 「메모를 남기기 전」이므로 애초에 레코더가 필요 없다 —
    // 없는 편이 오히려 정확하다. 아래 store.find(...).isEmpty()가 그것을 못박는다.
    //
    // 문항 1: 다중화가 주제가 아니다 — 세션 하나로 재는 것이 맞다(찰나 하나를 붙드는 검사다).
    // 문항 2: memoStarted.await()가 true라는 것이 「알림이 실제로 갔다」의 양성 대조다 —
    //         그것 없이 stopped를 단언하면 아무 일도 안 일어난 경우와 구분이 안 된다.
    // 문항 3: 리스너를 붙드는 스레드는 그 세션의 재연결 루프(가상 스레드)다. memoStarted가 풀린 시점에
    //         그 스레드는 notifyPermanentStop 안에 있고, 단언은 그동안 다른 스레드에서 돈다.
    // 문항 4: 「stopped + needsRelink + since==지금」은 <b>메모 경로로도 통과한다</b>(그 검사의 레코더
    //         시계가 같은 「지금」이다) — store.find("s1")가 비어 있음을 같이 단언해 등록부를 읽었음을 못박는다.
    // 문항 5: fromLive의 case STOPPED를 default로 합치면 since=null·needsRelink=false로 빨간불(확인함).
    @Test
    void 포기_메모가_남기_전_찰나에도_stopped이고_since는_지금이다() throws Exception {
        java.util.concurrent.CountDownLatch memoStarted = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch memoRelease = new java.util.concurrent.CountDownLatch(1);
        registry.onPermanentStop((streamId, reason) -> {
            memoStarted.countDown();
            try {
                memoRelease.await(10, java.util.concurrent.TimeUnit.SECONDS);   // 메모를 쓰는 동안을 흉내낸다
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        registry.open(key("s1", 1L, "tokA"), "tokA");
        awaitUntil(AWAIT, () -> behavior.isConnected("tokA"));
        behavior.failSessionCreateFor("tokA", 401);   // 다시 붙으려는 발급이 거절 — ⓐ 포기
        behavior.dropConnectionFor("tokA");
        assertThat(memoStarted.await(10, java.util.concurrent.TimeUnit.SECONDS))
                .as("알림이 실제로 갔다 — 아래 단언들의 양성 대조").isTrue();
        try {
            ChatCollectionStatus status = resolver.resolve("s1");
            assertThat(status.state()).isEqualTo("stopped");
            assertThat(status.needsRelink()).as("SESSION_AUTH_REJECTED는 다시 연동해야 풀린다").isTrue();
            assertThat(status.since()).as("메모가 아직 없으니 포기 시각은 「지금」이다 — 메모 경로와 갈리는 지점")
                    .isEqualTo(status.observedAt()).isEqualTo(지금);
            assertThat(status.attempt()).as("재연결 중이 아니다").isNull();
            assertThat(store.find("s1")).as("메모 경로가 아니라 등록부를 읽었다").isEmpty();
        } finally {
            memoRelease.countDown();
        }
    }

    private static SessionKey key(String streamId, long streamerId, String channelId) {
        return new SessionKey(streamId, streamerId, channelId, Instant.EPOCH.plusSeconds(streamerId));
    }
}
