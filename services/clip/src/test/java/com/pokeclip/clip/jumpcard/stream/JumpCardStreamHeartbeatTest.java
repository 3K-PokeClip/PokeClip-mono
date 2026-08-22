package com.pokeclip.clip.jumpcard.stream;

import com.pokeclip.clip.broadcast.Broadcast;
import com.pokeclip.clip.broadcast.BroadcastRepository;
import com.pokeclip.clip.support.IntegrationTestSupport;
import com.pokeclip.clip.support.SseReader;
import com.pokeclip.clip.support.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 하트비트 주기를 1초로 줄여 잰다. 운영 기본값 20초로는 시험이 20초를 기다려야 한다.
 * 프로퍼티가 다르므로 컨텍스트가 하나 더 뜬다({@code max_connections=300}은 이미 여유).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "pokeclip.jump-card.stream.heartbeat=PT1S")
class JumpCardStreamHeartbeatTest extends IntegrationTestSupport {

    private final int port;
    private final BroadcastRepository broadcasts;
    private final JdbcTemplate jdbc;

    JumpCardStreamHeartbeatTest(@LocalServerPort int port, BroadcastRepository broadcasts, JdbcTemplate jdbc) {
        this.port = port;
        this.broadcasts = broadcasts;
        this.jdbc = jdbc;
    }

    @BeforeEach
    void 정리() {
        jdbc.update("DELETE FROM jump_cards");
        broadcasts.deleteAllInBatch();
        broadcasts.save(Broadcast.startedNow("s-1", "u-1", 1L, Instant.now(), null));
    }

    /** 앞단 프록시가 조용한 연결을 끊지 않게 하는 장치다. 안 오면 배포 후에 연결이 툭툭 끊긴다. */
    @Test
    void 하트비트가_온다() {
        try (SseReader reader = new SseReader(
                "http://localhost:" + port + "/api/clip/broadcasts/s-1/events",
                Map.of("Authorization", "Bearer " + TestTokens.access("heartbeat")))) {

            assertThat(reader.await(1, Duration.ofSeconds(4))).as("1초 주기인데 4초 안에 안 왔다").isTrue();
            assertThat(reader.events()).extracting(SseReader.Event::comment).contains("ping");
        }
    }
}
