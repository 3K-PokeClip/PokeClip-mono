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
     * 기한을 넘기면 기다리지 않고 돌아오고, <b>못 보낸 것을 버린 수로 센다</b> — 안 세면 종료 때 잃은
     * 채팅이 셈 어디에도 없다(relay-loss-coverage 기준 A 「종료 중 바구니에 남은 것」).
     */
    @Test
    void 닫기_기한을_넘기면_돌아오고_못_보낸_것을_버린_수로_센다() throws Exception {
        clip.holdFirstRequest();   // 읽기 시한(1초)이 먼저 끊는다 → 첫 묶음은 FAILED
        buffer.offer("s-slow", chat("1"));
        await(() -> clip.callCount() == 1);
        buffer.offer("s-slow", chat("2"));
        buffer.offer("s-slow", chat("3"));

        relayer.beginClose();
        long started = System.nanoTime();
        relayer.awaitClosed(Duration.ofMillis(100));
        assertThat(Duration.ofNanos(System.nanoTime() - started))
                .as("기한만큼만 기다리고 돌아와야 종료 예산을 안 넘는다")
                .isLessThan(Duration.ofMillis(900));

        await(() -> !relayer.isRunning());
        assertThat(relayer.isRunning()).isFalse();
        assertThat(relayer.relayDropped()).as("첫 묶음 실패 1 + 못 보낸 2").isEqualTo(3);
        assertThat(clip.callCount()).as("기한을 넘긴 뒤에는 더 보내지 않는다").isEqualTo(1);
    }

    // ------------------------------------------------------------------

    private ClipRelayClient client() {
        // 운영 배선 그대로 — 전용 시한(1초)이 걸린 클라이언트다.
        return new RelayConfiguration.Enabled().clipRelayClient(RestClient.builder(),
                ClientHttpRequestFactoryBuilder.jdk(), properties(), ClipRelayClientTest.link());
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
