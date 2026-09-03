package com.pokeclip.chat.collector.persist;

import com.pokeclip.chat.collector.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 후원 바구니 → 표. 실제 PostgreSQL로 잰다 — 금액 NULL·시각 축 같은 것은 인메모리
 * 대역으로는 성립하지 않는다.
 *
 * <p>방송 번호 접두 {@code don-}은 기존 어느 검사와도 안 겹친다(문항 7).
 * {@code chat_donations}를 치우는 코드가 어디에도 없으므로 <b>자기 접두만</b> 지운다.
 */
@SpringBootTest
@ActiveProfiles("test")
class DonationPersisterTest extends IntegrationTestSupport {

    private final JdbcTemplate jdbc;

    DonationPersisterTest(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM chat_donations WHERE stream_id LIKE 'don-%'");
    }

    private static PersistableDonation donation(String donatorChannelId, Long amount) {
        return new PersistableDonation("don-1", "CH", donatorChannelId, "도네초코",
                "CHAT", amount, "가즈아", 1_754_300_000_000L);
    }

    /** 금액이 없어도 후원 자체는 남는다 — 디코더가 null을 준 갈래의 끝이다. */
    @Test
    void 후원이_표에_남고_금액이_없어도_저장된다() {
        DonationBuffer buffer = new DonationBuffer(100);
        buffer.offer(donation("D1", 5000L));
        buffer.offer(new PersistableDonation("don-1", "CH", "D2", "n", "VIDEO", null, "",
                1_754_300_001_000L));

        new DonationPersister(jdbc, buffer).flushBacklog();

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM chat_donations WHERE stream_id='don-1'", Long.class))
                .isEqualTo(2L);
        assertThat(jdbc.queryForObject(
                "SELECT pay_amount FROM chat_donations WHERE donator_channel_id='D2'", Long.class))
                .isNull();
    }

    /**
     * 칸 여덟이 <b>제자리에</b> 가는지. 같은 타입(TEXT)이 넷이라 자리를 바꿔도
     * 컴파일러도 DB도 안 막는다 — {@code ChatPersisterTest}의 닉네임/역할과 같은 자리다.
     */
    @Test
    void 칸_여덟이_제자리로_간다() {
        DonationBuffer buffer = new DonationBuffer(100);
        buffer.offer(donation("D-place", 7000L));

        new DonationPersister(jdbc, buffer).flushBacklog();

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT stream_id, channel_id, donator_channel_id, donator_nickname,"
                        + " donation_type, pay_amount, donation_text FROM chat_donations"
                        + " WHERE donator_channel_id='D-place'");
        assertThat(row)
                .containsEntry("stream_id", "don-1")
                .containsEntry("channel_id", "CH")
                .containsEntry("donator_channel_id", "D-place")
                .containsEntry("donator_nickname", "도네초코")
                .containsEntry("donation_type", "CHAT")
                .containsEntry("pay_amount", 7000L)
                .containsEntry("donation_text", "가즈아");
    }

    /** 실패하면 앞으로 되돌린다 — 뒤로 되돌리면 상한 초과가 더 새 후원을 버린다. */
    @Test
    void 저장에_실패하면_바구니로_되돌아가_다시_시도된다() {
        DonationBuffer buffer = new DonationBuffer(100);
        buffer.offer(donation("D-retry", 100L));
        JdbcTemplate broken = new JdbcTemplate(jdbc.getDataSource()) {
            @Override
            public int[] batchUpdate(String sql, java.util.List<Object[]> args) {
                throw new org.springframework.dao.DataAccessResourceFailureException("db down");
            }
        };

        assertThat(new DonationPersister(broken, buffer).flushOnce()).isZero();
        assertThat(buffer.size()).as("버리면 그 후원은 표에도 원본에도 없다").isEqualTo(1);

        assertThat(new DonationPersister(jdbc, buffer).flushOnce()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM chat_donations WHERE donator_channel_id='D-retry'", Long.class))
                .isEqualTo(1L);
    }

    /** NUL은 생성 지점에서 지운다 — 한 글자 때문에 배치 전체가 22021로 죽는다. */
    @Test
    void 닉네임과_문구의_NUL은_생성_지점에서_제거된다() {
        PersistableDonation d = new PersistableDonation("don-1", "CH", "D", "도\0네",
                "CHAT", 1L, "가\0즈아", 1L);
        assertThat(d.donatorNickname()).isEqualTo("도네");
        assertThat(d.donationText()).isEqualTo("가즈아");
    }
}
