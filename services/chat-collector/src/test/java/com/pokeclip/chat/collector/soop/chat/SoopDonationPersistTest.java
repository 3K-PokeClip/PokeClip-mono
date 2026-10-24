package com.pokeclip.chat.collector.soop.chat;

import com.pokeclip.chat.collector.Platform;
import com.pokeclip.chat.collector.archive.ChatArchive;
import com.pokeclip.chat.collector.persist.ChatBuffer;
import com.pokeclip.chat.collector.persist.DonationBuffer;
import com.pokeclip.chat.collector.persist.DonationPersister;
import com.pokeclip.chat.collector.persist.PersistableDonation;
import com.pokeclip.chat.collector.relay.RelaySink;
import com.pokeclip.chat.collector.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SOOP 후원이 실제 표에 어떻게 남는가(POK-431). 🔴 중복 열쇠 {@code uq_chat_donations_received (stream_id, received_at, received_seq)}에
 * 플랫폼이 없어, 같은 방송을 치지직과 SOOP에 같이 내보낼 때 같은 방송 번호 · 같은 밀리초 · 같은 순번이면 뒤에 온 후원이 오류 없이 사라진다
 * (계획 검증 중대 2, 일회용 PG 재현). SOOP 순번을 음수로 매겨 피한다.
 *
 * <p>방송 번호 접두 {@code soopdon-}: 자기 줄만 지운다.
 */
@SpringBootTest
@ActiveProfiles("test")
class SoopDonationPersistTest extends IntegrationTestSupport {

    private static final long AT = 1_761_300_000_000L;

    private final JdbcTemplate jdbc;

    SoopDonationPersistTest(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM chat_donations WHERE stream_id LIKE 'soopdon-%'");
    }

    @Test
    void 같은_방송_같은_시각의_치지직_후원과_SOOP_후원이_둘_다_남는다() {
        DonationBuffer buffer = new DonationBuffer(100);
        // 치지직 세션의 첫 후원(순번 1)
        buffer.offer(new PersistableDonation(Platform.CHZZK, "soopdon-1", "chzzk-ch", "fan", "팬", "CHAT", 1000L, "가즈아",
                AT, 1L));
        SoopChatSessions sessions = new SoopChatSessions(
                new SoopChatProperties(true, "i", "s", "u", "o", 10, Duration.ofSeconds(1), Duration.ofSeconds(1)),
                new ChatBuffer(), buffer, ChatArchive.NONE, RelaySink.NONE, () -> AT);
        List<Long> generations = new java.util.concurrent.CopyOnWriteArrayList<>();
        sessions.attach(new SoopChatSessions.Tabs() {
            @Override
            public void open(String streamId, String token, long generation) {
                generations.add(generation);
            }

            @Override
            public void close(String streamId) {
            }
        });
        try {
            sessions.ensure("soopdon-1", "soop-bj", "t");
            sessions.onDonation("soopdon-1", generations.getLast(),
                    SoopChatDonation.of("BALLOON_GIFTED", "fan", "팬", "10", "false", "false", "false"));
        } finally {
            sessions.shutdown();
        }

        new DonationPersister(jdbc, buffer).flushBacklog();

        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT platform, channel_id, donator_channel_id, donation_type, pay_amount, donation_text
                FROM chat_donations WHERE stream_id = 'soopdon-1' ORDER BY platform""");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(1)).containsEntry("platform", "SOOP").containsEntry("channel_id", "soop-bj")
                .containsEntry("donator_channel_id", "fan").containsEntry("donation_type", "BALLOON_GIFTED")
                .containsEntry("pay_amount", 10L).containsEntry("donation_text", "");
    }
}
