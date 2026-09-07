package com.pokeclip.clip.broadcast.api;

import com.pokeclip.clip.support.IntegrationTestSupport;
import com.pokeclip.clip.support.NotFoundFloor;
import com.pokeclip.clip.support.TestIds;
import com.pokeclip.clip.support.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 되감기 화면이 채팅을 읽는 사람 문 셋. <b>clip이 수집기를 부르는 첫 자리</b>이고, 이 문들의
 * 자격 판정은 통로·목록과 <b>같은 판정기</b>({@code BroadcastAccessGuard})를 쓴다.
 *
 * <p><b>가짜 판정 창구를 Mockito로 갈아 끼우지 않는다.</b> 그렇게 하면 「판정이 붙기 전과
 * 정확히 같은 것」을 재게 된다 — 진짜로 듣는 소켓 둘({@link IntegrationTestSupport#AUTH} ·
 * {@link IntegrationTestSupport#COLLECTOR})로 가고, <b>답을 안 걸어 둔 시험은 503을 받는다</b>.
 *
 * <p>🔴 <b>요청자 번호가 {@link TestIds#STREAMER}와 다르다.</b> 같으면 auth에 안 묻고 문자열만
 * 비교하는 구현에서도 초록이 된다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BroadcastChatControllerTest extends IntegrationTestSupport {

    private static final String RESOLVE = "/internal/editor-delegations/resolve";

    /** JWT {@code sub}. 방송 픽스처의 스트리머와 <b>다른 사람</b>이다. */
    private static final String 요청자 = "4181";

    private static final String 내_방송 = "s-chat";

    private static final String 없는_방송 = "s-chat-없음";

    private final int port;
    private final JdbcTemplate jdbc;

    BroadcastChatControllerTest(@LocalServerPort int port, JdbcTemplate jdbc) {
        this.port = port;
        this.jdbc = jdbc;
    }

    @BeforeEach
    void 앞_테스트의_흔적을_지운다() {
        방송과_카드를_비운다(jdbc);
        방송을_넣는다(내_방송);
    }

    /**
     * <b>양성 대조가 같은 시험 안에 있다.</b> 상태·본문을 그대로 준다는 것과, 수집기가 실제로
     * 그 창구를 받았다는 것을 같이 본다 — 뒤엣것이 없으면 문이 아무 데나 물어도 초록이다.
     */
    @Test
    void 자격이_있으면_수집기_응답을_그대로_준다() throws Exception {
        볼_수_있다("EDITOR");
        String 본문 = "{\"items\":[],\"nextCursor\":null,\"appliedOffsetMs\":3900}";
        COLLECTOR.respondWith("/internal/streams/" + 내_방송 + "/chat-messages", 200, 본문);

        HttpResponse<String> 응답 = 부른다(내_방송, "chat-messages?from=2026-09-01T00:00:00Z&limit=50");

        assertThat(응답.statusCode()).as("본문=%s", 응답.body()).isEqualTo(200);
        assertThat(응답.body()).as("본문을 다시 조립하면 수집기가 칸을 늘릴 때마다 clip을 고쳐야 한다")
                .isEqualTo(본문);
        assertThat(응답.headers().firstValue("Content-Type").orElse(""))
                .as("text/plain으로 나가면 브라우저가 JSON으로 안 읽는다").contains("application/json");
        assertThat(COLLECTOR.lastPath()).isEqualTo("/internal/streams/" + 내_방송 + "/chat-messages");
        assertThat(COLLECTOR.lastQuery()).contains("from=2026-09-01T00:00:00Z").contains("limit=50");
    }

    /**
     * 400을 503으로 접으면 「내가 잘못 물었다」가 「서버가 아프다」로 둔갑해 화면이 같은 요청을
     * 계속 다시 보낸다. 사유 낱말({@code inverted_window})은 수집기가 정본이다.
     */
    @Test
    void 수집기의_사백은_사유까지_그대로다() throws Exception {
        볼_수_있다("OWNER");
        COLLECTOR.respondWith("/internal/streams/" + 내_방송 + "/chat-chart", 400,
                "{\"error\":\"inverted_window\"}");

        HttpResponse<String> 응답 = 부른다(내_방송, "chat-chart?from=2026-09-01T01:00:00Z&to=2026-09-01T00:00:00Z");

        assertThat(응답.statusCode()).isEqualTo(400);
        assertThat(응답.body()).isEqualTo("{\"error\":\"inverted_window\"}");
    }

    /**
     * 🔴 <b>이 카드가 막는 것: 로그인만 하면 남의 방송 채팅을 읽는 것.</b> 「자격 없음」과
     * 「없는 방송」의 본문이 갈리면 방송 번호를 넣어 보는 것만으로 실재를 알 수 있다.
     *
     * <p>{@code callCount()==0}이 이 시험의 절반이다 — 판정을 수집기 호출 <b>뒤</b>로 옮기면
     * 404는 그대로인데 남의 방송 채팅이 이미 우리 프로세스에 들어와 있다.
     */
    @Test
    void 자격이_없으면_404이고_수집기를_안_부른다() throws Exception {
        볼_수_없다();
        COLLECTOR.respondWith("/internal/streams/" + 내_방송 + "/chat-messages", 200, "{\"items\":[]}");

        HttpResponse<String> 자격_없음 = 부른다(내_방송, "chat-messages");
        assertThat(자격_없음.statusCode()).isEqualTo(404);
        assertThat(COLLECTOR.callCount())
                .as("판정보다 먼저 물었다 — 남의 방송 채팅이 이미 들어왔다").isZero();

        HttpResponse<String> 없는_것 = 부른다(없는_방송, "chat-messages");
        assertThat(없는_것.statusCode()).isEqualTo(404);
        assertThat(자격_없음.body())
                .as("두 404의 본문이 갈리면 번호를 넣어 보는 것만으로 방송의 실재를 안다")
                .isEqualTo(없는_것.body()).contains("broadcast_not_found");
        assertThat(COLLECTOR.callCount()).isZero();

        // 양성 대조 — 자격만 바꾸면 같은 주소가 200이다. 없으면 위 404가 경로 오타여도 초록이다.
        볼_수_있다("OWNER");
        assertThat(부른다(내_방송, "chat-messages").statusCode()).isEqualTo(200);
    }

    /**
     * 🔴 <b>명부에 없는 번호는 경로 조립에 닿지 않는다.</b> {@code DefaultUriBuilderFactory.path}는
     * {@code ..}를 글자 그대로 통과시키므로(실측) 인코딩은 방어가 아니다 — 막는 것은 자격 판정이고,
     * 그것이 <b>수집기 호출보다 앞</b>이라는 것을 {@code callCount()==0}이 잰다.
     */
    @Test
    void 명부에_없는_번호는_수집기에_닿지_않는다() throws Exception {
        볼_수_있다("OWNER");
        COLLECTOR.respondWith(200, "{\"leaked\":true}");

        HttpResponse<String> 탈출_시도 = 부른다("..etc", "chat-messages");
        assertThat(탈출_시도.statusCode()).isEqualTo(404);
        assertThat(탈출_시도.body()).contains("broadcast_not_found");
        assertThat(COLLECTOR.callCount()).as("명부에 없는 번호가 수집기까지 갔다").isZero();

        // 진짜 구분자를 섞으면 우리 코드에 닿기도 전에 톰캣이 거절한다(실측: 400,
        // rejectEncodedPathSeparators 기본값). 그래서 이 갈래는 「우리가 막는다」의 근거가
        // 못 된다 — 위 404가 근거다. 여기서는 「어느 쪽으로 죽든 수집기에는 안 간다」만 잰다.
        HttpResponse<String> 인코딩_탈출 = 부른다("..%2F..%2Factuator%2Fhealth", "chat-messages");
        assertThat(인코딩_탈출.statusCode()).isIn(400, 404);
        assertThat(COLLECTOR.callCount()).isZero();
    }

    /**
     * 🔴 <b>브라우저가 {@code channelId}를 정하면 안 된다</b> — 그 값이 수집기에서 시차 보정값을
     * 고르는 열쇠다. 허용 목록 밖은 문 셋 어디서도 안 넘어간다.
     */
    @ParameterizedTest
    @CsvSource({"chat-messages,from", "chat-chart,from", "broadcast-info,since"})
    void 허용_목록_밖의_쿼리는_안_넘어간다(String 창구, String 허용된_칸) throws Exception {
        볼_수_있다("OWNER");
        COLLECTOR.respondWith("/internal/streams/" + 내_방송 + "/" + 창구, 200, "{}");

        assertThat(부른다(내_방송, 창구 + "?" + 허용된_칸 + "=2026-09-01T00:00:00Z&channelId=남의채널")
                .statusCode()).isEqualTo(200);

        assertThat(COLLECTOR.lastQuery())
                .as("프론트가 보정값 열쇠를 정하게 됐다").doesNotContain("channelId")
                // 양성 대조 — 아무것도 안 넘기는 구현에도 위 단언은 초록이다.
                .as("허용 목록 안의 칸까지 사라졌다 — 걸러내기가 통째로 과하다")
                .contains(허용된_칸 + "=2026-09-01T00:00:00Z");
    }

    /**
     * 🔴 <b>문마다 허용 목록이 다르다.</b> 한 목록으로 합치면 차트에 {@code cursor}가, 목록에
     * {@code bucket}이 넘어가고 수집기가 400으로 답한다 — 그 400은 프론트가 고칠 수 없다.
     */
    @Test
    void 문마다_받는_칸이_다르다() throws Exception {
        볼_수_있다("OWNER");
        COLLECTOR.respondWith(200, "{}");

        부른다(내_방송, "chat-chart?bucket=30&cursor=h:1&limit=50");
        assertThat(COLLECTOR.lastQuery()).contains("bucket=30")
                .as("차트에 목록 칸이 넘어갔다").doesNotContain("cursor").doesNotContain("limit");

        부른다(내_방송, "chat-messages?cursor=h:1&bucket=30");
        assertThat(COLLECTOR.lastQuery()).contains("cursor=")
                .as("목록에 차트 칸이 넘어갔다").doesNotContain("bucket");

        부른다(내_방송, "broadcast-info?since=2026-09-01T00:00:00Z&from=2026-09-01T00:00:00Z");
        assertThat(COLLECTOR.lastQuery()).contains("since=")
                .as("방송 정보 창구는 since 하나만 받는다").doesNotContain("from=");
    }

    /**
     * 503 둘의 이유가 본문에서 갈려야 한다 — 「자격을 못 물었다」는 잠시 뒤 다시,
     * 「수집기가 아프다」는 채팅만 안 보인다는 뜻이라 화면 안내가 다르다.
     */
    @ParameterizedTest
    @ValueSource(strings = {"chat-messages", "chat-chart", "broadcast-info"})
    void auth가_죽으면_authorization_수집기가_죽으면_collector로_갈린다(String 창구) throws Exception {
        AUTH.respondWith(RESOLVE, 500, "");
        HttpResponse<String> auth장애 = 부른다(내_방송, 창구);
        assertThat(auth장애.statusCode()).isEqualTo(503);
        assertThat(auth장애.body()).contains("authorization_unavailable").doesNotContain("collector_unavailable");

        볼_수_있다("OWNER");
        // 수집기는 답을 안 걸어 둔 상태 그대로다 — 기본이 503이다.
        HttpResponse<String> 수집기장애 = 부른다(내_방송, 창구);
        assertThat(수집기장애.statusCode()).isEqualTo(503);
        assertThat(수집기장애.body()).contains("collector_unavailable").doesNotContain("authorization_unavailable");
    }

    /**
     * 🔴 <b>404 바닥이 이 문에도 걸렸는가.</b> 「없는 방송」은 명부 조회 하나로 끝나고 「자격 없음」은
     * auth 왕복을 태운다 — 본문이 같아도 <b>시간이 갈리면</b> 남의 방송 번호를 넣어 보는 것만으로
     * 실재를 안다(세그먼트 문에서 1.5ms 대 4.4ms 실측, 오독 0건).
     *
     * <p><b>{@link NotFoundFloor#mark}를 지우면 이 시험만 빨간불이다</b> — 지우고 돌려서 확인했다.
     * 나머지 열하나는 전부 초록이었다.
     *
     * <p>가장 빠른 값으로 잰다 — 잡음은 느린 쪽으로만 붙으므로 최솟값이 참값에 가장 가깝다.
     */
    @Test
    void 없는_방송의_404도_바닥_뒤에_나간다() throws Exception {
        볼_수_없다();

        double 가장_빠른_ms = Double.MAX_VALUE;
        for (int i = 0; i < 5; i++) {
            long 시작 = System.nanoTime();
            HttpResponse<String> 응답 = 부른다(없는_방송, "chat-messages");
            가장_빠른_ms = Math.min(가장_빠른_ms, (System.nanoTime() - 시작) / 1_000_000.0);
            assertThat(응답.statusCode()).isEqualTo(404);
        }

        assertThat(가장_빠른_ms)
                .as("바닥이 안 걸렸다 — 이 404는 auth를 안 태운 만큼 빨라서 실재가 샌다")
                .isGreaterThanOrEqualTo(NotFoundFloor.FLOOR.toMillis());
    }

    // ── 도우미 ──────────────────────────────────────────────────

    private void 볼_수_있다(String relation) {
        AUTH.respondWith(RESOLVE, 200, "{\"relation\":\"%s\"}".formatted(relation));
    }

    private void 볼_수_없다() {
        AUTH.respondWith(RESOLVE, 200, "{\"relation\":\"NONE\"}");
    }

    /**
     * 본문이 SSE가 아니라 끝이 있는 JSON이라 {@code send}로 받아도 안 매달린다
     * ({@code StreamAccessTest}가 데인 자리와 다른 점). 그래도 시한을 건다 — 결함이 들어와
     * 응답이 안 오면 <b>빨간불이 아니라 멈춤</b>이 되고 전수 실행이 통째로 선다.
     */
    private HttpResponse<String> 부른다(String streamId, String 창구와_쿼리) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/clip/broadcasts/"
                        + streamId + "/" + 창구와_쿼리))
                .header("Authorization", "Bearer " + TestTokens.access(요청자))
                .timeout(Duration.ofSeconds(30))
                .GET().build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    private void 방송을_넣는다(String streamId) {
        jdbc.update("""
                        INSERT INTO broadcasts (stream_id, streamer_id, status, started_at, last_sequence)
                        VALUES (?, ?, 'live', ?, 1)""",
                streamId, TestIds.STREAMER,
                OffsetDateTime.ofInstant(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC));
    }
}
