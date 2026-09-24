package com.pokeclip.chat.collector.liveinfo;

import com.pokeclip.chat.collector.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 방송 정보 창구를 <b>밖에서</b> 친다. 저장 규칙은 {@link BroadcastInfoStoreTest}가 실 PG에서
 * 재고, 여기는 <b>창구가 더하는 것</b>만 본다 — 응답 칸 이름 · {@code since} 기본값 · 400 · 401.
 *
 * <p><b>방송 번호 접두는 {@code api-bi-}다.</b>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "pokeclip.link.internal-token=" + BroadcastInfoEndpointTest.TOKEN)
@ActiveProfiles("test")
class BroadcastInfoEndpointTest extends IntegrationTestSupport {

    /** 형제 창구 검사들과 값을 맞춰 둔다. */
    static final String TOKEN = "test-internal-token";

    private static final String STREAM = "api-bi-1";
    private static final Instant T = Instant.parse("2026-09-03T15:00:00Z");

    @LocalServerPort int port;
    @Autowired BroadcastInfoStore store;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void 내_방송만_비운다() {
        jdbc.update("DELETE FROM broadcast_info WHERE stream_id LIKE 'api-bi-%'");
    }

    private HttpResponse<String> 물어본다(String query, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(
                "http://localhost:" + port + "/internal/streams/" + STREAM + "/broadcast-info"
                + (query.isEmpty() ? "" : "?" + query)));
        if (token != null) request.header("X-Internal-Token", token);
        return HttpClient.newHttpClient().send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * 응답 칸 이름은 <b>clip·프론트와의 계약</b>이다 — 글자로 못박는다(감사 라운드 3 C-3와 같은 이유).
     * {@code latest}가 {@code series}의 마지막 줄이 아니라는 것도 여기서 갈린다.
     */
    @Test
    void 최신과_추이를_계약된_칸_이름으로_준다() throws Exception {
        store.insert(new BroadcastInfo(STREAM, "CH", T, "제목1", List.of("롤"), "LoL", 100));
        store.insert(new BroadcastInfo(STREAM, "CH", T.plusSeconds(60), "제목2",
                List.of("롤", "다이아"), "LoL", null));

        HttpResponse<String> response = 물어본다("since=" + T.minusSeconds(1), TOKEN);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.body())
                .contains("\"latest\":")
                .contains("\"title\":\"제목2\"")
                .contains("\"tags\":[\"롤\",\"다이아\"]")
                .contains("\"category\":\"LoL\"")
                .contains("\"observedAt\":\"2026-09-03T15:01:00Z\"")
                .contains("\"series\":[")
                .as("추이는 오래된 것부터고 모르는 시청자 수는 null이다")
                .contains("{\"observedAt\":\"2026-09-03T15:00:00Z\",\"viewers\":100}")
                .contains("{\"observedAt\":\"2026-09-03T15:01:00Z\",\"viewers\":null}");
    }

    /**
     * 모르는 방송은 <b>404가 아니라 200</b>이다. 404로 가르면 「그 방송이 없다」와
     * 「아직 한 번도 관측을 못 했다」가 같아지는데, 후자는 방송이 켜진 직후 매번 지나간다.
     */
    @Test
    void 관측이_없으면_latest는_null이고_추이는_빈_목록이다() throws Exception {
        HttpResponse<String> response = 물어본다("since=" + T, TOKEN);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("{\"latest\":null,\"series\":[]}");
    }

    /**
     * 🔴 <b>{@code QueryErrors.assignableTypes}에 이 창구가 들어 있는지를 재는 자리다</b>
     * (계획 검증 F13). 빼면 400이 <b>500</b>으로 나가고, 부르는 쪽은 자기 오타를
     * 「수집 서버 장애」로 읽는다. 200 갈래를 같이 두는 것이 양성 대조다.
     */
    @Test
    void 모르는_since는_400이지_500이_아니다() throws Exception {
        HttpResponse<String> unreadable = 물어본다("since=어제쯤", TOKEN);
        assertThat(unreadable.statusCode()).isEqualTo(400);
        assertThat(unreadable.body()).contains("\"error\":\"unreadable\"")
                .as("받은 값을 되비추면 그대로 로그와 화면으로 흐른다").doesNotContain("어제");

        HttpResponse<String> range = 물어본다("since=9223372036854775807", TOKEN);
        assertThat(range.statusCode()).isEqualTo(400);
        assertThat(range.body()).contains("\"error\":\"out_of_range\"");

        assertThat(물어본다("since=" + T, TOKEN).statusCode())
                .as("읽히는 값은 통과한다 — 양성 대조").isEqualTo(200);
    }

    /**
     * {@code since}를 안 주면 <b>최근 한 시간</b>이다. 「최근 것이 든다」만 재면 창이
     * 무한대여도 초록이라 <b>밖에 있는 줄이 빠지는 것</b>을 같이 잰다.
     */
    @Test
    void since를_안_주면_최근_한_시간이다() throws Exception {
        Instant now = Instant.now();
        store.insert(new BroadcastInfo(STREAM, "CH", now.minus(Duration.ofMinutes(30)),
                "안쪽", List.of(), "LoL", 7));
        store.insert(new BroadcastInfo(STREAM, "CH", now.minus(Duration.ofHours(2)),
                "바깥쪽", List.of(), "LoL", 9));

        String body = 물어본다("", TOKEN).body();

        assertThat(body).contains("\"viewers\":7").doesNotContain("\"viewers\":9");
        assertThat(body).as("최신 한 줄은 구간과 무관하다").contains("\"title\":\"안쪽\"");
    }

    @Test
    void 토큰이_없거나_틀리면_401이다() throws Exception {
        HttpResponse<String> none = 물어본다("since=" + T, null);
        assertThat(none.statusCode()).isEqualTo(401);
        assertThat(none.body()).isEmpty();
        assertThat(물어본다("since=" + T, "wrong").statusCode()).isEqualTo(401);
    }
}
