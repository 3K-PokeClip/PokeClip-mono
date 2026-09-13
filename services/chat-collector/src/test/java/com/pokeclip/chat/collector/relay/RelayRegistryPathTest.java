package com.pokeclip.chat.collector.relay;

import com.pokeclip.chat.collector.ChatLogLeakTest;
import com.pokeclip.chat.collector.ChzzkProperties;
import com.pokeclip.chat.collector.archive.ArchivableChat;
import com.pokeclip.chat.collector.archive.ArchiveCounters;
import com.pokeclip.chat.collector.archive.ChatArchive;
import com.pokeclip.chat.collector.chzzk.DonationSubscription;
import com.pokeclip.chat.collector.fake.FakeChzzkBehavior;
import com.pokeclip.chat.collector.fake.FakeChzzkTest;
import com.pokeclip.chat.collector.persist.ChatBuffer;
import com.pokeclip.chat.collector.persist.ChatPersister;
import com.pokeclip.chat.collector.persist.DonationBuffer;
import com.pokeclip.chat.collector.persist.DonationPersister;
import com.pokeclip.chat.collector.session.SessionKey;
import com.pokeclip.chat.collector.session.SessionRegistry;
import com.pokeclip.chat.collector.status.DonationSubscriptions;
import com.pokeclip.chat.collector.support.IntegrationTestSupport;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>등록부 경로</b>로 중계를 잰다 — 가짜 치지직 → {@code SessionRegistry} → {@code StreamSession.handleFrame}
 * → 진짜 {@link RelayBuffer} → 진짜 {@link ChatRelayer} → clip(대역 또는 가짜).
 *
 * <p>🔴 <b>옛 경로({@code CollectorRunner})로 재지 않는 이유</b>(계획 검증 F5): 옛 경로는 방송 번호가 없고
 * 중계가 {@code RelaySink.NONE}이라, 거기서 「안 담긴다」·「안 샌다」를 재면 <b>자동으로 참</b>이다.
 * 바구니·중계기는 운영 배선 메서드({@link RelayConfiguration.Enabled})로 만든다 — 손으로 조립하면
 * 스레드 이름·시한 같은 배선이 사본이 된다(문항 8).
 */
@FakeChzzkTest
class RelayRegistryPathTest extends IntegrationTestSupport {

    private static final Duration AWAIT = Duration.ofSeconds(10);
    private static final AtomicLong KEY_SEQ = new AtomicLong();

    @LocalServerPort int port;
    @Autowired FakeChzzkBehavior behavior;
    @Autowired RestClient.Builder restClientBuilder;
    @Autowired JdbcTemplate jdbc;

    private final RelayConfiguration.Enabled config = new RelayConfiguration.Enabled();
    private final List<Runnable> cleanups = new ArrayList<>();
    private SessionRegistry registry;
    private ChatRelayer relayer;
    private ChatPersister persister;
    private DonationPersister donationPersister;
    private FakeClipRelay clip;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM chat_messages WHERE stream_id LIKE 'relay-%'");
        jdbc.update("DELETE FROM chat_donations WHERE stream_id LIKE 'relay-%'");
    }

    @AfterEach
    void tearDown() {
        cleanups.forEach(Runnable::run);
        if (registry != null) registry.closeAll();
        if (relayer != null) {
            relayer.beginClose();
            relayer.awaitClosed(Duration.ofSeconds(3));
        }
        if (persister != null) persister.close();
        if (donationPersister != null) donationPersister.close();
        if (clip != null) clip.close();
        behavior.reset();
    }

    /**
     * F2 — <b>보내는 쪽에서 스레드 이름을 잰다.</b> 가짜 clip 핸들러에서 재면 가짜 서버의 스레드가 보인다.
     * 수신 콜백(WS 수신 스레드)이 HTTP를 부르면 채팅 폭주 때 수신이 밀린다(8/1 ping 사고와 같은 구조).
     */
    @Test
    void 수신_콜백은_중계_HTTP를_안_부른다() throws Exception {
        RecordingClient recording = recordingClient();
        RelayBuffer buffer = startRelay(recording);
        givenRegistry(buffer);
        registry.open(key("relay-thread", 71L), "tok-71");
        awaitCollecting("relay-thread");

        for (int i = 0; i < 5; i++) {
            behavior.emitChatTo("tok-71", chatJson("본문" + i, 1_723_610_000_000L + i));
        }
        behavior.emitDonationTo("tok-71", donationJson("후원문구"));
        awaitUntil(AWAIT, () -> recording.events().size() == 6);

        assertThat(recording.callerThreads())
                .as("한 번도 안 불렸으면 아래 allMatch는 빈 목록이라 참이다")
                .isNotEmpty();
        assertThat(recording.callerThreads()).allMatch("chzzk-relay"::equals);
        assertThat(recording.events()).hasSize(6);
    }

    /**
     * 🔴 <b>D 틀리게 가는 자리</b>(F3·문항 9) — 중계의 {@code time}은 <b>표에 저장된 값과 같은 축</b>이어야 한다.
     * 채팅은 {@code message_time}(치지직 시각), 후원은 {@code received_at}(우리가 받은 시각).
     * 프론트는 메울 때 {@code from = 마지막 time − appliedOffsetMs}로 창구를 부르고 겹침을
     * {@code (kind, time, senderChannelId, text)}로 거르므로, 한쪽이 수신 시각이면 창구와 SSE가 갈린다.
     */
    @Test
    void 중계_시각과_칸은_표에_저장된_값과_같다() throws Exception {
        RecordingClient recording = recordingClient();
        RelayBuffer buffer = startRelay(recording);
        givenRegistry(buffer, true);
        registry.open(key("relay-axis", 72L), "tok-72");
        awaitCollecting("relay-axis");

        long messageTime = 1_723_610_123_456L;   // 수신 시각(지금)과 1년 넘게 떨어진 값 — 축이 바뀌면 바로 보인다
        behavior.emitChatTo("tok-72", chatJson("축채팅", messageTime));
        behavior.emitDonationTo("tok-72", donationJson("축후원"));
        awaitUntil(AWAIT, () -> recording.events().size() == 2 && rows("chat_messages", "relay-axis") == 1
                && rows("chat_donations", "relay-axis") == 1);
        assertThat(recording.events()).as("둘 다 중계에 닿았다").hasSize(2);

        Instant chatInTable = jdbc.queryForObject(
                "SELECT message_time FROM chat_messages WHERE stream_id = 'relay-axis'", Timestamp.class).toInstant();
        Instant donationInTable = jdbc.queryForObject(
                "SELECT received_at FROM chat_donations WHERE stream_id = 'relay-axis'", Timestamp.class).toInstant();

        RelayEvent chat = recording.events().get(0);
        RelayEvent donation = recording.events().get(1);
        assertThat(chat.streamId()).isEqualTo("relay-axis");
        assertThat(chat.seq()).isEqualTo(1);
        assertThat(chat.payload()).isInstanceOf(RelayPayload.Chat.class);
        assertThat(chat.payload().time()).isEqualTo(Instant.ofEpochMilli(messageTime)).isEqualTo(chatInTable);
        RelayPayload.Chat chatPayload = (RelayPayload.Chat) chat.payload();
        assertThat(chatPayload.text()).isEqualTo("축채팅");
        assertThat(chatPayload.senderChannelId()).isEqualTo("u-1");
        assertThat(chatPayload.nickname()).isEqualTo("닉1");

        assertThat(donation.seq()).as("채팅과 후원이 한 번호를 나눠 쓴다").isEqualTo(2);
        assertThat(donation.payload()).isInstanceOf(RelayPayload.Donation.class);
        assertThat(donation.payload().time()).isEqualTo(donationInTable);
        RelayPayload.Donation donationPayload = (RelayPayload.Donation) donation.payload();
        assertThat(donationPayload.senderChannelId()).isEqualTo("D");
        assertThat(donationPayload.text()).isEqualTo("축후원");
        assertThat(donationPayload.amount()).isEqualTo(1000L);
    }

    /**
     * F14 — clip이 죽어도 저장은 그대로다. 중계 실패는 버리고 세며 표 적재 등식이 닫힌다.
     * 단위 시험({@link ChatRelayerTest})에는 {@code ChatBuffer}도 등식도 없어 여기서 잰다.
     */
    @Test
    void clip이_죽어도_버리고_세며_저장은_그대로다() throws Exception {
        RelayProperties dead = new RelayProperties(true, 10_000, Duration.ofMillis(100), closedPortBaseUrl());
        RelayBuffer buffer = config.relayBuffer(dead);
        relayer = config.chatRelayer(buffer, config.clipRelayClient(RestClient.builder(),
                ClientHttpRequestFactoryBuilder.jdk(), dead, ClipRelayClientTest.link()), dead);
        ChatBuffer chatBuffer = givenRegistry(buffer, true);
        registry.open(key("relay-dead", 73L), "tok-73");
        awaitCollecting("relay-dead");

        int n = 20;
        for (int i = 0; i < n; i++) {
            behavior.emitChatTo("tok-73", chatJson("죽은clip" + i, 1_723_620_000_000L + i));
        }
        awaitUntil(AWAIT, () -> relayer.relayDropped() == n && persister.persistedCount() == n);

        assertThat(relayer.relayDropped()).as("양성 대조 — 중계가 정말 실패했다").isEqualTo(n);
        assertThat(relayer.relayed()).isZero();
        assertThat(persister.persistedCount()).as("clip이 죽어도 표 적재는 그대로다").isEqualTo(n);
        assertThat(rows("chat_messages", "relay-dead")).isEqualTo(n);
        assertThat(registry.receivedTotal())
                .as("판정 등식 received = persisted + conflicts + poisoned + dropped")
                .isEqualTo(persister.persistedCount() + persister.conflictedCount()
                        + persister.poisonedCount() + chatBuffer.droppedCount());
    }

    /** F6 — 세션을 닫으면 번호를 잊는다. 안 잊으면 끝난 방송 수만큼 카운터가 쌓인다. */
    @Test
    void 세션을_닫으면_번호를_잊고_다시_열면_1부터_센다() throws Exception {
        RecordingClient recording = recordingClient();
        RelayBuffer buffer = startRelay(recording);
        givenRegistry(buffer);
        registry.open(key("relay-forget", 74L), "tok-74");
        awaitCollecting("relay-forget");
        behavior.emitChatTo("tok-74", chatJson("잊기1", 1_723_630_000_001L));
        behavior.emitChatTo("tok-74", chatJson("잊기2", 1_723_630_000_002L));
        awaitUntil(AWAIT, () -> recording.events().size() == 2);
        assertThat(buffer.trackedStreamCount()).as("양성 대조 — 닫기 전에는 카운터가 있다").isEqualTo(1);

        assertThat(registry.close("relay-forget")).isTrue();
        assertThat(buffer.trackedStreamCount()).isZero();

        registry.open(key("relay-forget", 74L), "tok-74");
        awaitCollecting("relay-forget");
        behavior.emitChatTo("tok-74", chatJson("잊기3", 1_723_630_000_003L));
        awaitUntil(AWAIT, () -> recording.events().size() == 3);

        assertThat(recording.events()).extracting(RelayEvent::seq).containsExactly(1L, 2L, 1L);
    }

    /** F6 — 방송을 갈아끼우면 옛 방송 번호를 잊고, 새 방송은 1부터 센다. */
    @Test
    void 갈아끼우면_옛_방송_번호를_잊는다() throws Exception {
        RecordingClient recording = recordingClient();
        RelayBuffer buffer = startRelay(recording);
        givenRegistry(buffer);
        registry.open(key("relay-old", 75L), "tok-75");
        awaitCollecting("relay-old");
        behavior.emitChatTo("tok-75", chatJson("옛방송", 1_723_640_000_001L));
        awaitUntil(AWAIT, () -> recording.events().size() == 1);

        assertThat(registry.open(key("relay-new", 75L), "tok-75")).as("갈아끼움").isTrue();
        behavior.emitChatTo("tok-75", chatJson("새방송", 1_723_640_000_002L));
        awaitUntil(AWAIT, () -> recording.events().size() == 2);

        assertThat(recording.events()).extracting(RelayEvent::streamId, RelayEvent::seq).containsExactly(
                org.assertj.core.groups.Tuple.tuple("relay-old", 1L),
                org.assertj.core.groups.Tuple.tuple("relay-new", 1L));
        assertThat(buffer.trackedStreamCount()).as("옛 방송 카운터가 남으면 방송 수만큼 쌓인다").isEqualTo(1);
    }

    /**
     * F5 — 중계가 <b>실패하는</b> 경로에서 본문·닉네임·보낸 사람·후원 문구가 로그에 안 남는다.
     * 탐지기는 {@link ChatLogLeakTest}의 것(root TRACE, 예외 사슬까지)을 그대로 쓴다.
     */
    @Test
    void 중계가_실패해도_본문과_닉네임과_후원문구가_로그에_안_남는다() throws Exception {
        String content = "LEAK-relay-content-" + UUID.randomUUID();
        String nickname = "LEAK-relay-nickname-" + UUID.randomUUID();
        String sender = "LEAK-relay-sender-" + UUID.randomUUID();
        String donationText = "LEAK-relay-donation-" + UUID.randomUUID();
        clip = FakeClipRelay.start();
        clip.respondWith(500);
        RelayProperties props = new RelayProperties(true, 10_000, Duration.ofMillis(100), clip.baseUrl());
        RelayBuffer buffer = config.relayBuffer(props);
        try (LogCaptor captor = new LogCaptor()) {
            relayer = config.chatRelayer(buffer, config.clipRelayClient(RestClient.builder(),
                    ClientHttpRequestFactoryBuilder.jdk(), props, ClipRelayClientTest.link()), props);
            givenRegistry(buffer);
            registry.open(key("relay-leak", 76L), "tok-76");
            awaitCollecting("relay-leak");

            behavior.emitChatTo("tok-76", "{\"senderChannelId\":\"" + sender + "\",\"content\":\"" + content
                    + "\",\"profile\":{\"nickname\":\"" + nickname + "\"},\"messageTime\":1723650000000}");
            behavior.emitDonationTo("tok-76", "{\"donationType\":\"CHAT\",\"channelId\":\"CH\","
                    + "\"donatorChannelId\":\"" + sender + "\",\"donatorNickname\":\"" + nickname + "\","
                    + "\"payAmount\":\"1000\",\"donationText\":\"" + donationText + "\"}");
            awaitUntil(AWAIT, () -> relayer.relayDropped() == 2);

            assertThat(relayer.relayDropped()).as("양성 대조 — 바늘이 실패 경로를 지나갔다").isEqualTo(2);
            assertThat(clip.callCount()).isGreaterThanOrEqualTo(1);
            assertThat(captor.levelOf("chat.relay.send_failed")).isNotNull();
            ChatLogLeakTest.assertNoSecretsIn(ChatLogLeakTest.renderAll(captor),
                    List.of(content, nickname, sender, donationText));
        }
    }


    /**
     * 🔴 L12(감사 A1) — <b>clip이 시한 없이 매달려도</b> 수신·저장이 안 밀린다. 닫힌 포트(즉시 거절)로는
     * 동기 전송 회귀가 들어와도 저장이 안 밀려 판별력이 없다 — 하이라이트 순간의 위험은 거절이 아니라 매달림이다.
     */
    @Test
    void clip이_매달려도_수신과_저장은_안_밀리고_등식이_닫힌다() throws Exception {
        BlockingClient blocking = new BlockingClient();
        cleanups.add(blocking::release);
        RelayProperties props = new RelayProperties(true, 50, Duration.ofMillis(100), "http://127.0.0.1:9");
        RelayBuffer buffer = config.relayBuffer(props);
        relayer = config.chatRelayer(buffer, blocking, props);
        ChatBuffer chatBuffer = givenRegistry(buffer, ChatArchive.NONE, true);
        registry.open(key("relay-hang", 77L), "tok-77");
        awaitCollecting("relay-hang");

        int n = 200;
        behavior.emitChatTo("tok-77", chatJson("매달림0", 1_723_660_000_000L));
        awaitUntil(AWAIT, () -> blocking.entered() == 1);
        assertThat(blocking.entered()).as("중계 스레드가 첫 요청 안에서 매달린 뒤에 넣어야 한다").isEqualTo(1);
        for (int i = 1; i < n; i++) {
            behavior.emitChatTo("tok-77", chatJson("매달림" + i, 1_723_660_000_000L + i));
        }
        awaitUntil(AWAIT, () -> persister.persistedCount() == n);

        assertThat(blocking.isReleased()).as("저장이 끝날 때까지 중계는 여전히 매달려 있다").isFalse();
        assertThat(blocking.entered()).isEqualTo(1);
        assertThat(persister.persistedCount()).isEqualTo(n);
        assertThat(registry.receivedTotal())
                .isEqualTo(persister.persistedCount() + persister.conflictedCount()
                        + persister.poisonedCount() + chatBuffer.droppedCount());
        assertThat(relayer.bufferDropped()).as("1건은 매달린 요청 안, 50건은 바구니, 나머지는 버림").isEqualTo(n - 1 - 50);

        blocking.release();
        awaitUntil(AWAIT, () -> relayer.relayed() + relayer.relayDropped() + relayer.bufferDropped() == n);
        assertThat(relayer.relayed() + relayer.relayDropped() + relayer.bufferDropped()).isEqualTo(n);
        assertThat(blocking.threads()).isNotEmpty().allMatch("chzzk-relay"::equals);
    }

    /**
     * 🔴 L1(F7) — 저장과 중계가 <b>같은 한 번 읽기</b>의 방송 번호를 쓴다. 손잡이: 채팅 갈래의 {@code archive.offer}가
     * 번호 읽기와 중계 사이에 있다. 거기서 수신 스레드를 붙든 채 갈아끼우고 놓는다.
     */
    @Test
    void 저장과_중계가_같은_방송_번호를_쓴다() throws Exception {
        RecordingClient recording = recordingClient();
        RelayBuffer buffer = startRelay(recording);
        LatchArchive archive = new LatchArchive();
        cleanups.add(archive::release);
        givenRegistry(buffer, archive, true);
        registry.open(key("relay-f7-old", 78L), "tok-78");
        awaitCollecting("relay-f7-old");

        behavior.emitChatTo("tok-78", chatJson("번호한번", 1_723_670_000_000L));
        awaitUntil(AWAIT, () -> archive.entered.getCount() == 0);
        assertThat(archive.entered.getCount()).as("수신 스레드가 두 읽기 사이(아카이브 offer)에 멈췄다").isZero();
        assertThat(registry.open(key("relay-f7-new", 78L), "tok-78")).as("갈아끼움").isTrue();
        archive.release();
        awaitUntil(AWAIT, () -> recording.events().size() == 1 && rowsLike("relay-f7-%") == 1);

        String inTable = jdbc.queryForObject(
                "SELECT stream_id FROM chat_messages WHERE stream_id LIKE 'relay-f7-%'", String.class);
        assertThat(inTable).as("저장은 붙들리기 전에 읽은 옛 번호다").isEqualTo("relay-f7-old");
        assertThat(recording.events().getFirst().streamId()).isEqualTo(inTable);
    }

    /**
     * 🔴 L2(F6 순서) — 닫는 중(반납 REST가 붙들린 사이)에 온 프레임이 카운터를 되살려도 <b>닫은 뒤</b> 잊으므로
     * 안 남는다. 손잡이: {@code unsubscribeDelay} + 반납 도착을 먼저 센다.
     */
    @Test
    void 닫는_중에_온_프레임도_카운터를_남기지_않는다() throws Exception {
        RecordingClient recording = recordingClient();
        RelayBuffer buffer = startRelay(recording);
        givenRegistry(buffer);
        registry.open(key("relay-f6", 79L), "tok-79");
        awaitCollecting("relay-f6");
        behavior.emitChatTo("tok-79", chatJson("닫기전", 1_723_680_000_001L));
        awaitUntil(AWAIT, () -> recording.events().size() == 1);

        int before = behavior.unsubscribeCallCount();
        behavior.unsubscribeDelay = Duration.ofSeconds(2);
        Thread closer = new Thread(() -> registry.close("relay-f6"), "relay-test-closer");
        closer.start();
        awaitUntil(AWAIT, () -> behavior.unsubscribeCallCount() > before);
        behavior.emitChatTo("tok-79", chatJson("닫는중", 1_723_680_000_002L));
        awaitUntil(AWAIT, () -> recording.events().size() == 2);
        boolean closerAliveWhenArrived = closer.isAlive();
        closer.join(15_000);

        assertThat(recording.events()).as("양성 대조 — 닫는 중 프레임이 중계에 닿았다").hasSize(2);
        assertThat(closerAliveWhenArrived).as("그 프레임은 close가 끝나기 전에 처리됐다").isTrue();
        assertThat(closer.isAlive()).isFalse();
        assertThat(buffer.trackedStreamCount()).isZero();
    }

    /**
     * 🔴 L3 — 세션의 {@code intakeClosed} 게이트가 중계에도 걸린다. {@code SessionRegistry.shutdown()}은 그 게이트를
     * {@code closeAll()} <b>뒤</b>에 세우므로 바구니 닫기(태스크 17)와 사이에 창이 남는다 — 게이트가 중계 앞이어야
     * 표에 없는 채팅이 SSE로 안 간다.
     *
     * <p>고정 대기 대신 <b>장벽 프레임</b>을 쓴다: 한 소켓의 프레임은 수신 스레드 하나가 차례로 처리하고,
     * 후원 회수 프레임은 게이트를 안 보고 후원 상태를 {@code REFUSED}로 바꾼다 — 그것이 보이면 앞선 두 프레임은
     * 이미 게이트에 막혔다. 게이트는 등록부 내부 필드라 리플렉션으로 세운다(운영에서 세우는 곳은 {@code shutdown} 하나).
     */
    @Test
    void 수신_게이트가_서면_중계에도_안_담긴다() throws Exception {
        RecordingClient recording = recordingClient();
        RelayBuffer buffer = startRelay(recording);
        givenRegistry(buffer);
        registry.open(key("relay-gate", 80L), "tok-80");
        awaitCollecting("relay-gate");
        assertThat(registry.donationStateOf("relay-gate")).as("장벽이 뜻을 가지려면 아직 REFUSED가 아니어야 한다")
                .isNotEqualTo(DonationSubscription.REFUSED);
        AtomicBoolean gate = (AtomicBoolean) ReflectionTestUtils.getField(registry, "intakeClosed");

        gate.set(true);
        behavior.emitChatTo("tok-80", chatJson("막힌채팅", 1_723_690_000_001L));
        behavior.emitDonationTo("tok-80", donationJson("막힌후원"));
        behavior.emitRevokedTo("tok-80", "DONATION");
        awaitUntil(AWAIT, () -> registry.donationStateOf("relay-gate") == DonationSubscription.REFUSED);
        assertThat(registry.donationStateOf("relay-gate")).as("장벽 프레임까지 처리됐다").isEqualTo(DonationSubscription.REFUSED);
        gate.set(false);
        behavior.emitChatTo("tok-80", chatJson("표지", 1_723_690_000_002L));
        awaitUntil(AWAIT, () -> !recording.events().isEmpty());

        assertThat(recording.events()).extracting(e -> ((RelayPayload.Chat) e.payload()).text()).containsExactly("표지");
        assertThat(recording.events().getFirst().seq()).as("막힌 것은 번호도 안 받는다").isEqualTo(1);
    }

    /**
     * 🔴 L4 — 후원 중계 시각이 표 {@code received_at}과 <b>같은 한 번 읽기</b>다. 손잡이: {@code DonationBuffer.offer}가
     * {@code synchronized}라 시험 스레드가 모니터를 쥐면 수신 스레드가 두 읽기 사이에서 멈춘다. 멈춘 것을
     * 스레드 상태로 확인하고 시계가 넘어간 뒤 놓는다(고정 대기 없음).
     */
    @Test
    void 후원_중계_시각은_표와_같은_한_번_읽기다() throws Exception {
        RecordingClient recording = recordingClient();
        RelayBuffer buffer = startRelay(recording);
        ChatBuffer chatBuffer = new ChatBuffer(1_000);
        DonationBuffer donationBuffer = new DonationBuffer(1_000);
        persister = new ChatPersister(jdbc, chatBuffer);
        donationPersister = new DonationPersister(jdbc, donationBuffer);
        donationPersister.start();
        registry = new SessionRegistry(chzzkProperties(), restClientBuilder, chatBuffer, persister, ChatArchive.NONE,
                new DonationSubscriptions(), donationBuffer, buffer);
        registry.open(key("relay-dtime", 81L), "tok-81");
        awaitCollecting("relay-dtime");

        synchronized (donationBuffer) {
            behavior.emitDonationTo("tok-81", donationJson("시각한번"));
            awaitUntil(AWAIT, RelayRegistryPathTest::someoneBlockedOnDonationOffer);
            assertThat(someoneBlockedOnDonationOffer()).as("수신 스레드가 후원 바구니 모니터에서 멈췄다").isTrue();
            long observed = System.currentTimeMillis();
            awaitUntil(AWAIT, () -> System.currentTimeMillis() >= observed + 5);
        }
        awaitUntil(AWAIT, () -> recording.events().size() == 1 && rows("chat_donations", "relay-dtime") == 1);

        Instant inTable = jdbc.queryForObject(
                "SELECT received_at FROM chat_donations WHERE stream_id = 'relay-dtime'", Timestamp.class).toInstant();
        assertThat(recording.events().getFirst().payload().time()).isEqualTo(inTable);
    }

    // ------------------------------------------------------------------

    private RelayBuffer startRelay(ClipRelayClient client) {
        RelayProperties props = new RelayProperties(true, 10_000, Duration.ofMillis(100), "http://127.0.0.1:9");
        RelayBuffer buffer = config.relayBuffer(props);
        relayer = config.chatRelayer(buffer, client, props);
        return buffer;
    }

    private ChatBuffer givenRegistry(RelaySink sink) {
        return givenRegistry(sink, false);
    }

    private ChatBuffer givenRegistry(RelaySink sink, boolean persist) {
        return givenRegistry(sink, ChatArchive.NONE, persist);
    }

    private ChatBuffer givenRegistry(RelaySink sink, ChatArchive archive, boolean persist) {
        ChatBuffer buffer = new ChatBuffer(1_000);
        DonationBuffer donationBuffer = new DonationBuffer(1_000);
        persister = new ChatPersister(jdbc, buffer);
        donationPersister = new DonationPersister(jdbc, donationBuffer);
        if (persist) {
            persister.start();
            donationPersister.start();
        }
        registry = new SessionRegistry(chzzkProperties(), restClientBuilder, buffer, persister, archive,
                new DonationSubscriptions(), donationBuffer, sink);
        return buffer;
    }

    private ChzzkProperties chzzkProperties() {
        return new ChzzkProperties(true, "설정-토큰-쓰면-안-된다",
                "http://localhost:" + port, Duration.ofSeconds(5),
                Duration.ofMillis(200), Duration.ofSeconds(60), Duration.ofMillis(60));
    }

    private long rowsLike(String pattern) {
        return jdbc.queryForObject("SELECT count(*) FROM chat_messages WHERE stream_id LIKE ?", Long.class, pattern);
    }

    /** 누군가 {@code DonationBuffer.offer}의 모니터를 기다리며 BLOCKED인가. */
    private static boolean someoneBlockedOnDonationOffer() {
        return Thread.getAllStackTraces().entrySet().stream().anyMatch(e ->
                e.getKey().getState() == Thread.State.BLOCKED && e.getValue().length > 0
                        && e.getValue()[0].getClassName().equals(DonationBuffer.class.getName())
                        && e.getValue()[0].getMethodName().equals("offer"));
    }

    private void awaitCollecting(String streamId) throws InterruptedException {
        awaitUntil(AWAIT, () -> registry.statusOf(streamId) != null
                && registry.statusOf(streamId).state() == com.pokeclip.chat.collector.CollectionStatus.State.COLLECTING);
        assertThat(registry.statusOf(streamId).state())
                .isEqualTo(com.pokeclip.chat.collector.CollectionStatus.State.COLLECTING);
    }

    private long rows(String table, String streamId) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE stream_id = ?", Long.class, streamId);
    }

    private static SessionKey key(String streamId, long streamerId) {
        return new SessionKey(streamId, streamerId, "CH", Instant.EPOCH.plusSeconds(KEY_SEQ.incrementAndGet()));
    }

    private static String chatJson(String content, long messageTime) {
        return "{\"channelId\":\"CH\",\"senderChannelId\":\"u-1\",\"content\":\"" + content
                + "\",\"profile\":{\"nickname\":\"닉1\"},\"messageTime\":" + messageTime + "}";
    }

    private static String donationJson(String text) {
        return "{\"donationType\":\"CHAT\",\"channelId\":\"CH\",\"donatorChannelId\":\"D\","
                + "\"donatorNickname\":\"n\",\"payAmount\":\"1000\",\"donationText\":\"" + text + "\"}";
    }

    private static String closedPortBaseUrl() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return "http://" + socket.getInetAddress().getHostAddress() + ":" + socket.getLocalPort();
        }
    }

    private static RecordingClient recordingClient() {
        return new RecordingClient();
    }

    /** 첫 호출부터 풀릴 때까지 무기한 붙든다 — 시한 없는 매달림(감사 A1). */
    static final class BlockingClient extends ClipRelayClient {
        private final CountDownLatch gate = new CountDownLatch(1);
        private final AtomicInteger entered = new AtomicInteger();
        private final List<String> threads = new CopyOnWriteArrayList<>();

        BlockingClient() {
            super(RestClient.builder(), new RelayProperties(true, 10_000, Duration.ofMillis(100), "http://127.0.0.1:9"),
                    ClipRelayClientTest.link());
        }

        @Override
        public Outcome send(String streamId, List<RelayEvent> events) {
            threads.add(Thread.currentThread().getName());
            entered.incrementAndGet();
            try {
                gate.await(60, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Outcome.SENT;
        }

        void release() { gate.countDown(); }
        boolean isReleased() { return gate.getCount() == 0; }
        int entered() { return entered.get(); }
        List<String> threads() { return new ArrayList<>(threads); }
    }

    /** 첫 offer만 붙든다 — 채팅 갈래의 두 읽기(번호 → 중계) 사이를 벌리는 손잡이(감사 F7). */
    static final class LatchArchive implements ChatArchive {
        final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch gate = new CountDownLatch(1);
        private final AtomicBoolean first = new AtomicBoolean(true);

        @Override
        public void offer(ArchivableChat chat) {
            if (first.compareAndSet(true, false)) {
                entered.countDown();
                try {
                    gate.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        void release() { gate.countDown(); }
        @Override public void beginClose() { }
        @Override public void awaitClosed(Duration budget) { }
        @Override public ArchiveCounters counters() { return ArchiveCounters.NONE; }
    }

    /**
     * 보내는 쪽 대역. <b>{@link ClipRelayClient#send} 안에서</b> 호출 스레드 이름을 남긴다(F2).
     * clip에는 아무것도 안 보낸다.
     */
    static final class RecordingClient extends ClipRelayClient {

        private final List<String> callerThreads = new CopyOnWriteArrayList<>();
        private final List<RelayEvent> events = new CopyOnWriteArrayList<>();

        RecordingClient() {
            super(RestClient.builder(),
                    new RelayProperties(true, 10_000, Duration.ofMillis(100), "http://127.0.0.1:9"),
                    ClipRelayClientTest.link());
        }

        @Override
        public Outcome send(String streamId, List<RelayEvent> batch) {
            callerThreads.add(Thread.currentThread().getName());
            events.addAll(batch);
            return Outcome.SENT;
        }

        List<String> callerThreads() {
            return new ArrayList<>(callerThreads);
        }

        List<RelayEvent> events() {
            return new ArrayList<>(events);
        }
    }
}
