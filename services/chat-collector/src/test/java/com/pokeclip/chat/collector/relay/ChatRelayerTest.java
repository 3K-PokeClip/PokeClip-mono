package com.pokeclip.chat.collector.relay;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 중계 스레드 하나가 바구니를 비워 clip에 민다. 재시도는 없다 — 실패는 버리고 센다.
 *
 * <p>🔴 <b>고정 대기를 쓰지 않는다</b>(A 교훈). 「묶였다」는 가짜 clip이 첫 요청을 <b>붙든 채</b>
 * 뒤를 넣고 놓아 결정적으로 만든다(F13) — HTTP 지연 운에 기대면 느린 기계에서만 초록이다.
 */
class ChatRelayerTest {

    private static final Duration AWAIT = Duration.ofSeconds(10);

    private FakeClipRelay clip;
    private RelayBuffer buffer;
    private ChatRelayer relayer;

    @BeforeEach
    void setUp() {
        clip = FakeClipRelay.start();
        buffer = new RelayBuffer(10_000);
        relayer = new ChatRelayer(buffer, client(), properties());
        relayer.start();
    }

    @AfterEach
    void tearDown() {
        relayer.beginClose();
        clip.close();
        relayer.awaitClosed(Duration.ofSeconds(3));
    }

    /**
     * F13 — 보내는 동안 쌓인 것은 다음 요청 <b>하나</b>로 간다. 한 건씩 보내면 clip이 느릴 때
     * 요청 수가 채팅 수만큼 늘고, 스레드가 하나라 지연이 그대로 쌓인다.
     */
    @Test
    void 첫_요청을_붙든_사이_쌓인_것은_둘째_요청_하나로_간다() throws Exception {
        clip.holdFirstRequest();
        buffer.offer("s-batch", chat("first"));
        await(() -> clip.callCount() == 1);
        assertThat(clip.callCount()).as("첫 요청이 가짜에 닿아 붙들린 뒤에 넣어야 묶음을 잰다").isEqualTo(1);

        for (int i = 0; i < 99; i++) {
            buffer.offer("s-batch", chat("m" + i));
        }
        clip.releaseFirstRequest();
        await(() -> relayer.relayed() == 100);

        assertThat(clip.callCount()).isEqualTo(2);
        assertThat(eventsOf(1)).hasSize(99);
        assertThat(eventsOf(1).getFirst().get("seq").asLong()).isEqualTo(2);
        assertThat(eventsOf(1).getLast().get("seq").asLong()).isEqualTo(100);
    }

    /** 문항 5 — {@code relayDropped}가 clip 실패 때문인지 바구니 상한 때문인지 가른다: 둘은 다른 셈이다. */
    @Test
    void 성공은_relayed로_실패와_모르는_방송은_relayDropped로_센다() throws Exception {
        // 시작 전에 둘을 넣어 한 요청으로 묶이게 한다 — 요청 수를 정확히 세려고.
        relayer.beginClose();
        relayer.awaitClosed(AWAIT);
        buffer = new RelayBuffer(10_000);
        relayer = new ChatRelayer(buffer, client(), properties());
        buffer.offer("s-ok", chat("a"));
        buffer.offer("s-ok", chat("b"));
        relayer.start();
        await(() -> relayer.relayed() == 2);

        clip.respondWith(404);
        buffer.offer("s-unknown", chat("c"));
        await(() -> relayer.relayDropped() == 1);

        clip.respondWith(500);
        buffer.offer("s-fail", chat("d"));
        await(() -> relayer.relayDropped() == 2);

        assertThat(relayer.relayed()).isEqualTo(2);
        assertThat(relayer.relayDropped()).isEqualTo(2);
        assertThat(relayer.bufferDropped()).as("clip 실패는 바구니 셈에 안 섞인다").isZero();
        assertThat(clip.requests()).extracting(FakeClipRelay.Received::path)
                .as("재시도가 없다 — 실패한 방송도 요청은 한 번씩")
                .containsExactly("/internal/broadcasts/s-ok/chat-events",
                        "/internal/broadcasts/s-unknown/chat-events",
                        "/internal/broadcasts/s-fail/chat-events");
    }

    @Test
    void 바구니_상한_초과는_bufferDropped로_센다() {
        RelayBuffer tiny = new RelayBuffer(1);
        ChatRelayer notStarted = new ChatRelayer(tiny, client(), properties());
        tiny.offer("s", chat("1"));
        tiny.offer("s", chat("2"));

        assertThat(notStarted.bufferDropped()).isEqualTo(1);
    }

    @Test
    void 방송별로_묶되_처음_나온_순서대로_보낸다() throws Exception {
        clip.holdFirstRequest();
        buffer.offer("s-hold", chat("hold"));
        await(() -> clip.callCount() == 1);

        buffer.offer("s-a", chat("a1"));
        buffer.offer("s-b", chat("b1"));
        buffer.offer("s-a", chat("a2"));
        clip.releaseFirstRequest();
        await(() -> relayer.relayed() == 4);

        assertThat(clip.requests()).extracting(FakeClipRelay.Received::path).containsExactly(
                "/internal/broadcasts/s-hold/chat-events",
                "/internal/broadcasts/s-a/chat-events",
                "/internal/broadcasts/s-b/chat-events");
        assertThat(eventsOf(1)).extracting(e -> e.get("text").asString()).containsExactly("a1", "a2");
        assertThat(eventsOf(2)).extracting(e -> e.get("text").asString()).containsExactly("b1");
    }

    /**
     * 닫기 모양(태스크 17이 {@code CollectorRunner.closeSinks}에 잇는다) — 새로 받지 않고,
     * 이미 담긴 것은 마저 보내고, 스레드가 끝난다.
     */
    @Test
    void 닫으면_새로_받지_않고_담긴_것은_마저_보내고_끝난다() throws Exception {
        clip.holdFirstRequest();
        buffer.offer("s-close", chat("1"));
        await(() -> clip.callCount() == 1);
        buffer.offer("s-close", chat("2"));
        buffer.offer("s-close", chat("3"));

        relayer.beginClose();
        buffer.offer("s-close", chat("after-close"));
        clip.releaseFirstRequest();
        relayer.awaitClosed(AWAIT);

        assertThat(relayer.isRunning()).as("기한 안에 비웠으면 스레드가 끝나 있어야 한다").isFalse();
        assertThat(relayer.relayed()).isEqualTo(3);
        List<String> texts = new ArrayList<>();
        for (int i = 0; i < clip.callCount(); i++) {
            eventsOf(i).forEach(e -> texts.add(e.get("text").asString()));
        }
        assertThat(texts).containsExactly("1", "2", "3");
    }

    /**
     * 기한을 넘기면 기다리지 않고 돌아오고, <b>돌아오는 순간 셈이 이미 확정</b>이다 — 판정 줄은
     * {@code awaitClosed} 바로 뒤에 셈을 읽는다(태스크 17). 🔴 L5: 한 묶음에 방송이 여럿이면 기한 뒤에도
     * 남은 방송에 계속 보내고 셈이 스레드가 끝날 때(최대 방송 수 × 1.5초 뒤)에야 닫혔다. 방송 하나로는
     * 그 결함이 안 보여 셋으로 잰다.
     */
    @Test
    void 닫기_기한을_넘기면_돌아올_때_셈이_확정이고_남은_방송에_더_안_보낸다() throws Exception {
        relayer.beginClose();
        relayer.awaitClosed(AWAIT);
        buffer = new RelayBuffer(10_000);
        relayer = new ChatRelayer(buffer, client(), properties());
        buffer.offer("s-a", chat("a"));
        buffer.offer("s-b", chat("b"));
        buffer.offer("s-c", chat("c"));
        clip.holdFirstRequest();   // 읽기 시한(1초)이 끊을 때까지 첫 방송 요청이 매달린다
        relayer.start();
        await(() -> clip.callCount() == 1);
        assertThat(clip.callCount()).as("한 묶음(방송 셋)의 첫 요청이 나가 매달린 뒤에 닫는다").isEqualTo(1);

        relayer.beginClose();
        long started = System.nanoTime();
        relayer.awaitClosed(Duration.ofMillis(100));
        Duration waited = Duration.ofNanos(System.nanoTime() - started);

        assertThat(waited).as("기한만큼만 기다리고 돌아와야 종료 예산을 안 넘는다").isLessThan(Duration.ofMillis(900));
        assertThat(relayer.relayDropped()).as("돌아온 순간 셋 다 버린 수로 확정").isEqualTo(3);
        assertThat(relayer.relayed()).isZero();

        await(() -> !relayer.isRunning());
        assertThat(relayer.isRunning()).isFalse();
        assertThat(clip.callCount()).as("기한을 넘긴 뒤에는 남은 방송에 더 보내지 않는다").isEqualTo(1);
        assertThat(relayer.relayDropped()).as("나중에 끝난 요청이 셈을 다시 바꾸지 않는다").isEqualTo(3);
        assertThat(relayer.relayed()).isZero();
        assertThat(relayer.relayOffered())
                .isEqualTo(relayer.relayed() + relayer.relayDropped() + relayer.bufferDropped() + buffer.size());
    }

    /** L6 — 닫힌 뒤 중계 등식이 닫힌다: 넣은 수 = 보냄 + 버림(clip) + 버림(바구니) + 남은 수. */
    @Test
    void 닫힌_뒤_중계_등식이_닫힌다() throws Exception {
        clip.holdFirstRequest();
        buffer.offer("s-eq", chat("1"));
        await(() -> clip.callCount() == 1);
        clip.respondWith(500);   // 붙든 첫 요청은 풀리면 500, 뒤도 500
        buffer.offer("s-eq", chat("2"));
        clip.releaseFirstRequest();
        clip.respondWith(200);
        await(() -> relayer.relayed() + relayer.relayDropped() == 2);
        buffer.offer("s-eq2", chat("3"));

        relayer.beginClose();
        relayer.awaitClosed(AWAIT);

        assertThat(relayer.relayOffered()).as("양성 대조 — 넣은 것이 셋").isEqualTo(3);
        assertThat(relayer.relayOffered())
                .isEqualTo(relayer.relayed() + relayer.relayDropped() + relayer.bufferDropped() + buffer.size());
    }

    /**
     * 감사 L21 — 중계 스레드가 {@code Error}로 죽어도 <b>나가 있던 묶음이 버린 수로 들어가</b> 등식이 닫힌다.
     * {@code send}는 {@code RuntimeException}만 받으므로 전에는 그 묶음이 어느 셈에도 안 들어갔고,
     * {@code awaitClosed}는 죽은 스레드를 보고 곧장 돌아가 확정도 없었다.
     */
    @Test
    void 중계_스레드가_Error로_죽어도_나가_있던_묶음이_등식에_들어간다() throws Exception {
        relayer.beginClose();
        relayer.awaitClosed(AWAIT);
        buffer = new RelayBuffer(10_000);
        ClipRelayClient dying = new ClipRelayClient(RestClient.builder(), properties(), ClipRelayClientTest.link()) {
            @Override
            public Outcome send(String streamId, List<RelayEvent> events) {
                throw new LinkageError("주입 — RuntimeException이 아닌 것");
            }
        };
        buffer.offer("s-a", chat("1"));
        buffer.offer("s-a", chat("2"));
        buffer.offer("s-b", chat("3"));   // 방송 둘 — 첫 방송에서 죽으면 둘째 방송 몫도 나가 있던 채다
        relayer = new ChatRelayer(buffer, dying, properties());
        relayer.start();

        await(() -> relayer.relayDropped() == 3);
        await(() -> !relayer.isRunning());
        assertThat(relayer.isRunning()).as("양성 대조 — 스레드가 정말 죽었다").isFalse();
        relayer.beginClose();
        relayer.awaitClosed(AWAIT);

        assertThat(relayer.relayOffered()).isEqualTo(3);
        assertThat(relayer.relayDropped()).as("나가 있던 셋이 버린 수로 들어간다").isEqualTo(3);
        assertThat(relayer.relayOffered())
                .isEqualTo(relayer.relayed() + relayer.relayDropped() + relayer.bufferDropped() + buffer.size());
    }

    // ------------------------------------------------------------------

    private ClipRelayClient client() {
        // 운영 배선 그대로 — 전용 시한(1초)이 걸린 클라이언트다.
        return new RelayConfiguration.Enabled().clipRelayClient(RestClient.builder(),
                ClientHttpRequestFactoryBuilder.jdk(), properties(), ClipRelayClientTest.link());
    }

    /** PR #181 codex — 한 묶음(MAX_BATCH)을 넘게 쌓인 채 죽어도 바구니 잔량까지 버린 수로 들어가고, 죽은 뒤엔 안 받는다. */
    @Test
    void 중계_스레드가_죽으면_바구니_잔량까지_세고_더_안_받는다() throws Exception {
        relayer.beginClose();
        relayer.awaitClosed(AWAIT);
        buffer = new RelayBuffer(10_000);
        ClipRelayClient dying = new ClipRelayClient(RestClient.builder(), properties(), ClipRelayClientTest.link()) {
            @Override
            public Outcome send(String streamId, List<RelayEvent> events) {
                throw new LinkageError("주입");
            }
        };
        int total = ChatRelayer.MAX_BATCH + 7;
        for (int i = 0; i < total; i++) {
            buffer.offer("s-a", chat("m" + i));
        }
        relayer = new ChatRelayer(buffer, dying, properties());
        relayer.start();

        await(() -> !relayer.isRunning());
        buffer.offer("s-a", chat("죽은 뒤"));

        assertThat(relayer.relayDropped()).as("나가 있던 500 + 남은 7").isEqualTo(total);
        assertThat(buffer.size()).isZero();
        assertThat(relayer.relayOffered()).as("죽은 뒤 것은 안 받는다").isEqualTo(total);
    }

    private RelayProperties properties() {
        return new RelayProperties(true, 10_000, Duration.ofMillis(100), clip.baseUrl());
    }

    private List<JsonNode> eventsOf(int request) {
        List<JsonNode> out = new ArrayList<>();
        clip.requests().get(request).body().get("events").forEach(out::add);
        return out;
    }

    private static RelayPayload.Chat chat(String text) {
        return new RelayPayload.Chat(Instant.EPOCH, "n", "s", null, text);
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + AWAIT.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
    }
}
