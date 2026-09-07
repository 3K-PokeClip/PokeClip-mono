package com.pokeclip.chat.collector.persist;

import com.pokeclip.chat.collector.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 🔴 <b>후원 저장의 「운영 배선」을 재는 검사가 0개였다</b> (POK-234 감사 라운드 2 C2).
 *
 * <p>{@link DonationPersister}에서 {@code @Component}를 지워도 <b>모듈 전체가 초록</b>이었다.
 * 표에 들어가는 것을 재던 검사 둘이 전부 <b>손으로 만든 인스턴스</b>를 쓰기 때문이다
 * ({@code new DonationPersister(jdbc, donationBuffer)} · {@code new DonationBuffer(1_000)}).
 * 즉 <b>빈으로 안 뜨거나 {@code @PostConstruct}가 안 돌면 운영에서 후원이 표에 한 건도
 * 안 들어가는데 아무도 안 잡는</b> 상태였다.
 *
 * <p>{@code DonationSubscriptions}에는 정확히 이 물음(chat-window-test-reality 문항 8)이
 * 걸려 있었는데 {@code DonationBuffer}↔{@code DonationPersister}에는 없었다 —
 * <b>쌍둥이가 갈린 자리</b>다.
 *
 * <p><b>재는 방법</b>: 손으로 아무것도 안 만든다. 컨텍스트가 준 바구니 빈에 넣고,
 * <b>주기 flush가 저절로 돌아</b> 표에 들어오는 것만 본다. 그 길은
 * 「{@code @Component}로 빈이 뜬다」 + 「{@code @PostConstruct}가 스케줄러를 건다」 +
 * 「그 저장기가 <b>이 바구니</b>를 물고 있다」 셋이 다 서야 열린다.
 */
// 문항 2: 「행이 있다」만 보면 아무 데서나 들어온 행도 통과한다 — 이 검사만 쓰는 채널
//         이름으로 좁히고, 넣기 전 0인 것을 먼저 못박는다.
// 문항 8: 이 검사가 붙드는 것은 사본이 아니라 <b>운영이 쓰는 그 빈</b>이다. 그래서
//         @Autowired만 쓰고 new를 한 번도 안 한다.
@SpringBootTest
class DonationPersistenceWiringTest extends IntegrationTestSupport {

    /** 주기 flush가 1초라 그 두 배 넘게 준다. 느린 기계에서 흔들리지 않을 만큼만. */
    private static final Duration AWAIT = Duration.ofSeconds(10);

    private static final String CHANNEL = "wiring-donation-bean";

    @Autowired DonationBuffer buffer;
    @Autowired JdbcTemplate jdbc;

    @Test
    void 빈으로_뜬_후원_저장기가_주기_flush로_표에_넣는다() throws Exception {
        assertThat(rows())
                .as("넣기 전에 행이 있으면 아래 단언이 남의 행을 세는 것이다")
                .isZero();

        buffer.offer(new PersistableDonation(
                "wiring-donation-stream", CHANNEL, "donator-w", "후원자",
                "CHAT", 5000L, "고맙습니다", 1_723_600_900_000L));

        long deadline = System.nanoTime() + AWAIT.toNanos();
        while (rows() < 1 && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }

        assertThat(rows())
                .as("@Component나 @PostConstruct가 없으면 운영에서 후원이 표에 한 건도 안 들어간다")
                .isEqualTo(1);
    }

    private long rows() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM chat_donations WHERE channel_id = ?", Long.class, CHANNEL);
    }
}
