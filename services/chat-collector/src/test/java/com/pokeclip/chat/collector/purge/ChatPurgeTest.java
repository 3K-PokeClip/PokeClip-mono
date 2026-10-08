package com.pokeclip.chat.collector.purge;

import com.pokeclip.chat.collector.purge.ChatPurgeStore.Due;
import com.pokeclip.chat.collector.support.IntegrationTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 탈퇴 채널 정리와 60일 보관(POK-256). 채널 둘을 똑같이 심고 한 쪽만 지운다. 남의 것이 남는지 같이 재야
 * 「표를 통째로 비웠다」가 초록이 되지 않는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "pokeclip.link.internal-token=test-internal-token")
@ActiveProfiles("test")
class ChatPurgeTest extends IntegrationTestSupport {

    private static final String 탈퇴_채널 = "purge-gone";
    private static final String 이웃_채널 = "purge-kept";
    private static final Instant 요청 = Instant.parse("2026-10-08T00:00:00Z");

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ChatPurgeStore store;
    @Autowired ChatPurgeProperties properties;

    @BeforeEach
    @AfterEach
    void 내_것만_비운다() {
        for (String table : List.of("chat_messages", "chat_donations", "broadcast_info")) {
            jdbc.update("DELETE FROM " + table + " WHERE channel_id LIKE 'purge-%'");
        }
        jdbc.update("DELETE FROM purged_channels WHERE channel_id LIKE 'purge-%'");
    }

    @Test
    void 탈퇴_채널의_채팅_후원_방송정보와_원본을_지우고_이웃은_남긴다() {
        심는다(탈퇴_채널, 요청.minusSeconds(3600));
        심는다(이웃_채널, 요청.minusSeconds(3600));
        List<String> archived = new ArrayList<>();

        store.request(탈퇴_채널, 요청);
        purger(archived::add, 요청.plus(Duration.ofMinutes(11))).purge(due(탈퇴_채널));

        for (String table : List.of("chat_messages", "chat_donations", "broadcast_info")) {
            assertThat(count(table, 탈퇴_채널)).as(table).isZero();
            assertThat(count(table, 이웃_채널)).as(table).isOne();
        }
        assertThat(archived).containsExactly(탈퇴_채널);
        assertThat(store.due(10)).extracting(Due::channelId).doesNotContain(탈퇴_채널);
    }

    @Test
    void 늦은_창이_닫히기_전에는_줄을_닫지_않고_창_안에_늦게_적재된_채팅도_지운다() {
        store.request(탈퇴_채널, 요청);
        purger(c -> { }, 요청.plus(Duration.ofMinutes(1))).purge(due(탈퇴_채널));
        assertThat(store.due(10)).extracting(Due::channelId).contains(탈퇴_채널);

        // 바구니에 남아 있던 채팅이 지운 뒤 적재됐다(받은 시각은 탈퇴 직전).
        심는다(탈퇴_채널, 요청.minusSeconds(5));
        purger(c -> { }, 요청.plus(Duration.ofMinutes(11))).purge(due(탈퇴_채널));

        assertThat(count("chat_messages", 탈퇴_채널)).isZero();
        assertThat(store.due(10)).extracting(Due::channelId).doesNotContain(탈퇴_채널);
    }

    @Test
    void 창이_닫힌_뒤_새로_받은_채팅은_지우지_않는다() {
        // 같은 채널을 나중에 다른 계정이 연동했다. 그 사람의 채팅은 탈퇴와 무관하다.
        심는다(탈퇴_채널, 요청.plus(Duration.ofHours(2)));
        store.request(탈퇴_채널, 요청);

        purger(c -> { }, 요청.plus(Duration.ofHours(3))).purge(due(탈퇴_채널));

        assertThat(count("chat_messages", 탈퇴_채널)).isOne();
    }

    @Test
    void 원본_지우기가_실패하면_줄을_닫지_않아_다음_순회가_다시_한다() {
        store.request(탈퇴_채널, 요청);
        ChannelPurger broken = purger(c -> { throw new IllegalStateException("창고 장애"); },
                요청.plus(Duration.ofHours(1)));

        assertThatThrownBy(() -> broken.purge(due(탈퇴_채널))).isInstanceOf(IllegalStateException.class);

        assertThat(store.due(10)).extracting(Due::channelId).contains(탈퇴_채널);
    }

    @Test
    void 묶음보다_많은_줄도_끝까지_지운다() {
        jdbc.update("""
                INSERT INTO chat_messages (channel_id, sender_channel_id, content, message_time, received_at, content_sha256, stream_id)
                SELECT ?, 'viewer', 'm' || g, ?, ?, md5(g::text), 'purge-stream' FROM generate_series(1, 12005) g""",
                탈퇴_채널, Timestamp.from(요청.minusSeconds(60)), Timestamp.from(요청.minusSeconds(60)));
        store.request(탈퇴_채널, 요청);

        purger(c -> { }, 요청.plus(Duration.ofHours(1))).purge(due(탈퇴_채널));

        assertThat(count("chat_messages", 탈퇴_채널)).isZero();
    }

    @Test
    void 기한이_지난_줄만_지운다_방송_번호_없는_옛_채팅도() {
        Instant now = 요청;
        Instant old = now.minus(Duration.ofDays(61));
        Instant fresh = now.minus(Duration.ofDays(59));
        심는다(이웃_채널, old);
        jdbc.update("""
                INSERT INTO chat_messages (channel_id, sender_channel_id, content, message_time, received_at, content_sha256)
                VALUES (?, 'viewer', '옛 줄', ?, ?, 'legacy')""", 이웃_채널, Timestamp.from(old), Timestamp.from(old));
        심는다(탈퇴_채널, fresh);

        new ChatPurgeSweepers(store, purger(c -> { }, now), properties, () -> now).expire();

        for (String table : List.of("chat_messages", "chat_donations", "broadcast_info")) {
            assertThat(count(table, 이웃_채널)).as(table).isZero();
            assertThat(count(table, 탈퇴_채널)).as(table).isOne();
        }
    }

    @Test
    void 내부_문은_명부에_적고_202_모양이_틀리면_400_토큰이_없으면_401() throws Exception {
        assertThat(지운다(탈퇴_채널, "test-internal-token").statusCode()).isEqualTo(202);
        assertThat(지운다(탈퇴_채널, "test-internal-token").statusCode()).isEqualTo(202);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM purged_channels WHERE channel_id = ?", Integer.class,
                탈퇴_채널)).isOne();

        assertThat(지운다("purge-a.b", "test-internal-token").statusCode()).isEqualTo(400);
        assertThat(지운다("purge-other", null).statusCode()).isEqualTo(401);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM purged_channels WHERE channel_id LIKE 'purge-%'",
                Integer.class)).isOne();
    }

    // ── 도우미 ──────────────────────────────────────────────────────────────

    private ChannelPurger purger(ArchivePurge archive, Instant now) {
        AtomicReference<Instant> clock = new AtomicReference<>(now);
        return new ChannelPurger(store, archive, properties, clock::get);
    }

    private Due due(String channelId) {
        return store.due(100).stream().filter(d -> d.channelId().equals(channelId)).findFirst().orElseThrow();
    }

    private HttpResponse<String> 지운다(String channelId, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(
                "http://localhost:" + port + "/internal/channels/" + channelId + "/chat-data")).DELETE();
        if (token != null) {
            request.header("X-Internal-Token", token);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** 채팅·후원·방송 정보 한 줄씩. 받은 시각을 정해 준다. */
    private void 심는다(String channelId, Instant receivedAt) {
        Timestamp at = Timestamp.from(receivedAt);
        jdbc.update("""
                INSERT INTO chat_messages (channel_id, sender_channel_id, content, message_time, received_at, content_sha256, stream_id)
                VALUES (?, 'viewer', '안녕', ?, ?, md5(random()::text), 'purge-stream')""", channelId, at, at);
        jdbc.update("""
                INSERT INTO chat_donations (stream_id, channel_id, donator_channel_id, donation_type, donation_text, received_at, received_seq)
                VALUES ('purge-stream', ?, 'viewer', 'CHAT', '고마워요', ?, nextval(pg_get_serial_sequence('chat_donations', 'id')))""",
                channelId, at);
        jdbc.update("INSERT INTO broadcast_info (stream_id, channel_id, observed_at) VALUES ('purge-stream', ?, ?)",
                channelId, at);
    }

    private int count(String table, String channelId) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE channel_id = ?", Integer.class, channelId);
    }
}
