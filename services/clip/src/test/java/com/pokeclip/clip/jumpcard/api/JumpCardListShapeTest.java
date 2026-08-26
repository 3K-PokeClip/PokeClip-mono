package com.pokeclip.clip.jumpcard.api;

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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>카드 한 장의 모양이 목록과 통로에서 칸 하나까지 같은가</b>(PRD 성공 기준).
 *
 * <p>🔴 <b>MockMvc로는 못 잰다.</b> 통로는 비동기 응답이라 MockMvc가 끝까지 흘려보내지 않는다.
 * 그래서 이 갈래만 진짜 소켓으로 떼어 놓았다 — 나머지는 {@code JumpCardListControllerTest}에 있다.
 * ({@code @SpringBootTest(RANDOM_PORT)}는 통로 시험들이 이미 쓰는 설정이라 <b>컨텍스트가 안 는다</b>.)
 *
 * <p><b>칸 이름만 훑지 않고 JSON 트리를 통째로 비교한다</b> — 이름이 같아도 값의 모양(예: 시각
 * 형식)이 갈릴 수 있고, 두 경로의 직렬화가 실제로 다르다: 통로는 주입받은 {@code ObjectMapper}로
 * 직접 문자열을 만들고, 목록은 스프링 MVC의 메시지 컨버터를 지난다. <b>같은 것을 두 번 재는 것이
 * 아니라 두 경로를 맞대는 것이다.</b>
 *
 * <p>🔴 <b>카드를 통로를 연 <u>뒤에</u> 만든다.</b> 연결 직후 스냅샷으로 받으면 그 전송을 없애는
 * 태스크 7에서 이 시험이 통째로 죽는다 — 발행 경로로 받으면 그 변경과 무관하다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class JumpCardListShapeTest extends IntegrationTestSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String RESOLVE = "/internal/editor-delegations/resolve";

    /** 통로 자리는 사람 단위라 다른 클래스와 겹치면 남의 시험이 503을 맞는다 — 이 클래스 전용 번호다. */
    private static final String 요청자 = "4179";

    private static final String 방송 = "s-shape";

    private final int port;
    private final JumpCardService service;
    private final JdbcTemplate jdbc;

    JumpCardListShapeTest(@LocalServerPort int port, JumpCardService service, JdbcTemplate jdbc) {
        this.port = port;
        this.service = service;
        this.jdbc = jdbc;
    }

    @BeforeEach
    void 앞_테스트의_흔적을_지운다() {
        방송과_카드를_비운다(jdbc);
        jdbc.update("""
                        INSERT INTO broadcasts (stream_id, streamer_id, status, started_at, last_sequence)
                        VALUES (?, ?, 'live', ?, 1)""",
                방송, TestIds.STREAMER,
                OffsetDateTime.ofInstant(Instant.parse("2026-08-25T00:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void 카드_한_장의_모양이_통로로_오는_것과_칸_하나까지_같다() throws Exception {
        AUTH.respondWith(RESOLVE, 200, "{\"relation\":\"OWNER\"}");

        try (SseReader reader = new SseReader(
                "http://localhost:" + port + "/api/clip/broadcasts/" + 방송 + "/events",
                Map.of("Authorization", "Bearer " + TestTokens.access(요청자)))) {
            assertThat(reader.statusCode()).as("통로가 안 열렸다 — 아래 비교가 아무것도 안 잰다").isEqualTo(200);

            // 통로가 선 뒤에 만든다. 스냅샷으로 받으면 초기 전송을 없애는 태스크 7에서 죽는다.
            service.record(방송, new HighlightRequest("evt-shape", "auto", 5_043_000L,
                    new HighlightRequest.Window(5_020_000L, 5_062_000L), 97,
                    MAPPER.readTree("{\"multiplier\":4.2}")));

            assertThat(reader.awaitNamed(1, Duration.ofSeconds(3)))
                    .as("통로로 카드가 안 왔다 — 비교할 한쪽이 없다").isTrue();
            JsonNode 통로_카드 = MAPPER.readTree(reader.named().get(0).data());

            JsonNode 목록 = MAPPER.readTree(목록_본문());
            assertThat(목록.path("cards").size()).as("목록이 비었다 — 빈 것끼리 비교하면 자동으로 참이다")
                    .isEqualTo(1);
            JsonNode 목록_카드 = 목록.path("cards").get(0);

            // 트리 동등이라 칸 하나가 늘거나 값의 모양이 갈리면 빨간불이다.
            assertThat(목록_카드).isEqualTo(통로_카드);
            assertThat(목록_카드.path("evidence").path("multiplier").asDouble())
                    .as("두 쪽 다 근거를 통째로 빠뜨렸다면 위 동등은 참이면서 아무 뜻이 없다").isEqualTo(4.2);
        }
    }

    private String 목록_본문() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(
                                "http://localhost:" + port + "/api/clip/broadcasts/" + 방송 + "/jump-cards"))
                        .header("Authorization", "Bearer " + TestTokens.access(요청자))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("목록 문이 200이 아니다: " + response.body()).isEqualTo(200);
        return response.body();
    }
}
