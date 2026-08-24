package com.pokeclip.clip.jumpcard;

import com.pokeclip.clip.broadcast.Broadcast;
import com.pokeclip.clip.broadcast.BroadcastRepository;
import com.pokeclip.clip.jumpcard.api.HighlightRequest;
import com.pokeclip.clip.support.IntegrationTestSupport;
import com.pokeclip.clip.support.SseReader;
import com.pokeclip.clip.support.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>상한값 그 자체로 실제 DB와 실제 SSE를 지나간다.</b> 단위 시험은 「막는다」만 재고
 * <b>「허용한 값이 하류에서 정말 되는가」</b>는 못 잰다 — 상한을 한 자리 잘못 잡으면
 * 부팅은 되는데 운영에서 500이 난다.
 *
 * <p>하류가 <b>둘</b>이고 터지는 자리가 다르므로 둘 다 지난다(2026-08-24 재현, PR #114 봇 지적 ②):
 * <ul>
 *   <li>{@code claim} → {@code toSeconds()} → {@code make_interval}.
 *       너무 크면 {@code ERROR: interval out of range}(SQLState 22008)로 <b>500</b></li>
 *   <li><b>읽기</b> → {@code claimedAt.plus(ttl)}({@code JumpCardSnapshot.of}).
 *       너무 크면 {@code DateTimeException}으로 <b>SSE 연결 자체가 500</b>이 된다 —
 *       claim을 아무도 안 눌러도 이미 집힌 카드가 있으면 그렇다. 그래서 <b>집은 뒤에</b> 연다</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "pokeclip.jump-card.claim-ttl=P36500D")
class ClaimTtlUpperBoundTest extends IntegrationTestSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 상한 그 자체. {@code JumpCardProperties.MAX_CLAIM_TTL}과 같아야 이 시험이 경계를 잰다. */
    private static final Duration MAX = Duration.ofDays(36_500);

    private final int port;
    private final JumpCardService service;
    private final JumpCardProperties properties;
    private final BroadcastRepository broadcasts;
    private final JdbcTemplate jdbc;

    ClaimTtlUpperBoundTest(@LocalServerPort int port, JumpCardService service, JumpCardProperties properties,
                           BroadcastRepository broadcasts, JdbcTemplate jdbc) {
        this.port = port;
        this.service = service;
        this.properties = properties;
        this.broadcasts = broadcasts;
        this.jdbc = jdbc;
    }

    @BeforeEach
    void 정리() {
        방송과_카드를_비운다(jdbc);
        broadcasts.save(Broadcast.startedNow("s-ttl-max", "u-1", 1L, Instant.now(), null));
    }

    @Test
    void 상한값으로_집으면_점유_SQL도_읽기도_통과한다() {
        assertThat(properties.claimTtl()).as("상한이 실제로 바인딩됐는지부터 본다").isEqualTo(MAX);

        long cardId = service.record("s-ttl-max", auto()).card().id();

        JumpCardSnapshot claimed = service.claim(cardId, "u-A");

        assertThat(claimed.claimedBy()).as("점유 SQL이 interval을 만들지 못하면 여기 오기 전에 500이다")
                .isEqualTo("u-A");
        assertThat(claimed.claimExpiresAt())
                .as("Instant.plus가 상한을 못 넘기면 여기서 DateTimeException이 난다")
                .isEqualTo(claimed.claimedAt().plus(MAX));

        try (SseReader reader = new SseReader(
                "http://localhost:" + port + "/api/clip/broadcasts/s-ttl-max/events",
                Map.of("Authorization", "Bearer " + TestTokens.access("ttl-max-reader")))) {

            assertThat(reader.statusCode())
                    .as("집힌 카드가 있는 방송의 스냅샷이 곧 claimedAt.plus(ttl)를 부르는 자리다")
                    .isEqualTo(200);
            assertThat(reader.awaitNamed(1, Duration.ofSeconds(3))).isTrue();
            assertThat(MAPPER.readTree(reader.named().get(0).data()).get("claimExpiresAt").isNull())
                    .as("집힌 카드인데 만료 시각이 비면 계산이 조용히 빠진 것이다").isFalse();
        }
    }

    private HighlightRequest auto() {
        return new HighlightRequest("evt-ttl-max", "auto", 1_023_000L,
                new HighlightRequest.Window(1_000_000L, 1_042_000L), 97, null);
    }
}
