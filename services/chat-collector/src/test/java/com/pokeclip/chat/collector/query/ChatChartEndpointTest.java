package com.pokeclip.chat.collector.query;

import com.pokeclip.chat.collector.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static com.pokeclip.chat.collector.query.ChatWindowEndpointTest.TOKEN;
import static com.pokeclip.chat.collector.query.ChatWindowEndpointTest.get;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 차트 창구를 <b>밖에서</b> 친다. 집계 규칙은 {@link ChatChartQueryTest}가 실 PG에서 재고,
 * 여기는 <b>창구가 더하는 것</b>만 본다 — {@code bucket} 그물 · 점 수 상한 · 401 ·
 * <b>400 사유가 우리 본문으로 나가는가</b>(= {@link QueryErrors}가 이 창구를 덮는가).
 *
 * <p><b>방송 번호 접두는 {@code api-chart-}다</b>(문항 7). 창구 검사 둘이 같은 프로퍼티를 쓰므로
 * 스프링 컨텍스트를 {@link ChatWindowEndpointTest}와 <b>공유한다</b> — 프로퍼티가 갈리면
 * 컨텍스트가 하나 더 뜬다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"pokeclip.link.internal-token=" + TOKEN,
                "pokeclip.sync.default-offset-ms=0"})
@ActiveProfiles("test")
class ChatChartEndpointTest extends IntegrationTestSupport {

    /** 픽스처의 후원 순번. 표의 UNIQUE가 요구하는 유일값이면 되고,
     * 이 픽스처가 재는 것은 창구 조회이지 중복 규칙이 아니다
     * — 그쪽은 DonationPersisterTest가 잰다. */
    private static final java.util.concurrent.atomic.AtomicLong DONATION_SEQ =
            new java.util.concurrent.atomic.AtomicLong();

    private static final String STREAM = "api-chart-1";
    private static final Instant T0 = Instant.parse("2026-09-03T15:00:00Z");

    private static final AtomicInteger SEQ = new AtomicInteger(900_000);

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void 내_방송만_비운다() {
        jdbc.update("DELETE FROM chat_messages WHERE stream_id = ?", STREAM);
        jdbc.update("DELETE FROM chat_donations WHERE stream_id = ?", STREAM);
    }

    private HttpResponse<String> 물어본다(String query) throws Exception {
        return get(port, "/internal/streams/" + STREAM + "/chat-chart?" + query, TOKEN);
    }

    @Test
    void 구간과_보정값을_실어_준다() throws Exception {
        insertChat(T0.plusSeconds(1));
        insertChat(T0.plusSeconds(2));
        insertDonation(T0.plusSeconds(15));

        HttpResponse<String> response = 물어본다("from=" + T0 + "&to=" + T0.plusSeconds(20) + "&bucket=10");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.body())
                .contains("\"bucketSeconds\":10")
                .contains("\"appliedOffsetMs\":0")
                .as("표 축 시각이 ISO-8601로 나간다")
                .contains("\"start\":\"2026-09-03T15:00:00Z\",\"chats\":2,\"donations\":0")
                .contains("\"start\":\"2026-09-03T15:00:10Z\",\"chats\":0,\"donations\":1");
    }

    /**
     * <b>{@link QueryErrors}가 이 창구를 덮는지</b>가 여기서 드러난다 — 조언 목록에서 빠지면
     * 400이 <b>500</b>으로 나가고 부르는 쪽은 자기 입력 오류를 「수집 서버 장애」로 읽는다
     * (계획 검증 F13이 태스크 10에 대해 경고한 바로 그 모양이다).
     */
    @Test
    void 시각_그물은_목록_창구와_같은_본문이다() throws Exception {
        assertThat(물어본다("bucket=10").body()).contains("\"error\":\"missing\"");
        assertThat(물어본다("from=어제쯤&to=" + T0).body())
                .contains("\"error\":\"unreadable\"").doesNotContain("어제");
        assertThat(물어본다("from=" + T0.plusSeconds(10) + "&to=" + T0).body())
                .contains("\"error\":\"inverted\"");
        assertThat(물어본다("from=" + T0 + "&to=" + T0.plusSeconds(3601)).body())
                .contains("\"error\":\"too_wide\"");
    }

    @Test
    void 모르는_bucket은_400이고_아는_넷은_통과한다() throws Exception {
        String window = "from=" + T0 + "&to=" + T0.plusSeconds(600);
        for (String bad : new String[] {"1", "7", "3600", "abc", "0", "-10"}) {
            HttpResponse<String> response = 물어본다(window + "&bucket=" + bad);
            assertThat(response.statusCode()).as("bucket=" + bad).isEqualTo(400);
            assertThat(response.body()).as("bucket=" + bad).contains("\"error\":\"bucket\"");
        }
        for (int good : new int[] {5, 10, 30, 60}) {
            assertThat(물어본다(window + "&bucket=" + good).statusCode())
                    .as("bucket=" + good).isEqualTo(200);
        }
        assertThat(물어본다(window).body())
                .as("안 주면 10초다").contains("\"bucketSeconds\":10");
    }

    /**
     * 🔴 <b>기본 설정에서는 이 그물에 닿을 수 없다.</b> {@code window-max}가 1시간이고 가장 촘촘한
     * {@code bucket}이 5초라 최대 점 수가 <b>정확히 720</b>, 즉 상한 그 자체다 — 그보다 넓게
     * 물으면 {@code too_wide}가 <b>먼저</b> 걸린다(이 검사를 처음 썼을 때 실제로 그랬다).
     *
     * <p>그래서 그물을 지우지 않고 <b>창 상한을 넓힌 컨텍스트</b>에서 잰다. 이 그물은 셋 중
     * 하나(창 상한·구간 크기 목록·점 수 상한)가 바뀌는 날을 위한 것이고, 지워 두면 그날
     * 8시간 창이 5,760점으로 통째로 나간다. <b>운영 상한 720을 그대로 쓴다</b> — 상한 값을
     * 검사용으로 바꾸면 재는 것이 사본이 된다(문항 8).
     */
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = {"pokeclip.link.internal-token=" + TOKEN,
                    "pokeclip.sync.default-offset-ms=0",
                    "pokeclip.query.window-max=PT2H"})
    @ActiveProfiles("test")
    static class 창_상한을_넓힌_프로세스 extends IntegrationTestSupport {

        @LocalServerPort int port;

        @Test
        void 점이_너무_많으면_400이다() throws Exception {
            String p = "/internal/streams/" + STREAM + "/chat-chart?from=" + T0 + "&to=";

            assertThat(get(port, p + T0.plusSeconds(3600) + "&bucket=5", TOKEN).statusCode())
                    .as("정확히 720점은 상한 그 자체다 — 막으면 안 된다")
                    .isEqualTo(200);

            HttpResponse<String> tooMany =
                    get(port, p + T0.plusSeconds(3605) + "&bucket=5", TOKEN);
            assertThat(tooMany.statusCode()).isEqualTo(400);
            assertThat(tooMany.body()).contains("\"error\":\"too_many_buckets\"");

            // 🔴 <b>마이크로초가 실린 from 으로 상한을 우회할 수 없다</b>(봇 codex).
            // 창구가 toSeconds()/bucket 으로 따로 셀 때는 <b>둘 다 내림</b>이라 질의가
            // 실제로 만드는 격자보다 적게 나왔다 — 아래가 720 으로 통과하고 721점이 나갔다.
            Instant 마이크로초 = T0.plusNanos(500_000);
            HttpResponse<String> 우회 = get(port,
                    "/internal/streams/" + STREAM + "/chat-chart?from=" + 마이크로초
                            + "&to=" + 마이크로초.plusSeconds(3600) + "&bucket=5", TOKEN);
            assertThat(우회.statusCode())
                    .as("창구가 질의와 다른 방법으로 세면 상한이 우회된다").isEqualTo(400);
            assertThat(우회.body()).contains("\"error\":\"too_many_buckets\"");
        }
    }

    @Test
    void 토큰이_없거나_틀리면_401이다() throws Exception {
        String p = "/internal/streams/" + STREAM + "/chat-chart?from=" + T0 + "&to=" + T0.plusSeconds(10);
        assertThat(get(port, p, null).statusCode()).isEqualTo(401);
        assertThat(get(port, p, "wrong").statusCode()).isEqualTo(401);
    }

    private void insertChat(Instant messageTime) {
        int n = SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO chat_messages
                  (channel_id, sender_channel_id, content, message_time, received_at,
                   content_sha256, stream_id)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                "api-chart-ch", "api-chart-sender", "m" + n,
                Timestamp.from(messageTime), Timestamp.from(messageTime),
                String.format("%064d", n), STREAM);
    }

    private void insertDonation(Instant receivedAt) {
        jdbc.update("""
                INSERT INTO chat_donations
                  (stream_id, channel_id, donator_channel_id, donator_nickname,
                   donation_type, pay_amount, donation_text, received_at, received_seq)
                -- 순번은 매번 유일한 값이면 된다. 이 픽스처가 재는 것은 창구 조회이지
                -- 중복 규칙이 아니다 — 그쪽은 DonationPersisterTest가 잰다.
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                STREAM, "api-chart-ch", "api-chart-donator", "후원자", "CHAT", 1000L, "가즈아",
                Timestamp.from(receivedAt), DONATION_SEQ.incrementAndGet());
    }
}
