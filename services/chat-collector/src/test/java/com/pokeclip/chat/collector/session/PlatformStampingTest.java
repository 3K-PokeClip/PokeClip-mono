package com.pokeclip.chat.collector.session;

import com.pokeclip.chat.collector.ChzzkProperties;
import com.pokeclip.chat.collector.Platform;
import com.pokeclip.chat.collector.archive.ChatArchive;
import com.pokeclip.chat.collector.fake.FakeChzzkBehavior;
import com.pokeclip.chat.collector.fake.FakeChzzkTest;
import com.pokeclip.chat.collector.persist.ChatBuffer;
import com.pokeclip.chat.collector.persist.ChatPersister;
import com.pokeclip.chat.collector.persist.DonationBuffer;
import com.pokeclip.chat.collector.persist.DonationPersister;
import com.pokeclip.chat.collector.status.DonationSubscriptions;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>채팅·후원이 「그 세션의 플랫폼」으로 표에 남는가</b>(POK-397).
 *
 * <p>🔴 표의 기본값이 {@code 'CHZZK'}라 치지직으로만 재면 플랫폼을 아예 안 넘겨도 초록이다. 그래서 <b>SOOP 키로 연 세션</b>에
 * 프레임을 흘리고 표의 줄이 SOOP인지 본다. 저장 record를 시험에서 직접 만들지 않는다: 세션이 프레임에서 record를 만드는 자리를
 * 지나야 「플랫폼을 key에서 읽는가」가 재어진다.
 *
 * <p>세션 접속은 가짜 치지직 서버로 한다. SOOP 수신은 다음 PR이고, 여기서 재는 것은 「key의 플랫폼이 저장까지 흐르는가」뿐이다.
 */
@FakeChzzkTest
class PlatformStampingTest extends IntegrationTestSupport {

    private static final Duration AWAIT = Duration.ofSeconds(10);

    @LocalServerPort int port;
    @Autowired FakeChzzkBehavior behavior;
    @Autowired RestClient.Builder restClientBuilder;
    @Autowired JdbcTemplate jdbc;

    private SessionRegistry registry;
    private ChatPersister persister;
    private DonationPersister donationPersister;
    /** 아카이브 바구니에 들어간 원본의 (플랫폼, 채널). S3 폴더가 이 플랫폼으로 갈린다. */
    private final List<String> archived = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final ChatArchive recordingArchive = new ChatArchive() {
        @Override public void offer(com.pokeclip.chat.collector.archive.ArchivableChat chat) {
            archived.add(chat.platform() + ":" + chat.channelId());
        }
        @Override public void beginClose() { }
        @Override public void awaitClosed(Duration budget) { }
        @Override public com.pokeclip.chat.collector.archive.ArchiveCounters counters() {
            return com.pokeclip.chat.collector.archive.ArchiveCounters.NONE;
        }
    };

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM chat_messages WHERE channel_id LIKE 'plat-%'");
        jdbc.update("DELETE FROM chat_donations WHERE stream_id LIKE 'plat-%'");
    }

    @AfterEach
    void tearDown() {
        if (registry != null) registry.closeAll();
        if (persister != null) persister.close();
        if (donationPersister != null) donationPersister.close();
        behavior.reset();
    }

    /**
     * 같은 아이디·같은 사람·같은 시각·같은 본문이 두 플랫폼에서 오면 두 줄이다. 지문이 플랫폼을 안 보면 SOOP 쪽이 치지직 채팅의
     * 중복으로 보여 조용히 버려진다.
     */
    @Test
    void 같은_지문의_채팅이_두_플랫폼_세션에서_오면_각자의_플랫폼으로_두_줄이다() throws Exception {
        givenRegistry();
        assertThat(registry.open(key(Platform.CHZZK, "plat-s1", 1L), "tok-c")).isTrue();
        assertThat(registry.open(key(Platform.SOOP, "plat-s2", 2L), "tok-s")).isTrue();

        behavior.emitChatTo("tok-c", chatJson("plat-same", "같은 말", 1_723_610_000_001L));
        behavior.emitChatTo("tok-s", chatJson("plat-same", "같은 말", 1_723_610_000_001L));

        awaitUntil(AWAIT, () -> chatRows() == 2);
        assertThat(jdbc.queryForList(
                "SELECT platform || ':' || stream_id FROM chat_messages WHERE channel_id = 'plat-same' ORDER BY 1",
                String.class))
                .as("SOOP 세션의 채팅이 CHZZK로 남거나, 지문에 걸려 버려졌다")
                .containsExactly("CHZZK:plat-s1", "SOOP:plat-s2");
        assertThat(archived)
                .as("원본 파일의 플랫폼 폴더가 세션의 플랫폼을 따라야 한다")
                .containsExactlyInAnyOrder("CHZZK:plat-same", "SOOP:plat-same");
    }

    @Test
    void SOOP_세션의_후원은_SOOP으로_남는다() throws Exception {
        givenRegistry();
        assertThat(registry.open(key(Platform.SOOP, "plat-d1", 3L), "tok-d")).isTrue();
        awaitUntil(AWAIT, () -> registry.statusOf("plat-d1") != null
                && registry.statusOf("plat-d1").state() == com.pokeclip.chat.collector.CollectionStatus.State.COLLECTING);

        behavior.emitDonationTo("tok-d", "{\"donationType\":\"CHAT\",\"channelId\":\"plat-don\","
                + "\"donatorChannelId\":\"D\",\"donatorNickname\":\"n\","
                + "\"payAmount\":\"1000\",\"donationText\":\"t\"}");

        awaitUntil(AWAIT, () -> donationPlatforms().size() == 1);
        assertThat(donationPlatforms()).containsExactly("SOOP");
    }

    /** 방송 정보 수집기는 이 목록으로 줄을 만든다({@code BroadcastInfoCollector.merge}). */
    @Test
    void 걷는_방송_목록이_세션의_플랫폼을_싣는다() throws Exception {
        givenRegistry();
        assertThat(registry.open(key(Platform.SOOP, "plat-a1", 4L), "tok-a")).isTrue();
        awaitUntil(AWAIT, () -> !registry.activeSessions().isEmpty());

        assertThat(registry.activeSessions())
                .extracting(SessionRegistry.ActiveSession::platform)
                .containsExactly(Platform.SOOP);
    }

    // ------------------------------------------------------------------

    private void givenRegistry() {
        ChatBuffer buffer = new ChatBuffer(1_000);
        DonationBuffer donationBuffer = new DonationBuffer(1_000);
        persister = new ChatPersister(jdbc, buffer);
        persister.start();
        donationPersister = new DonationPersister(jdbc, donationBuffer);
        donationPersister.start();
        registry = new SessionRegistry(
                new ChzzkProperties(true, "설정-토큰-쓰면-안-된다",
                        "http://localhost:" + port, Duration.ofSeconds(5),
                        Duration.ofMillis(200), Duration.ofSeconds(60), Duration.ofMillis(60)),
                restClientBuilder, buffer, persister, recordingArchive,
                new DonationSubscriptions(), donationBuffer);
    }

    private static SessionKey key(Platform platform, String streamId, long streamerId) {
        return new SessionKey(platform, streamId, streamerId, "key-ch-" + streamerId,
                Instant.EPOCH.plusSeconds(streamerId));
    }

    private static String chatJson(String channelId, String content, long messageTime) {
        return "{\"channelId\":\"" + channelId + "\",\"senderChannelId\":\"u-1\","
                + "\"content\":\"" + content + "\",\"messageTime\":" + messageTime + "}";
    }

    private long chatRows() {
        return jdbc.queryForObject("SELECT count(*) FROM chat_messages WHERE channel_id = 'plat-same'", Long.class);
    }

    private List<String> donationPlatforms() {
        return jdbc.queryForList("SELECT platform FROM chat_donations WHERE stream_id = 'plat-d1'", String.class);
    }
}
