package com.pokeclip.chat.collector.liveinfo;

import com.pokeclip.chat.collector.Platform;

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
        jdbc.update("DELETE FROM broadcast_info WHERE stream_id LIKE 'api-bt-%'");
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
        store.insert(new BroadcastInfo(Platform.CHZZK, STREAM, "CH", T, "제목1", List.of("롤"), "LoL", 100));
        store.insert(new BroadcastInfo(Platform.CHZZK, STREAM, "CH", T.plusSeconds(60), "제목2",
                List.of("롤", "다이아"), "LoL", null));
        store.insert(new BroadcastInfo(Platform.CHZZK, STREAM, "CH", T.plusSeconds(120), "제목2",
                List.of("롤", "다이아"), "Just Chatting", 90));

        HttpResponse<String> response = 물어본다("since=" + T.minusSeconds(1), TOKEN);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.body())
                .contains("\"latest\":{\"title\":\"제목2\",\"tags\":[\"롤\",\"다이아\"],\"category\":\"Just Chatting\","
                        + "\"viewers\":90,\"observedAt\":\"2026-09-03T15:02:00Z\"}")
                .as("추이는 오래된 것부터고 모르는 시청자 수는 null이다. 점마다 그 관측의 카테고리가 실린다(POK-402)")
                .contains("\"series\":["
                        + "{\"observedAt\":\"2026-09-03T15:00:00Z\",\"viewers\":100,\"platform\":\"CHZZK\",\"category\":\"LoL\"},"
                        + "{\"observedAt\":\"2026-09-03T15:01:00Z\",\"viewers\":null,\"platform\":\"CHZZK\",\"category\":\"LoL\"},"
                        + "{\"observedAt\":\"2026-09-03T15:02:00Z\",\"viewers\":90,\"platform\":\"CHZZK\",\"category\":\"Just Chatting\"}]");
    }

    /**
     * 같은 방송에 치지직·SOOP 줄이 있으면 {@code platforms}에 플랫폼마다 최신 값이 따로 나가고(POK-397), {@code latest}는 옛 모양
     * 그대로 플랫폼과 무관한 최신 한 줄이다. 추이 줄마다 플랫폼이 실린다. 칸 이름은 clip·프론트와의 계약이라 글자로 못박는다.
     */
    @Test
    void 플랫폼마다_최신_값과_추이_줄의_플랫폼을_준다() throws Exception {
        store.insert(new BroadcastInfo(Platform.CHZZK, STREAM, "CH", T, "치지직 제목", List.of(), "LoL", 100));
        store.insert(new BroadcastInfo(Platform.SOOP, STREAM, "SU", T.plusSeconds(60), null, List.of(), null, 8));

        HttpResponse<String> response = 물어본다("since=" + T.minusSeconds(1), TOKEN);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.body())
                .contains("\"latest\":{\"title\":null,\"tags\":[],\"category\":null,\"viewers\":8,"
                        + "\"observedAt\":\"2026-09-03T15:01:00Z\"}")
                .contains("\"platforms\":[{\"platform\":\"CHZZK\",\"title\":\"치지직 제목\",\"category\":\"LoL\","
                        + "\"viewers\":100,\"observedAt\":\"2026-09-03T15:00:00Z\"},"
                        + "{\"platform\":\"SOOP\",\"title\":null,\"category\":null,"
                        + "\"viewers\":8,\"observedAt\":\"2026-09-03T15:01:00Z\"}]")
                .as("카테고리를 못 얻은 관측은 null이다(POK-402)")
                .contains("{\"observedAt\":\"2026-09-03T15:01:00Z\",\"viewers\":8,\"platform\":\"SOOP\",\"category\":null}");
    }

    /**
     * 모르는 방송은 <b>404가 아니라 200</b>이다. 404로 가르면 「그 방송이 없다」와
     * 「아직 한 번도 관측을 못 했다」가 같아지는데, 후자는 방송이 켜진 직후 매번 지나간다.
     */
    @Test
    void 관측이_없으면_latest는_null이고_추이는_빈_목록이다() throws Exception {
        HttpResponse<String> response = 물어본다("since=" + T, TOKEN);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("{\"latest\":null,\"platforms\":[],\"series\":[]}");
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
        store.insert(new BroadcastInfo(Platform.CHZZK, STREAM, "CH", now.minus(Duration.ofMinutes(30)),
                "안쪽", List.of(), "LoL", 7));
        store.insert(new BroadcastInfo(Platform.CHZZK, STREAM, "CH", now.minus(Duration.ofHours(2)),
                "바깥쪽", List.of(), "LoL", 9));

        String body = 물어본다("", TOKEN).body();

        assertThat(body).contains("\"viewers\":7").doesNotContain("\"viewers\":9");
        assertThat(body).as("최신 한 줄은 구간과 무관하다").contains("\"title\":\"안쪽\"");
    }

    // ── 여러 방송의 마지막 제목(POK-259) ─────────────────────────

    private HttpResponse<String> 제목을_묻는다(String body, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(
                "http://localhost:" + port + "/internal/broadcast-info/latest"))
                .header("Content-Type", "application/json");
        if (token != null) request.header("X-Internal-Token", token);
        return HttpClient.newHttpClient().send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String 본문(List<String> streamIds) {
        return streamIds.stream().map(id -> "\"" + id + "\"")
                .collect(java.util.stream.Collectors.joining(",", "{\"streamIds\":[", "]}"));
    }

    /** 칸 이름은 clip과의 계약이라 글자로 못박는다. 쉼표가 든 번호도 하나로 다룬다(주소에 이어 붙이던 첫 판은 쪼갰다). */
    @Test
    void 여러_방송의_마지막_제목을_계약된_칸_이름으로_준다() throws Exception {
        store.insert(new BroadcastInfo(Platform.CHZZK, "api-bt-1", "CH", T, "제목1", List.of(), "LoL", 3));
        store.insert(new BroadcastInfo(Platform.CHZZK, "api-bt-2", "CH", T, "제목2", List.of(), null, null));

        HttpResponse<String> joined = 제목을_묻는다(본문(List.of("api-bt-1", "api-bt-2", "api-bt-none")), TOKEN);
        assertThat(joined.statusCode()).as(joined.body()).isEqualTo(200);
        assertThat(joined.body())
                .contains("\"titles\":{")
                .contains("\"api-bt-1\":{\"title\":\"제목1\",\"category\":\"LoL\"}")
                .contains("\"api-bt-2\":{\"title\":\"제목2\",\"category\":null}")
                .as("관측이 없는 방송은 키가 없다").doesNotContain("api-bt-none");

        assertThat(제목을_묻는다("{}", TOKEN).body()).isEqualTo("{\"titles\":{},\"platforms\":{}}");

        store.insert(new BroadcastInfo(Platform.CHZZK, "api-bt-a,b", "CH", T, "쉼표 방송", List.of(), null, null));
        assertThat(제목을_묻는다(본문(List.of("api-bt-a,b")), TOKEN).body()).contains("\"api-bt-a,b\":{\"title\":\"쉼표 방송\"");
    }

    /**
     * {@code platforms}는 {@code titles}와 <b>나란히</b> 있다(POK-397, 계획 검증 M1). 제목이 없는 SOOP 방송도 시청자 수가 나가야 하는데
     * {@code titles}는 제목 있는 방송만 키를 가진다. 옛 clip은 {@code titles}만 읽으므로 그 칸은 그대로다.
     */
    @Test
    void 여러_방송의_플랫폼별_최신_값을_제목과_나란히_준다() throws Exception {
        store.insert(new BroadcastInfo(Platform.CHZZK, "api-bt-1", "CH", T, "제목1", List.of(), "LoL", 3));
        store.insert(new BroadcastInfo(Platform.SOOP, "api-bt-1", "SU", T.plusSeconds(60), "숲 제목", List.of(), null, 4));
        store.insert(new BroadcastInfo(Platform.SOOP, "api-bt-2", "SU", T, null, List.of(), null, 9));

        HttpResponse<String> response = 제목을_묻는다(본문(List.of("api-bt-1", "api-bt-2")), TOKEN);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("{\"titles\":{\"api-bt-1\":{\"title\":\"숲 제목\",\"category\":null}},"
                + "\"platforms\":{\"api-bt-1\":["
                + "{\"platform\":\"CHZZK\",\"title\":\"제목1\",\"category\":\"LoL\",\"viewers\":3,\"observedAt\":\"2026-09-03T15:00:00Z\"},"
                + "{\"platform\":\"SOOP\",\"title\":\"숲 제목\",\"category\":null,\"viewers\":4,\"observedAt\":\"2026-09-03T15:01:00Z\"}],"
                + "\"api-bt-2\":[{\"platform\":\"SOOP\",\"title\":null,\"category\":null,\"viewers\":9,"
                + "\"observedAt\":\"2026-09-03T15:00:00Z\"}]}}");
    }

    /** 상한을 넘으면 잘라 주지 않고 400이다. 잘라 주면 부르는 쪽은 뒤 방송 제목이 「없다」로 보인다. 상한 그 자체는 통과(양성 대조). */
    @Test
    void 방송을_백_개_넘게_물으면_400이다() throws Exception {
        // 128자 번호 100개. 주소에 이으면 13KB라 톰캣 요청 줄 한도(8KB)에 걸리던 크기다(PR #214 codex)
        List<String> hundred = java.util.stream.IntStream.range(0, BroadcastInfoController.MAX_STREAMS)
                .mapToObj(i -> ("api-bt-x" + i + "-").repeat(20).substring(0, 128)).toList();
        HttpResponse<String> full = 제목을_묻는다(본문(hundred), TOKEN);
        assertThat(full.statusCode()).as(full.body()).isEqualTo(200);

        List<String> over101 = new java.util.ArrayList<>(hundred);
        over101.add("api-bt-over");
        HttpResponse<String> over = 제목을_묻는다(본문(over101), TOKEN);
        assertThat(over.statusCode()).isEqualTo(400);
        assertThat(over.body()).isEqualTo("{\"error\":\"too_many\"}");
    }

    @Test
    void 제목_창구도_토큰이_없거나_틀리면_401이다() throws Exception {
        assertThat(제목을_묻는다(본문(List.of("api-bt-1")), null).statusCode()).isEqualTo(401);
        assertThat(제목을_묻는다(본문(List.of("api-bt-1")), "wrong").statusCode()).isEqualTo(401);
    }

    @Test
    void 토큰이_없거나_틀리면_401이다() throws Exception {
        HttpResponse<String> none = 물어본다("since=" + T, null);
        assertThat(none.statusCode()).isEqualTo(401);
        assertThat(none.body()).isEmpty();
        assertThat(물어본다("since=" + T, "wrong").statusCode()).isEqualTo(401);
    }
}
