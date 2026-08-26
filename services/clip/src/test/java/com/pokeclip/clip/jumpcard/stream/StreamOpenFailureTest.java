package com.pokeclip.clip.jumpcard.stream;

import com.pokeclip.clip.broadcast.Broadcast;
import com.pokeclip.clip.broadcast.BroadcastRepository;
import com.pokeclip.clip.support.IntegrationTestSupport;
import com.pokeclip.clip.support.SseReader;
import com.pokeclip.clip.support.TestIds;
import com.pokeclip.clip.support.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;

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
 * {@code openWithSnapshot}이 {@code initial.get()}을 {@code open()} <b>앞에서</b> 부른다.
 *
 * <p>🔴 <b>POK-174가 실패를 심는 자리를 옮겼다.</b> 전에는 {@code JumpCardService.snapshotsOf}만
 * 던지는 하위 클래스를 빈으로 덮어썼는데, 초기 카드 전송이 없어져 <b>그 메서드가 이 경로에서
 * 아예 안 불린다</b>. 지금 자물쇠 안에서 도는 것은 방송 상태 조회 하나라 그쪽을 던지게 한다 —
 * 실물 코드에서 그것이 던지는 상황은 커넥션 고갈·쿼리 타임아웃이다.
 * (덕분에 {@code allow-bean-definition-overriding}과 시험용 하위 클래스가 함께 사라졌다.)
 *
 * <p>🔴 <b>자격 판정이 쓰는 조회는 안 건드린다</b>({@code findStreamerIdByStreamId}) —
 * 그것까지 던지면 <b>연결 자리를 잡기도 전에</b> 끝나 이 시험이 아무것도 안 잰다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StreamOpenFailureTest extends IntegrationTestSupport {

    private static final String RESOLVE = "/internal/editor-delegations/resolve";

    @MockitoSpyBean
    private BroadcastRepository broadcasts;

    private final int port;
    private final CardStreamRegistry registry;
    private final JdbcTemplate jdbc;

    StreamOpenFailureTest(@LocalServerPort int port, CardStreamRegistry registry, JdbcTemplate jdbc) {
        this.port = port;
        this.registry = registry;
        this.jdbc = jdbc;
    }

    @BeforeEach
    void 정리() {
        jdbc.update("DELETE FROM jump_cards");
        broadcasts.deleteAllInBatch();
        broadcasts.save(Broadcast.startedNow("s-fail", TestIds.STREAMER, 1L, java.time.Instant.now(), null));
        AUTH.respondWith(RESOLVE, 200, "{\"relation\":\"OWNER\"}");
    }

    @Test
    void 자물쇠_안_조회가_실패해도_연결_자리가_안_남는다() {
        int before = registry.connectionCount();
        // 방송 픽스처를 심은 뒤에 건다 — 위 save가 이 조회를 안 쓰지만 순서를 뒤집을 이유가 없다.
        doThrow(new IllegalStateException("방송 상태 읽기 실패(시험용)"))
                .when(broadcasts).findByStreamId("s-fail");

        try (SseReader reader = new SseReader(
                "http://localhost:" + port + "/api/clip/broadcasts/s-fail/events",
                Map.of("Authorization", "Bearer " + TestTokens.access("1401")))) {

            assertThat(reader.statusCode()).as("실패는 실패대로 나가야 한다").isEqualTo(500);
        }

        assertThat(AUTH.callCount())
                .as("자격 판정 앞에서 죽었다 — 연결 자리를 잡는 데까지 가지도 않았다").isPositive();
        assertThat(registry.connectionCount())
                .as("연결을 잡은 뒤 실패하면 그 자리는 프로세스가 죽을 때까지 남는다")
                .isEqualTo(before);
    }
}
