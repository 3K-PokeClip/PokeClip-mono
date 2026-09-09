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

    /**
     * 🔴 <b>저장 재시도가 중복을 만들지 않는다.</b> 배치 저장이 중간에 끊기면 커밋된 앞 행이
     * 되돌려져 다시 들어오는데, 후원에는 그것을 흡수할 장치가 없었다(로컬 리뷰 라운드 1).
     *
     * <p><b>같은 배치를 두 번 넣는 것</b>으로 그 재전송을 흉내 낸다 — 절단을 실제로 만들려면
     * TCP 중계기가 필요하고(POK-84가 그렇게 쟀다), 이 검사가 재려는 것은 절단 자체가 아니라
     * <b>같은 줄이 두 번 와도 표에 한 번만 남는가</b>이기 때문이다.
     */
    @Test
    void 같은_후원이_두_번_와도_한_번만_남는다() {
        DonationBuffer buffer = new DonationBuffer(100);
        buffer.offer(donation("DUP", 5000L));
        DonationPersister persister = new DonationPersister(jdbc, buffer);
        persister.flushBacklog();

        buffer.offer(donation("DUP", 5000L));
        persister.flushBacklog();

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM chat_donations WHERE donator_channel_id='DUP'", Long.class))
                .as("재시도가 중복을 만들었다").isEqualTo(1L);
        assertThat(persister.persistedCount()).as("저장으로 센 것").isEqualTo(1L);
        assertThat(persister.conflictedCount()).as("접힌 것 — 유실이 아니라 중복이다").isEqualTo(1L);
    }

    /**
     * 🔴 <b>진짜 연속 후원은 접히지 않는다.</b> 같은 사람이 같은 금액을 연달아 보내는 것은
     * 정상이고, 그것까지 한 건으로 접으면 <b>실제 후원이 사라진다</b> — 지문에 시각이 들어
     * 있는 이유가 이것이다. 위 검사의 <b>반대 방향</b>이라 둘을 같이 본다.
     */
    @Test
    void 같은_금액을_연달아_보내면_둘_다_남는다() {
        DonationBuffer buffer = new DonationBuffer(100);
        buffer.offer(new PersistableDonation("don-1", "CH", "SAME", "도네초코", "CHAT", 5000L,
                "가즈아", 1_754_300_000_000L));
        buffer.offer(new PersistableDonation("don-1", "CH", "SAME", "도네초코", "CHAT", 5000L,
                "가즈아", 1_754_300_000_001L));   // 1밀리초 뒤

        new DonationPersister(jdbc, buffer).flushBacklog();

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM chat_donations WHERE donator_channel_id='SAME'", Long.class))
                .as("같은 금액 연속 후원이 접혔다 — 실제 후원이 사라진다").isEqualTo(2L);
    }

    /**
     * 🔴 <b>금액과 문구의 경계가 지문에 선다.</b> 재료를 이어 붙일 때 구분자가 없으면
     * <b>경계가 다른데 이어 붙인 결과가 같은</b> 쌍이 한 지문이 되어 <b>진짜 후원 하나가
     * 사라진다</b> — 아래 둘이 정확히 그 쌍이다: {@code 5원+"00"}과 {@code 50원+"0"}은
     * 구분자를 빼면 둘 다 {@code CHAT500}이다(실측 재현).
     *
     * <p><b>이 검사는 앞서 한 번 헛돌았다</b>(POK-234 도장 감사). 처음 쓴 입력이
     * 「금액 없음 + 문구 "null"」과 「금액 없음 + 빈 문구」였는데, 그 둘은 구분자를 지워도
     * 이어 붙인 결과가 달라 <b>14건 전부 초록</b>이었다. <b>구분자가 막는 것은
     * 「null 표기의 모호함」이 아니라 「경계의 모호함」이다</b> — 근거를 잘못 짚으면
     * 그 근거에 맞춘 입력이 아무것도 안 잰다.
     */
    @Test
    void 금액과_문구의_경계가_지문에_선다() {
        DonationBuffer buffer = new DonationBuffer(100);
        buffer.offer(new PersistableDonation("don-1", "CH", "AMB", "n", "CHAT", 5L,
                "00", 1_754_300_000_000L));
        buffer.offer(new PersistableDonation("don-1", "CH", "AMB", "n", "CHAT", 50L,
                "0", 1_754_300_000_000L));

        new DonationPersister(jdbc, buffer).flushBacklog();

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM chat_donations WHERE donator_channel_id='AMB'", Long.class))
                .as("경계가 안 서서 다른 후원 둘이 한 건으로 접혔다").isEqualTo(2L);
    }

    /**
     * 종류에 NUL이 하나 들어와도 저장된다. <b>이 칸만 안 걸러지고 있었다</b>(도장 감사) —
     * 실 PG가 {@code invalid byte sequence 0x00}으로 거부하는데 후원 저장에는 격리가 없어
     * <b>그 방송의 후원이 1초마다 영원히 재시도되며 통째로 멎는다.</b> 채팅은 원본이 S3에
     * 남지만 후원은 아카이브가 없어 되찾을 길이 없다.
     *
     * <p><b>길이도 같이 잰다.</b> 종류 칸이 한때 {@code VARCHAR(16)}이라 17자면 같은 결말이었다.
     */
    @Test
    void 종류에_NUL이나_긴_값이_와도_저장된다() {
        DonationBuffer buffer = new DonationBuffer(100);
        buffer.offer(new PersistableDonation("don-1", "CH", "NUL", "n", "CH\0AT", 1L,
                "t", 1_754_300_000_000L));
        buffer.offer(new PersistableDonation("don-1", "CH", "LONG", "n",
                "0123456789ABCDEFG", 1L, "t", 1_754_300_001_000L));

        new DonationPersister(jdbc, buffer).flushBacklog();

        assertThat(jdbc.queryForObject(
                "SELECT donation_type FROM chat_donations WHERE donator_channel_id='NUL'",
                String.class))
                .as("NUL이 안 걸러져 배치가 통째로 죽는다").isEqualTo("CHAT");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM chat_donations WHERE donator_channel_id='LONG'", Long.class))
                .as("긴 종류가 들어오면 그 방송의 후원 저장이 멎는다").isEqualTo(1L);
    }
}
