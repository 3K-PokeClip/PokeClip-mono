package com.pokeclip.clip.jumpcard.stream;

import com.pokeclip.clip.broadcast.Broadcast;
import com.pokeclip.clip.broadcast.BroadcastRepository;
import com.pokeclip.clip.delegation.BroadcastAccessGuard;
import com.pokeclip.clip.jumpcard.JumpCardProperties;
import com.pokeclip.clip.jumpcard.JumpCardRepository;
import com.pokeclip.clip.jumpcard.JumpCardService;
import com.pokeclip.clip.jumpcard.ThrowingSnapshotService;
import com.pokeclip.clip.support.IntegrationTestSupport;
import com.pokeclip.clip.support.SseReader;
import com.pokeclip.clip.support.TestIds;
import com.pokeclip.clip.support.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>통로를 여는 도중 실패해도 연결 자리가 남으면 안 된다.</b>
 *
 * <p>{@code registry.open()}은 {@code conns}에 자리를 잡고 정리 콜백 셋을 거는데,
 * <b>그 콜백은 서블릿 컨테이너가 emitter를 받아 비동기 요청을 시작해야 불린다.</b>
 * 컨트롤러가 그 전에 예외로 끝나면 컨테이너는 그 emitter를 모르므로 타임아웃도 완료도 오류도
 * 일으킬 주체가 없고, <b>자리는 프로세스가 죽을 때까지 남는다.</b> 같은 사용자가 네 번 겪으면
 * {@code max-per-user=4}에 걸려 이후 모든 SSE가 503이 되고 재시작 전엔 회복되지 않는다
 * (인가 2차의 만료 토큰 불사 연결과 같은 실패 모양이다).
 *
 * <p>막는 방법은 <b>연결을 잡기 전에 던질 것을 다 던지는 것</b>이다 —
 * 컨트롤러가 {@code snapshotsOf()}를 {@code open()} 앞에서 부른다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.main.allow-bean-definition-overriding=true")
@Import(StreamOpenFailureTest.FailingSnapshotConfig.class)
class StreamOpenFailureTest extends IntegrationTestSupport {

    /**
     * 운영 소스를 안 건드리고 빈만 덮는다 — 같은 이름({@code jumpCardService})으로 등록해
     * 실물을 대체한다(인가 2차 감사가 CORS 주입에 쓴 방법과 같다).
     */
    @TestConfiguration
    static class FailingSnapshotConfig {

        @Bean
        JumpCardService jumpCardService(JumpCardRepository cards, BroadcastRepository broadcasts,
                                        JumpCardProperties properties, ObjectMapper mapper,
                                        CardStreamRegistry registry, BroadcastAccessGuard guard) {
            return new ThrowingSnapshotService(cards, broadcasts, properties, mapper, registry, guard);
        }
    }

    private final int port;
    private final CardStreamRegistry registry;
    private final BroadcastRepository broadcasts;
    private final JdbcTemplate jdbc;

    StreamOpenFailureTest(@LocalServerPort int port, CardStreamRegistry registry,
                          BroadcastRepository broadcasts, JdbcTemplate jdbc) {
        this.port = port;
        this.registry = registry;
        this.broadcasts = broadcasts;
        this.jdbc = jdbc;
    }

    @BeforeEach
    void 정리() {
        jdbc.update("DELETE FROM jump_cards");
        broadcasts.deleteAllInBatch();
        broadcasts.save(Broadcast.startedNow("s-fail", TestIds.STREAMER, 1L, java.time.Instant.now(), null));
    }

    @Test
    void 스냅샷_읽기가_실패해도_연결_자리가_안_남는다() {
        int before = registry.connectionCount();

        try (SseReader reader = new SseReader(
                "http://localhost:" + port + "/api/clip/broadcasts/s-fail/events",
                Map.of("Authorization", "Bearer " + TestTokens.access("1401")))) {

            assertThat(reader.statusCode()).as("실패는 실패대로 나가야 한다").isEqualTo(500);
        }

        assertThat(registry.connectionCount())
                .as("연결을 잡은 뒤 실패하면 그 자리는 프로세스가 죽을 때까지 남는다")
                .isEqualTo(before);
    }
}
