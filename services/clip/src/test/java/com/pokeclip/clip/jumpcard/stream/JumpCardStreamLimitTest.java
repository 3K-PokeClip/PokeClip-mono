package com.pokeclip.clip.jumpcard.stream;

import com.pokeclip.clip.broadcast.Broadcast;
import com.pokeclip.clip.broadcast.BroadcastRepository;
import com.pokeclip.clip.jumpcard.JumpCardService;
import com.pokeclip.clip.support.IntegrationTestSupport;
import com.pokeclip.clip.support.SseReader;
import com.pokeclip.clip.support.TestIds;
import com.pokeclip.clip.support.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

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

    /** 「상한에 걸린 시도가 스냅샷을 읽었는가」를 세려고 감싼다. 세는 것 말고는 실물 그대로다. */
    @MockitoSpyBean
    private JumpCardService service;

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
        broadcasts.save(Broadcast.startedNow("s-1", TestIds.STREAMER, 1L, Instant.now(), null));
    }

    /** 503의 이유가 상한인지 서버 오류인지 구분하려면 본문의 scope까지 봐야 한다(문항 2·5). */
    @Test
    void 사용자당_상한을_넘기면_503이고_본문이_이유를_말한다() {
        String token = TestTokens.access("2101");
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

    /**
     * <b>상한에 걸린 시도는 스냅샷을 한 번도 읽지 않는다.</b>
     *
     * <p>상한 검사가 {@code initial.get()} <b>뒤</b>에 있으면, 거절될 요청도 매번 그 방송 카드
     * 전부를 <b>자물쇠 안에서</b> 읽는다. 재연결 루프는 그것을 초당 수백 번 한다 —
     * 2026-08-24 재현(PR #113 봇 지적 ②): 503 <b>1615회에 조회 1615회</b>(비율 1.00),
     * 5초 중 자물쇠가 <b>41~72%</b> 잡혀 있었고 그동안 {@code publish} 막힘 중앙값이
     * 기준선 55us에서 <b>499us(300장) · 2010us(1200장)</b>로 뛰었다.
     *
     * <p><b>거절되는 쪽은 안 아프다</b>(요청 왕복 2~4ms). 아픈 것은 같은 자물쇠를 기다리는
     * {@code publish}·{@code broadcastEnded}, 즉 <b>남의 화면</b>이다.
     *
     * <p>호출 횟수로 재는 이유 — 시간으로 재면 기계 부하에 흔들린다. <b>0회냐 아니냐</b>는
     * 흔들리지 않는다.
     */
    @Test
    void 상한에_걸린_시도는_스냅샷을_읽지_않는다() {
        String token = TestTokens.access("2102");
        List<SseReader> opened = new ArrayList<>();
        try {
            for (int i = 0; i < 4; i++) {
                SseReader reader = open(token);
                opened.add(reader);
                assertThat(reader.statusCode()).as("상한을 먼저 채워야 이 시험이 무언가를 잰다").isEqualTo(200);
            }

            // 여기까지의 조회 4회는 정상이다. 이 뒤로 거절되는 것만 센다.
            clearInvocations(service);

            for (int i = 0; i < 20; i++) {
                try (SseReader rejected = open(token)) {
                    assertThat(rejected.statusCode()).as("거절 " + i).isEqualTo(503);
                }
            }

            verify(service, never()).snapshotsOf(anyString());
        } finally {
            opened.forEach(SseReader::close);
        }
    }

    private SseReader open(String token) {
        return new SseReader("http://localhost:" + port + "/api/clip/broadcasts/s-1/events",
                Map.of("Authorization", "Bearer " + token));
    }
}
