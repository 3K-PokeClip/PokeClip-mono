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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 상한 시험만 따로 둔다. {@code application-test.yml}은 상한을 넉넉히(50) 두는데, 여기서만
 * 운영 기본값 4를 다시 건다 — 전역으로 4를 쓰면 이 시험이 연 연결의 자리가 즉시 반납되지 않아
 * <b>뒤 시험들이 전부 503으로 오염된다</b>(plan-critic 실측).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "pokeclip.jump-card.stream.max-per-user=4",
        // 이 시험의 방송에는 카드가 없어 서버가 쓸 것이 없다 — 하트비트가 첫 쓰기가 되어
        // 응답 헤더가 그때까지 안 나간다. 운영 기본값 20초면 한 건에 80초다(전수 3분 중 80초).
        "pokeclip.jump-card.stream.heartbeat=PT1S"
})
class JumpCardStreamLimitTest extends IntegrationTestSupport {

    private final int port;
    private final BroadcastRepository broadcasts;
    private final JdbcTemplate jdbc;

    JumpCardStreamLimitTest(@LocalServerPort int port, BroadcastRepository broadcasts, JdbcTemplate jdbc) {
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

    /** 503의 이유가 상한인지 서버 오류인지 구분하려면 본문의 scope까지 봐야 한다(문항 2·5). */
    @Test
    void 사용자당_상한을_넘기면_503이고_본문이_이유를_말한다() {
        String token = TestTokens.access("limit-user");
        List<SseReader> opened = new ArrayList<>();
        try {
            for (int i = 0; i < 4; i++) {
                SseReader reader = open(token);
                opened.add(reader);
                assertThat(reader.statusCode()).as("상한 안쪽 연결 " + i).isEqualTo(200);
            }

            try (SseReader fifth = open(token)) {
                assertThat(fifth.statusCode()).isEqualTo(503);
                assertThat(fifth.body())
                        .as("503이 상한 때문인지 서버 오류인지 구분하려면 scope까지 봐야 한다")
                        .contains("stream_limit").contains("\"scope\":\"user\"");
            }
        } finally {
            opened.forEach(SseReader::close);
        }
    }

    private SseReader open(String token) {
        return new SseReader("http://localhost:" + port + "/api/clip/broadcasts/s-1/events",
                Map.of("Authorization", "Bearer " + token));
    }
}
