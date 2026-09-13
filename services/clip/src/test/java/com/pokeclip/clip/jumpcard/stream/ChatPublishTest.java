package com.pokeclip.clip.jumpcard.stream;

import com.pokeclip.clip.jumpcard.JumpCardSnapshot;
import com.pokeclip.clip.jumpcard.JumpCardSource;
import com.pokeclip.clip.jumpcard.api.ChatEventsRequest;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 수집기가 민 채팅·후원을 연결에 뿌린다(POK-234 PR-B 태스크 18). 스프링을 안 띄우고 실물 실행기를 쓴다.
 *
 * <p>🔴 <b>채팅은 카드와 다른 실행기로 간다</b>(main 결정 F4). 같은 스트라이프 큐를 쓰면 초당 수십 건의 채팅이
 * 큐를 채워 <b>멀쩡한 연결이 카드를 잃는다</b> — 카드가 제품의 핵심이다. 그래서 여기서 재는 것은 셋이다:
 * 칸이 제대로 실리는가 · 채팅이 전용 줄로 가는가 · 거부가 이벤트 수만큼 세어지고 job은 요청당 연결당 하나인가.
 */
class ChatPublishTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CardStreamExecutor cardExecutor;
    private CardStreamExecutor chatExecutor;
    private CardStreamRegistry registry;
    private final List<CountDownLatch> gates = new ArrayList<>();

    @AfterEach
    void 정리() {
        gates.forEach(CountDownLatch::countDown);
        if (registry != null) registry.stop();
        if (cardExecutor != null) cardExecutor.shutdown();
    }

    private CardStreamRegistry registry(int chatStripes, int chatCapacity) {
        cardExecutor = new CardStreamExecutor(4, 1000);
        chatExecutor = new CardStreamExecutor("chat-stream-", chatStripes, chatCapacity, false);
        StreamProperties props = new StreamProperties(Duration.ofHours(1), Duration.ofHours(1), 4, 1000, 50, 50, 500);
        registry = new CardStreamRegistry(cardExecutor, chatExecutor, props, MAPPER, d -> new Recording());
        return registry;
    }

    /**
     * SSE {@code id}=seq · {@code event}=kind · {@code data}=<b>받은 이벤트 원문 그대로</b>(seq 포함).
     * 🔴 {@code data}가 없으면 브라우저가 이벤트를 <b>버린다</b>(WHATWG 규약, POK-118 3판). clip은 칸을 해석하지
     * 않고 통과시킨다(F8) — PR-C가 칸을 더해도 clip이 칸을 새로 알 필요가 없다.
     */
    @Test
    void 채팅_이벤트는_id가_seq이고_event가_kind이고_data가_받은_원문이다() throws Exception {
        CardStreamRegistry registry = registry(4, 1000);
        Recording conn = (Recording) registry.open("s-1", "u-1", Duration.ofMinutes(1));

        CardStreamRegistry.ChatPublishResult result = registry.publishChatEvents("s-1", events("""
                {"events":[
                  {"seq":1,"seqEpoch":1757000000000,"kind":"chat","time":"2026-09-03T15:00:01.123Z","timeBasis":"message",
                   "nickname":"닉","senderChannelId":"u","role":null,"text":"ㅋㅋ","amount":null,"donationType":null},
                  {"seq":2,"seqEpoch":1757000000000,"kind":"donation","time":"2026-09-03T15:00:02Z","timeBasis":"received",
                   "nickname":"후원자","senderChannelId":"d","role":null,"text":"응원","amount":1000,"donationType":"CHAT"}
                ]}"""));

        assertThat(result.accepted()).isEqualTo(2);
        assertThat(result.dropped()).isZero();
        awaitUntil(() -> conn.events().size() == 2);
        assertThat(conn.events()).extracting(Recording.Event::name).containsExactly("chat", "donation");
        assertThat(conn.events()).extracting(Recording.Event::id).containsExactly("1", "2");
        JsonNode first = MAPPER.readTree(conn.events().get(0).data());
        assertThat(first.get("seq").asLong()).isEqualTo(1);
        assertThat(first.get("seqEpoch").asLong()).isEqualTo(1757000000000L);
        assertThat(first.get("text").asString()).isEqualTo("ㅋㅋ");
        assertThat(first.get("time").asString()).isEqualTo("2026-09-03T15:00:01.123Z");
        assertThat(first.has("role")).as("null 칸도 원문 그대로 남는다").isTrue();
        assertThat(MAPPER.readTree(conn.events().get(1).data()).get("amount").asLong()).isEqualTo(1000);
    }

    @Test
    void 그_방송의_연결에만_간다() {
        CardStreamRegistry registry = registry(4, 1000);
        Recording mine = (Recording) registry.open("s-1", "u-1", Duration.ofMinutes(1));
        Recording other = (Recording) registry.open("s-2", "u-2", Duration.ofMinutes(1));

        registry.publishChatEvents("s-1", events("{\"events\":[{\"seq\":1,\"kind\":\"chat\",\"text\":\"a\"}]}"));
        registry.publish(card("s-2"));   // 장벽: 다른 방송 연결에 카드가 도착한 뒤에 본다

        awaitUntil(() -> mine.events().size() == 1 && other.events().size() == 1);
        assertThat(other.events()).extracting(Recording.Event::name)
                .as("남의 방송 채팅이 새면 편집자가 다른 방송 반응을 본다").containsExactly("card");
    }

    /**
     * F8 — <b>모르는 kind는 묶음째 거부하지 않는다.</b> 그 이벤트만 건너뛰고 셈한다. 400으로 묶음을 거부하면
     * 수집기가 clip보다 먼저 배포되는 날(PR-C의 broadcast-info) 같은 묶음의 채팅이 전부 죽는다.
     */
    @Test
    void 모르는_kind는_건너뛰고_dropped로_세고_아는_것은_간다() {
        CardStreamRegistry registry = registry(4, 1000);
        Recording conn = (Recording) registry.open("s-1", "u-1", Duration.ofMinutes(1));

        CardStreamRegistry.ChatPublishResult result = registry.publishChatEvents("s-1", events("""
                {"events":[{"seq":1,"kind":"broadcast-info","title":"x"},{"seq":2,"kind":"chat","text":"a"}]}"""));

        assertThat(result.accepted()).isEqualTo(1);
        assertThat(result.dropped()).isEqualTo(1);
        awaitUntil(() -> conn.events().size() == 1);
        assertThat(conn.events()).extracting(Recording.Event::name).containsExactly("chat");
    }

    /** F4 — 채팅은 전용 줄({@code chat-stream-N})에서 돌고 카드는 카드 줄에서 돈다. */
    @Test
    void 채팅은_chat_stream_스레드에서_카드는_jumpcard_stream_스레드에서_보낸다() {
        CardStreamRegistry registry = registry(4, 1000);
        Recording conn = (Recording) registry.open("s-1", "u-1", Duration.ofMinutes(1));

        registry.publishChatEvents("s-1", events("{\"events\":[{\"seq\":1,\"kind\":\"chat\",\"text\":\"a\"}]}"));
        registry.publish(card("s-1"));

        awaitUntil(() -> conn.events().size() == 2);
        assertThat(conn.threadOf("chat")).startsWith("chat-stream-");
        assertThat(conn.threadOf("card")).startsWith("jumpcard-stream-");
    }

    /**
     * F4 — <b>요청 하나 = 연결당 job 하나</b>. 이벤트마다 job을 넣으면 한 요청(최대 500건)이 큐를 통째로 먹는다.
     * 줄을 붙든 채(느린 연결) 큐 한 칸짜리 실행기에 5건짜리 요청을 넣으면 job 하나라 거부가 없어야 한다.
     */
    @Test
    void 요청_하나는_연결당_job_하나라_큐를_한_칸만_쓴다() {
        CardStreamRegistry registry = registry(1, 1);
        Recording slow = (Recording) registry.open("s-1", "u-1", Duration.ofMinutes(1));
        CountDownLatch gate = slow.blockSends();
        gates.add(gate);

        registry.publishChatEvents("s-1", events("{\"events\":[{\"seq\":1,\"kind\":\"chat\",\"text\":\"붙듦\"}]}"));
        awaitUntil(slow::isBlocked);
        registry.publishChatEvents("s-1", events("""
                {"events":[{"seq":2,"kind":"chat"},{"seq":3,"kind":"chat"},{"seq":4,"kind":"chat"},
                           {"seq":5,"kind":"chat"},{"seq":6,"kind":"chat"}]}"""));

        assertThat(registry.chatDroppedCount()).as("이벤트마다 job이면 5건 중 4건이 거부된다").isZero();
        gate.countDown();
        awaitUntil(() -> slow.events().size() == 6);
        assertThat(slow.events()).extracting(Recording.Event::id).containsExactly("1", "2", "3", "4", "5", "6");
    }

    /**
     * 🔴 F4 — 채팅 큐가 차면 <b>거부된 이벤트 수만큼</b> 세고, job마다 WARN을 찍지 않으며, <b>같은 연결의 카드는
     * 여전히 간다</b>(카드 줄이 따로라서). 양성 대조: 채팅 거부가 실제로 났다({@code chatDroppedCount() > 0}).
     */
    @Test
    void 채팅_큐가_차도_거부를_이벤트_수만큼_세고_카드는_간다() {
        CardStreamRegistry registry = registry(1, 1);
        Recording slow = (Recording) registry.open("s-1", "u-1", Duration.ofMinutes(1));
        CountDownLatch gate = slow.blockChatSends();
        gates.add(gate);

        registry.publishChatEvents("s-1", events("{\"events\":[{\"seq\":1,\"kind\":\"chat\"}]}"));
        awaitUntil(slow::isBlocked);
        registry.publishChatEvents("s-1", events("{\"events\":[{\"seq\":2,\"kind\":\"chat\"}]}"));   // 큐 한 칸
        try (LogCaptor captor = new LogCaptor()) {
            registry.publishChatEvents("s-1", events("""
                    {"events":[{"seq":3,"kind":"chat"},{"seq":4,"kind":"chat"},{"seq":5,"kind":"chat"}]}"""));
            assertThat(registry.chatDroppedCount()).as("양성 대조 — 채팅 거부가 실제로 났다, 이벤트 수만큼").isEqualTo(3);
            assertThat(captor.messages()).as("채팅 거부로 job마다 WARN이 찍히면 초당 수십 줄이다")
                    .noneMatch(m -> m.startsWith("jumpcard.stream.rejected"));
        }

        registry.publish(card("s-1"));
        awaitUntil(() -> slow.events().stream().anyMatch(e -> "card".equals(e.name())));
        assertThat(slow.threadOf("card")).as("채팅 줄이 막혀도 카드는 카드 줄로 간다").startsWith("jumpcard-stream-");
    }

    @Test
    void 연결이_없는_방송인지_묻는다() {
        CardStreamRegistry registry = registry(4, 1000);
        registry.open("s-1", "u-1", Duration.ofMinutes(1));

        assertThat(registry.hasConnections("s-1")).isTrue();
        assertThat(registry.hasConnections("s-2")).isFalse();
    }

    // ------------------------------------------------------------------

    private static List<ChatEvent> events(String json) {
        return ChatEventsRequest.parse(MAPPER.readTree(json)).events();
    }

    private static JumpCardSnapshot card(String streamId) {
        return new JumpCardSnapshot(7L, streamId, JumpCardSource.AUTO, 1_500L,
                new JumpCardSnapshot.Window(1_000L, 2_000L), 97, null, null, null, null,
                false, null, 7L, Instant.parse("2026-08-23T00:00:00Z"));
    }

    private static void awaitUntil(BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("3초 안에 조건이 참이 되지 않았다");
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    /** 받은 SSE 이벤트와 <b>보낸 스레드 이름</b>을 남긴다. 붙들기 빗장으로 느린 연결을 흉내낸다. */
    static final class Recording extends SseEmitter {

        record Event(String name, String id, String data, String thread) {
        }

        private final List<Event> events = Collections.synchronizedList(new ArrayList<>());
        private volatile CountDownLatch gate;
        private volatile boolean chatOnly;
        private volatile boolean blocked;

        CountDownLatch blockSends() {
            gate = new CountDownLatch(1);
            return gate;
        }

        CountDownLatch blockChatSends() {
            chatOnly = true;
            return blockSends();
        }

        boolean isBlocked() {
            return blocked;
        }

        @Override
        public void send(SseEventBuilder builder) {
            StringBuilder raw = new StringBuilder();
            for (DataWithMediaType chunk : builder.build()) {
                raw.append(String.valueOf(chunk.getData()));
            }
            String name = null;
            String id = null;
            StringBuilder data = new StringBuilder();
            for (String line : raw.toString().split("\n")) {
                if (line.startsWith("event:")) {
                    name = line.substring("event:".length()).trim();
                } else if (line.startsWith("id:")) {
                    id = line.substring("id:".length()).trim();
                } else if (line.startsWith("data:")) {
                    data.append(line.substring("data:".length()).trim());
                }
            }
            CountDownLatch g = gate;
            if (g != null && (!chatOnly || !"card".equals(name))) {
                blocked = true;
                try {
                    g.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            events.add(new Event(name, id, data.toString(), Thread.currentThread().getName()));
        }

        List<Event> events() {
            synchronized (events) {
                return List.copyOf(events);
            }
        }

        String threadOf(String name) {
            return events().stream().filter(e -> name.equals(e.name())).map(Event::thread).findFirst().orElse("none");
        }
    }
}
