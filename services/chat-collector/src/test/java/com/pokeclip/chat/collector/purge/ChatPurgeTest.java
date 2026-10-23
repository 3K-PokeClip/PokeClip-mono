package com.pokeclip.chat.collector.purge;

import com.pokeclip.chat.collector.Platform;
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
        jdbc.update("DELETE FROM chat_ingest_keys WHERE stream_id LIKE 'purge-%'");
        archived.clear();
        stopped.clear();
    }

    private final List<String> stopped = new ArrayList<>();
    private final List<String> archived = new ArrayList<>();

    /**
     * 🔴 같은 아이디가 치지직과 SOOP에 있다(POK-397). 한쪽 탈퇴가 다른 쪽의 채팅·후원·방송 정보·짝·원본·세션을 건드리면 안 된다.
     * 남는 쪽 데이터도 <b>탈퇴 시각 + 늦은 창 전</b>에 받은 것으로 심는다. 창 뒤 데이터는 정리가 원래 안 건드려 이 시험이 저절로
     * 참이 된다(계획 검증 F3).
     */
    @Test
    void 치지직_탈퇴는_같은_아이디의_SOOP_데이터를_안_건드린다() {
        같은_아이디를_두_플랫폼에_심는다();

        store.request(Platform.CHZZK, 탈퇴_채널, 요청);
        purger((p, c, b) -> archived.add(p + ":" + c), 요청.plus(Duration.ofMinutes(11))).purge(due(Platform.CHZZK, 탈퇴_채널));

        for (String table : List.of("chat_messages", "chat_donations", "broadcast_info")) {
            assertThat(count(table, Platform.CHZZK, 탈퇴_채널)).as(table).isZero();
            assertThat(count(table, Platform.SOOP, 탈퇴_채널)).as(table + ": SOOP 줄이 지워졌다").isOne();
        }
        assertThat(ingestKeys("purge-chzzk-stream")).isZero();
        assertThat(ingestKeys("purge-soop-stream")).as("SOOP 방송의 짝이 지워졌다").isOne();
        assertThat(archived).containsExactly("CHZZK:" + 탈퇴_채널);
        assertThat(stopped).containsExactly("CHZZK:" + 탈퇴_채널 + "@" + 요청);
        assertThat(store.due(100)).extracting(Due::platform, Due::channelId)
                .doesNotContain(org.assertj.core.groups.Tuple.tuple(Platform.CHZZK, 탈퇴_채널));
    }

    @Test
    void SOOP_탈퇴는_같은_아이디의_치지직_데이터를_안_건드린다() {
        같은_아이디를_두_플랫폼에_심는다();

        store.request(Platform.SOOP, 탈퇴_채널, 요청);
        purger((p, c, b) -> archived.add(p + ":" + c), 요청.plus(Duration.ofMinutes(11))).purge(due(Platform.SOOP, 탈퇴_채널));

        for (String table : List.of("chat_messages", "chat_donations", "broadcast_info")) {
            assertThat(count(table, Platform.SOOP, 탈퇴_채널)).as(table).isZero();
            assertThat(count(table, Platform.CHZZK, 탈퇴_채널)).as(table + ": 치지직 줄이 지워졌다").isOne();
        }
        assertThat(ingestKeys("purge-soop-stream")).isZero();
        assertThat(ingestKeys("purge-chzzk-stream")).as("치지직 방송의 짝이 지워졌다").isOne();
        assertThat(archived).containsExactly("SOOP:" + 탈퇴_채널);
        assertThat(stopped).containsExactly("SOOP:" + 탈퇴_채널 + "@" + 요청);
        assertThat(store.due(100)).extracting(Due::platform, Due::channelId)
                .as("창이 닫혔는데 SOOP 명부 줄이 안 닫혔다")
                .doesNotContain(org.assertj.core.groups.Tuple.tuple(Platform.SOOP, 탈퇴_채널));
    }

    /** 같은 아이디의 치지직 탈퇴가 이미 끝났어도 SOOP 탈퇴는 따로 열리고 따로 닫힌다. 명부가 채널만 보면 서로를 덮는다. */
    @Test
    void 명부는_같은_아이디의_두_플랫폼을_따로_적고_따로_닫는다() {
        store.request(Platform.CHZZK, 탈퇴_채널, 요청);
        store.request(Platform.SOOP, 탈퇴_채널, 요청.plusSeconds(60));
        store.complete(Platform.CHZZK, 탈퇴_채널, 요청.plus(Duration.ofMinutes(11)));

        assertThat(store.due(100)).filteredOn(d -> d.channelId().equals(탈퇴_채널))
                .extracting(Due::platform, Due::requestedAt)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(Platform.SOOP, 요청.plusSeconds(60)));
    }

    @Test
    void 탈퇴_채널의_채팅_후원_방송정보와_원본을_지우고_이웃은_남긴다() {
        심는다(탈퇴_채널, 요청.minusSeconds(3600));
        심는다(이웃_채널, 요청.minusSeconds(3600));
        jdbc.update("INSERT INTO chat_ingest_keys (stream_id, ingest_stream_id) VALUES ('purge-stream', 'purge-key')");

        store.request(Platform.CHZZK, 탈퇴_채널, 요청);
        purger((p, c, before) -> archived.add(p + ":" + c + "@" + before), 요청.plus(Duration.ofMinutes(11))).purge(due(탈퇴_채널));

        for (String table : List.of("chat_messages", "chat_donations", "broadcast_info")) {
            assertThat(count(table, 탈퇴_채널)).as(table).isZero();
            assertThat(count(table, 이웃_채널)).as(table).isOne();
        }
        // 원본도 같은 창(탈퇴 + 10분)으로 자른다.
        assertThat(archived).containsExactly("CHZZK:" + 탈퇴_채널 + "@" + 요청.plus(Duration.ofMinutes(10)));
        assertThat(stopped).as("탈퇴 시각 전에 시작한 세션만 닫는다").containsExactly("CHZZK:" + 탈퇴_채널 + "@" + 요청);
        // 방송 번호 → 스트림키 짝도 지운다(그 방송의 채팅이 이 채널 것이었다).
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_ingest_keys WHERE stream_id = 'purge-stream'",
                Integer.class)).isZero();
        assertThat(store.due(10)).extracting(Due::channelId).doesNotContain(탈퇴_채널);
    }

    /** 응답을 못 받은 발송기가 다시 보내도 지우는 범위가 뒤로 밀리지 않는다(PR #220 codex P1). */
    @Test
    void 같은_탈퇴가_늦게_다시_와도_첫_기준_시각을_지킨다() {
        store.request(Platform.CHZZK, 탈퇴_채널, 요청);
        store.request(Platform.CHZZK, 탈퇴_채널, 요청.plus(Duration.ofHours(1)));

        assertThat(due(탈퇴_채널).requestedAt()).isEqualTo(요청);
    }

    /** 끝난 뒤에 더 늦은 탈퇴가 오면(같은 채널을 연동했던 다른 계정) 다시 연다. 옛 탈퇴의 재전송은 안 연다. */
    @Test
    void 끝난_줄은_더_늦은_탈퇴만_다시_연다() {
        store.request(Platform.CHZZK, 탈퇴_채널, 요청);
        store.complete(Platform.CHZZK, 탈퇴_채널, 요청.plus(Duration.ofMinutes(11)));

        store.request(Platform.CHZZK, 탈퇴_채널, 요청);
        assertThat(store.due(100)).extracting(Due::channelId).doesNotContain(탈퇴_채널);

        store.request(Platform.CHZZK, 탈퇴_채널, 요청.plus(Duration.ofDays(3)));
        assertThat(due(탈퇴_채널).requestedAt()).isEqualTo(요청.plus(Duration.ofDays(3)));
    }

    @Test
    void 늦은_창이_닫히기_전에는_줄을_닫지_않고_창_안에_늦게_적재된_채팅도_지운다() {
        store.request(Platform.CHZZK, 탈퇴_채널, 요청);
        purger((p, c, b) -> { }, 요청.plus(Duration.ofMinutes(1))).purge(due(탈퇴_채널));
        assertThat(store.due(10)).extracting(Due::channelId).contains(탈퇴_채널);

        // 바구니에 남아 있던 채팅이 지운 뒤 적재됐다(받은 시각은 탈퇴 직전).
        심는다(탈퇴_채널, 요청.minusSeconds(5));
        purger((p, c, b) -> { }, 요청.plus(Duration.ofMinutes(11))).purge(due(탈퇴_채널));

        assertThat(count("chat_messages", 탈퇴_채널)).isZero();
        assertThat(store.due(10)).extracting(Due::channelId).doesNotContain(탈퇴_채널);
    }

    @Test
    void 창이_닫힌_뒤_새로_받은_채팅은_지우지_않는다() {
        // 같은 채널을 나중에 다른 계정이 연동했다. 그 사람의 채팅은 탈퇴와 무관하다.
        심는다(탈퇴_채널, 요청.plus(Duration.ofHours(2)));
        store.request(Platform.CHZZK, 탈퇴_채널, 요청);

        purger((p, c, b) -> { }, 요청.plus(Duration.ofHours(3))).purge(due(탈퇴_채널));

        assertThat(count("chat_messages", 탈퇴_채널)).isOne();
    }

    @Test
    void 원본_지우기가_실패하면_줄을_닫지_않아_다음_순회가_다시_한다() {
        store.request(Platform.CHZZK, 탈퇴_채널, 요청);
        ChannelPurger broken = purger((p, c, b) -> { throw new IllegalStateException("창고 장애"); },
                요청.plus(Duration.ofHours(1)));

        assertThatThrownBy(() -> broken.purge(due(탈퇴_채널))).isInstanceOf(IllegalStateException.class);

        assertThat(store.due(10)).extracting(Due::channelId).contains(탈퇴_채널);
    }

    /**
     * 묶음을 2로 줄여 다섯 줄을 세 묶음(2·2·1)에 지운다. 운영 묶음(5000)을 넘기려고 1만 2천 줄을 심었더니
     * CI 에서 그 DELETE 하나가 DB socketTimeout(10초)에 끊겼다(PR #320). 로컬은 CPU·디스크 부하를 줘도
     * 30회 모두 0.2초 안이라 원인은 못 좁혔다. 줄 수는 이 검사가 재는 것이 아니라서 덜어 냈다.
     */
    @Test
    void 묶음보다_많은_줄도_끝까지_지운다() {
        jdbc.update("""
                INSERT INTO chat_messages (channel_id, sender_channel_id, content, message_time, received_at, content_sha256, stream_id)
                SELECT ?, 'viewer', 'm' || g, ?, ?, md5(g::text), 'purge-stream' FROM generate_series(1, 5) g""",
                탈퇴_채널, Timestamp.from(요청.minusSeconds(60)), Timestamp.from(요청.minusSeconds(60)));
        store.request(Platform.CHZZK, 탈퇴_채널, 요청);
        ChatPurgeProperties 작은_묶음 = new ChatPurgeProperties(properties.interval(), properties.lateWindow(),
                properties.retain(), properties.retentionInterval(), 2);

        purger((p, c, b) -> { }, 요청.plus(Duration.ofHours(1)), 작은_묶음).purge(due(탈퇴_채널));

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
        jdbc.update("INSERT INTO chat_ingest_keys (stream_id, ingest_stream_id, created_at) VALUES ('purge-old', 'k', ?), "
                + "('purge-new', 'k', ?)", Timestamp.from(old), Timestamp.from(fresh));

        new ChatPurgeSweepers(store, purger((p, c, b) -> { }, now), properties, () -> now).expire();

        for (String table : List.of("chat_messages", "chat_donations", "broadcast_info")) {
            assertThat(count(table, 이웃_채널)).as(table).isZero();
            assertThat(count(table, 탈퇴_채널)).as(table).isOne();
        }
        assertThat(jdbc.queryForList("SELECT stream_id FROM chat_ingest_keys WHERE stream_id LIKE 'purge-%'", String.class))
                .containsExactly("purge-new");
    }

    @Test
    void 내부_문은_명부에_적고_202_모양이_틀리면_400_토큰이_없으면_401() throws Exception {
        assertThat(지운다(탈퇴_채널, "test-internal-token").statusCode()).isEqualTo(202);
        assertThat(지운다(탈퇴_채널, "test-internal-token").statusCode()).isEqualTo(202);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM purged_channels WHERE channel_id = ?", Integer.class,
                탈퇴_채널)).isOne();

        assertThat(지운다("purge-a.b", "test-internal-token").statusCode()).isEqualTo(400);
        assertThat(지운다("purge-x", "since=yesterday", "test-internal-token").statusCode()).isEqualTo(400);
        // 탈퇴 시각을 실어 오면 그것이 기준이다(도착 시각이 아니다).
        assertThat(지운다("purge-since", "since=2026-10-01T00:00:00Z", "test-internal-token").statusCode()).isEqualTo(202);
        assertThat(due("purge-since").requestedAt()).isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
        assertThat(지운다("purge-other", null).statusCode()).isEqualTo(401);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM purged_channels WHERE channel_id LIKE 'purge-%'",
                Integer.class)).isEqualTo(2);
    }

    /** 🔴 기본값이 CHZZK라 SOOP으로 잰다. 플랫폼 칸 전의 auth(값 없음)는 치지직이다. 소문자·공백은 거절한다. */
    @Test
    void 내부_문은_플랫폼을_명부에_적고_모르는_플랫폼은_400() throws Exception {
        assertThat(지운다("purge-p1", "platform=SOOP", "test-internal-token").statusCode()).isEqualTo(202);
        assertThat(지운다("purge-p2", null, "test-internal-token").statusCode()).isEqualTo(202);
        assertThat(지운다("purge-p3", "platform=", "test-internal-token").statusCode()).isEqualTo(202);
        assertThat(jdbc.queryForList("SELECT platform || ':' || channel_id FROM purged_channels"
                + " WHERE channel_id IN ('purge-p1', 'purge-p2', 'purge-p3') ORDER BY channel_id", String.class))
                .containsExactly("SOOP:purge-p1", "CHZZK:purge-p2", "CHZZK:purge-p3");

        HttpResponse<String> lower = 지운다("purge-p4", "platform=soop", "test-internal-token");
        assertThat(lower.statusCode()).isEqualTo(400);
        assertThat(lower.body()).isEqualTo("{\"error\":\"invalid_platform\"}");
        assertThat(지운다("purge-p5", "platform=X%20Y", "test-internal-token").statusCode()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM purged_channels WHERE channel_id IN ('purge-p4', 'purge-p5')",
                Integer.class)).isZero();
    }

    // ── 도우미 ──────────────────────────────────────────────────────────────

    private ChannelPurger purger(ArchivePurge archive, Instant now) {
        return purger(archive, now, properties);
    }

    private ChannelPurger purger(ArchivePurge archive, Instant now, ChatPurgeProperties purgeProperties) {
        AtomicReference<Instant> clock = new AtomicReference<>(now);
        return new ChannelPurger(store, archive, (p, c, since) -> stopped.add(p + ":" + c + "@" + since), purgeProperties, clock::get);
    }

    private Due due(String channelId) {
        return due(Platform.CHZZK, channelId);
    }

    private Due due(Platform platform, String channelId) {
        return store.due(100).stream().filter(d -> d.platform() == platform && d.channelId().equals(channelId))
                .findFirst().orElseThrow();
    }

    private HttpResponse<String> 지운다(String channelId, String token) throws Exception {
        return 지운다(channelId, null, token);
    }

    private HttpResponse<String> 지운다(String channelId, String query, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/internal/channels/"
                + channelId + "/chat-data" + (query == null ? "" : "?" + query))).DELETE();
        if (token != null) {
            request.header("X-Internal-Token", token);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** 채팅·후원·방송 정보 한 줄씩. 받은 시각을 정해 준다. */
    private void 심는다(String channelId, Instant receivedAt) {
        심는다(Platform.CHZZK, channelId, receivedAt, "purge-stream");
    }

    private void 심는다(Platform platform, String channelId, Instant receivedAt, String streamId) {
        Timestamp at = Timestamp.from(receivedAt);
        String p = platform.name();
        jdbc.update("""
                INSERT INTO chat_messages (platform, channel_id, sender_channel_id, content, message_time, received_at, content_sha256, stream_id)
                VALUES (?, ?, 'viewer', '안녕', ?, ?, md5(random()::text), ?)""", p, channelId, at, at, streamId);
        jdbc.update("""
                INSERT INTO chat_donations (platform, stream_id, channel_id, donator_channel_id, donation_type, donation_text, received_at, received_seq)
                VALUES (?, ?, ?, 'viewer', 'CHAT', '고마워요', ?, nextval(pg_get_serial_sequence('chat_donations', 'id')))""",
                p, streamId, channelId, at);
        jdbc.update("INSERT INTO broadcast_info (platform, stream_id, channel_id, observed_at) VALUES (?, ?, ?, ?)",
                p, streamId, channelId, at);
    }

    private int count(String table, String channelId) {
        return count(table, Platform.CHZZK, channelId);
    }

    private int count(String table, Platform platform, String channelId) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE platform = ? AND channel_id = ?",
                Integer.class, platform.name(), channelId);
    }

    private void 같은_아이디를_두_플랫폼에_심는다() {
        심는다(Platform.CHZZK, 탈퇴_채널, 요청.minusSeconds(3600), "purge-chzzk-stream");
        심는다(Platform.SOOP, 탈퇴_채널, 요청.minusSeconds(3600), "purge-soop-stream");
        jdbc.update("INSERT INTO chat_ingest_keys (stream_id, ingest_stream_id) VALUES ('purge-chzzk-stream', 'k1'),"
                + " ('purge-soop-stream', 'k2')");
    }

    private int ingestKeys(String streamId) {
        return jdbc.queryForObject("SELECT count(*) FROM chat_ingest_keys WHERE stream_id = ?", Integer.class, streamId);
    }
}
