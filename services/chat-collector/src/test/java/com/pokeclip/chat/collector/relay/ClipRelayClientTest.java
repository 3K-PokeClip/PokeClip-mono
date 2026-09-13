package com.pokeclip.chat.collector.relay;

import ch.qos.logback.classic.Level;
import com.pokeclip.chat.collector.ChatLogLeakTest;
import com.pokeclip.chat.collector.link.LinkProperties;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 수집기 → clip 채팅 이벤트 중계 클라이언트. 쌍둥이는 {@code broadcast/reattach/LiveBroadcastClient}다 —
 * 같은 clip, 같은 토큰 헤더, 같은 「주입받은 빌더를 쓴다」 규칙.
 *
 * <p><b>다른 점 둘</b>: ① 실패를 예외가 아니라 결과로 돌려준다 — 중계는 재시도하지 않고
 * 버리고 센다(표가 정본이고 놓친 것은 범위 창구가 메운다). ② 시한이 전용이다(F11) —
 * 실시간 가치는 1초 뒤 사라지고, 중계 스레드가 하나라 clip이 매달리면 뒤 방송이 전부 밀린다.
 */
class ClipRelayClientTest {

    private static final String INTERNAL_TOKEN = "internal-token-for-relay-test";

    private FakeClipRelay clip;

    @BeforeEach
    void start() {
        clip = FakeClipRelay.start();
    }

    @AfterEach
    void stop() {
        clip.close();
    }

    @Test
    void 이백번대는_SENT() {
        clip.respondWith(200);
        assertThat(client().send("s-1", List.of(chatEvent("s-1", 1)))).isEqualTo(ClipRelayClient.Outcome.SENT);
        clip.respondWith(202);
        assertThat(client().send("s-1", List.of(chatEvent("s-1", 2)))).isEqualTo(ClipRelayClient.Outcome.SENT);
        // 문항 2: 아예 안 보내고 SENT를 돌려주는 구현도 위를 통과한다.
        assertThat(clip.callCount()).isEqualTo(2);
    }

    /** 방송이 clip 명부에 없다. 재시도해도 안 바뀌므로 FAILED와 가른다(셈이 다르게 읽힌다). */
    @Test
    void 사백사는_UNKNOWN_BROADCAST() {
        clip.respondWith(404);
        assertThat(client().send("s-1", List.of(chatEvent("s-1", 1))))
                .isEqualTo(ClipRelayClient.Outcome.UNKNOWN_BROADCAST);
    }

    @Test
    void 오백과_못닿음은_FAILED이고_던지지_않는다() throws IOException {
        clip.respondWith(500);
        assertThat(client().send("s-1", List.of(chatEvent("s-1", 1)))).isEqualTo(ClipRelayClient.Outcome.FAILED);
        clip.respondWith(401);
        assertThat(client().send("s-1", List.of(chatEvent("s-1", 1)))).isEqualTo(ClipRelayClient.Outcome.FAILED);
        assertThat(clip.callCount()).as("요청이 실제로 나갔는가").isEqualTo(2);

        ClipRelayClient unreachable = clientFor(closedPortBaseUrl());
        assertThat(unreachable.send("s-1", List.of(chatEvent("s-1", 1)))).isEqualTo(ClipRelayClient.Outcome.FAILED);
    }

    /**
     * 칸 이름·시각 축이 창구({@code ChatWindowItem})와 같다(F3). 두 종류 모두 <b>열 칸을 늘 싣는다</b> —
     * 창구도 없는 칸을 null로 싣는다. {@code id} 칸은 없다: 창구의 id(표 PK)와 같은 이름에 다른 값을
     * 실으면 프론트가 짝짓는다(ChatWindowItem javadoc 문항 9).
     */
    @Test
    void 토큰_경로_본문_모양() {
        clip.respondWith(200);
        Instant chatTime = Instant.parse("2026-09-03T15:00:01.123Z");
        Instant donationTime = Instant.parse("2026-09-03T15:00:02Z");
        List<RelayEvent> events = List.of(
                new RelayEvent("live/A 1", 1, 1_757_000_000_000L, new RelayPayload.Chat(chatTime, "닉", "sender-1", "streamer", "ㅋㅋ")),
                new RelayEvent("live/A 1", 2, 1_757_000_000_000L, new RelayPayload.Donation(donationTime, "후원자", "donator-1",
                        1000L, "CHAT", "응원")));

        assertThat(client().send("live/A 1", events)).isEqualTo(ClipRelayClient.Outcome.SENT);

        FakeClipRelay.Received received = clip.requests().getFirst();
        assertThat(received.internalToken()).isEqualTo(INTERNAL_TOKEN);
        assertThat(received.path())
                .as("방송 번호는 경로 변수로 인코딩된다 — 문자열로 이어 붙이면 / 가 경로를 가른다")
                .isEqualTo("/internal/broadcasts/live%2FA%201/chat-events");
        assertThat(received.contentType()).startsWith("application/json");

        JsonNode list = received.body().get("events");
        assertThat(list.size()).isEqualTo(2);
        JsonNode chat = list.get(0);
        assertThat(fieldNames(chat)).containsExactly("seq", "seqEpoch", "kind", "time", "timeBasis", "nickname",
                "senderChannelId", "role", "text", "amount", "donationType");
        assertThat(chat.get("seq").asLong()).isEqualTo(1);
        assertThat(chat.get("seqEpoch").asLong()).isEqualTo(1_757_000_000_000L);
        assertThat(chat.get("kind").asString()).isEqualTo("chat");
        assertThat(chat.get("time").asString()).isEqualTo("2026-09-03T15:00:01.123Z");
        assertThat(chat.get("timeBasis").asString()).isEqualTo("message");
        assertThat(chat.get("nickname").asString()).isEqualTo("닉");
        assertThat(chat.get("senderChannelId").asString()).isEqualTo("sender-1");
        assertThat(chat.get("role").asString()).isEqualTo("streamer");
        assertThat(chat.get("text").asString()).isEqualTo("ㅋㅋ");
        assertThat(chat.get("amount").isNull()).isTrue();
        assertThat(chat.get("donationType").isNull()).isTrue();

        JsonNode donation = list.get(1);
        assertThat(fieldNames(donation)).containsExactly("seq", "seqEpoch", "kind", "time", "timeBasis", "nickname",
                "senderChannelId", "role", "text", "amount", "donationType");
        assertThat(donation.get("seq").asLong()).isEqualTo(2);
        assertThat(donation.get("kind").asString()).isEqualTo("donation");
        // 창구 시험(ChatWindowEndpointTest)이 초가 딱 떨어질 때 "…:01Z" 모양을 단언한다 — 같은 모양이다.
        assertThat(donation.get("time").asString()).isEqualTo("2026-09-03T15:00:02Z");
        assertThat(donation.get("timeBasis").asString()).isEqualTo("received");
        assertThat(donation.get("senderChannelId").asString()).isEqualTo("donator-1");
        assertThat(donation.get("role").isNull()).isTrue();
        assertThat(donation.get("text").asString()).isEqualTo("응원");
        assertThat(donation.get("amount").asLong()).isEqualTo(1000L);
        assertThat(donation.get("donationType").asString()).isEqualTo("CHAT");
        assertThat(received.body().has("id")).isFalse();
        assertThat(chat.has("id")).isFalse();
    }

    /** POK-234 PR-C — 방송 정보는 공통 다섯 칸 + 제목·태그·카테고리·시청자 수. 시청자 수 null은 「못 찾음」 그대로. */
    @Test
    void 방송_정보_본문_모양() {
        clip.respondWith(200);
        Instant observed = Instant.parse("2026-09-03T15:01:00Z");
        List<RelayEvent> events = List.of(
                new RelayEvent("s-1", 3, 7L, new RelayPayload.Info(observed, "제목\0", java.util.Arrays.asList("롤", null), "LoL", null)));

        assertThat(client().send("s-1", events)).isEqualTo(ClipRelayClient.Outcome.SENT);

        JsonNode info = clip.requests().getFirst().body().get("events").get(0);
        assertThat(fieldNames(info)).containsExactly("seq", "seqEpoch", "kind", "time", "timeBasis",
                "title", "tags", "category", "viewers");
        assertThat(info.get("kind").asString()).isEqualTo("broadcast-info");
        assertThat(info.get("timeBasis").asString()).isEqualTo("observed");
        assertThat(info.get("time").asString()).isEqualTo("2026-09-03T15:01:00Z");
        assertThat(info.get("title").asString()).as("NUL을 지운다").isEqualTo("제목");
        assertThat(info.get("tags").size()).as("null 태그는 뺀다").isEqualTo(1);
        assertThat(info.get("category").asString()).isEqualTo("LoL");
        assertThat(info.get("viewers").isNull()).isTrue();
    }

    /**
     * 실패 경고에 본문·닉네임·보낸 사람·토큰이 없다. root TRACE로 잰다(LogCaptor) —
     * 예외 사슬까지 편다({@code ChatLogLeakTest.renderAll}).
     */
    @Test
    void 실패_경고에_본문과_닉네임과_토큰이_없다() {
        clip.respondWith(500);
        String text = "relay-text-" + System.nanoTime();
        String nickname = "relay-nick-" + System.nanoTime();
        String sender = "relay-sender-" + System.nanoTime();
        try (LogCaptor captor = new LogCaptor()) {
            client().send("s-leak", List.of(new RelayEvent("s-leak", 1, 1L,
                    new RelayPayload.Chat(Instant.EPOCH, nickname, sender, null, text))));

            // 양성 대조 — 경고가 한 줄도 없으면 아래 단언은 아무것도 안 본다.
            assertThat(captor.levelOf("chat.relay.send_failed")).isEqualTo(Level.WARN);
            ChatLogLeakTest.assertNoSecretsIn(ChatLogLeakTest.renderAll(captor),
                    List.of(text, nickname, sender, INTERNAL_TOKEN));
        }
    }

    /**
     * clip이 죽으면 묶음마다 실패한다 — 채팅이 초당 수십 건이면 경고도 초당 수십 줄이다.
     * 그래서 {@link ClipRelayClient#WARN_EVERY} 안의 실패는 한 줄로 모으고 다음 줄에 모은 수를 싣는다.
     */
    @Test
    void 연달은_실패는_경고_한_줄로_모은다() {
        clip.respondWith(500);
        ClipRelayClient client = client();
        try (LogCaptor captor = new LogCaptor()) {
            for (int i = 0; i < 5; i++) {
                assertThat(client.send("s-1", List.of(chatEvent("s-1", i + 1)))).isEqualTo(ClipRelayClient.Outcome.FAILED);
            }
            assertThat(clip.callCount()).as("다섯 번 다 나갔다 — 로그를 모으는 것이지 요청을 거르는 것이 아니다").isEqualTo(5);
            assertThat(captor.messages()).filteredOn(line -> line.startsWith("chat.relay.send_failed")).hasSize(1);
        }
    }

    /**
     * 🔴 <b>시한을 값이 아니라 행동으로 잰다</b>(이 서버 CLAUDE.md 「설정으로 거는 것은 행동으로」).
     * 운영 배선 메서드({@link RelayConfiguration.Enabled#clipRelayClient})를 <b>그대로</b> 부른다 —
     * 검사가 시한을 손으로 다시 조립하면 운영 코드를 안 잰다.
     */
    @Test
    void clip이_답을_안_주면_전용_시한에서_끊는다() {
        clip.holdFor(Duration.ofSeconds(20));
        ClipRelayClient client = new RelayConfiguration.Enabled().clipRelayClient(
                RestClient.builder(), ClientHttpRequestFactoryBuilder.jdk(), properties(clip.baseUrl()), link());

        long started = System.nanoTime();
        assertThat(client.send("s-1", List.of(chatEvent("s-1", 1)))).isEqualTo(ClipRelayClient.Outcome.FAILED);
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        assertThat(clip.callCount()).as("요청이 안 나갔으면 시한을 잰 것이 아니다").isEqualTo(1);
        assertThat(took)
                .as("읽기 시한 1초 — 전역 5초나 무한을 물려받았다면 여기를 넘는다")
                .isLessThan(Duration.ofMillis(3_000));
    }

    @Test
    void 내부_토큰이_비면_만들_때_죽는다() {
        assertThatThrownBy(() -> new ClipRelayClient(RestClient.builder(), properties(clip.baseUrl()),
                new LinkProperties("http://localhost:8082", "")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INTERNAL_API_TOKEN");
    }

    @Test
    void clip_주소가_비면_만들_때_죽는다() {
        assertThatThrownBy(() -> new ClipRelayClient(RestClient.builder(), properties(""), link()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CLIP_BASE_URL");
    }

    // ------------------------------------------------------------------

    private ClipRelayClient client() {
        return clientFor(clip.baseUrl());
    }

    /** 운영과 같은 JDK 스택으로 못박는다(쌍둥이 시험과 같은 이유 — Apache 5의 wire 로거가 헤더를 찍는다). */
    private ClipRelayClient clientFor(String baseUrl) {
        return new ClipRelayClient(RestClient.builder().requestFactory(new JdkClientHttpRequestFactory()),
                properties(baseUrl), link());
    }

    static RelayProperties properties(String baseUrl) {
        return new RelayProperties(true, 10_000, Duration.ofMillis(100), baseUrl);
    }

    static LinkProperties link() {
        return new LinkProperties("http://localhost:8082", INTERNAL_TOKEN);
    }

    private static RelayEvent chatEvent(String streamId, long seq) {
        return new RelayEvent(streamId, seq, 1L, new RelayPayload.Chat(Instant.EPOCH, "n", "s", null, "t"));
    }

    private static List<String> fieldNames(JsonNode node) {
        return node.propertyNames().stream().toList();
    }

    private static String closedPortBaseUrl() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return "http://" + socket.getInetAddress().getHostAddress() + ":" + socket.getLocalPort();
        }
    }
}
