package com.pokeclip.clip.playback.api;

import com.pokeclip.clip.support.IntegrationTestSupport;
import com.pokeclip.clip.support.NotFoundFloor;
import com.pokeclip.clip.support.TestIds;
import com.pokeclip.clip.support.TestPlaybackKeys;
import com.pokeclip.clip.support.TestTokens;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 영상 출입증 문(POK-122). <b>자격 판정은 다른 문 열하나와 같은 판정기</b>를 쓰고, 이 시험이
 * 따로 재는 것은 「나가는 쿠키가 CloudFront가 통과시킬 쿠키인가」다 — 정책을 풀어 범위·만료를
 * 읽고 서명을 <b>공개키로 검증</b>한다. 「Set-Cookie가 셋 있다」만 재면 아무 문자열이나 초록이다.
 *
 * <p>🔴 <b>출입증 재료는 이 클래스가 자기 {@code @DynamicPropertySource}로 채운다</b> — 그래서
 * 컨텍스트가 하나 더 생긴다. {@link IntegrationTestSupport}에 두면 나머지 컨텍스트 전부가 「켜짐」이
 * 되어 꺼짐(503) 갈래를 잴 자리가 없다({@code PlaybackAccessDisabledTest}가 기본 컨텍스트에서 잰다).
 *
 * <p>요청자 번호가 {@link TestIds#STREAMER}와 다르다 — 같으면 auth에 안 묻는 구현도 초록이다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PlaybackAccessControllerTest extends IntegrationTestSupport {

    private static final String RESOLVE = "/internal/editor-delegations/resolve";
    private static final String 요청자 = "4182";
    private static final String 내_방송 = "s-play";
    private static final String 없는_방송 = "s-play-없음";
    private static final String BASE = "https://media.test";
    private static final String DOMAIN = "media.test";
    private static final Duration TTL = Duration.ofMinutes(60);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final int port;
    private final JdbcTemplate jdbc;

    PlaybackAccessControllerTest(@LocalServerPort int port, JdbcTemplate jdbc) {
        this.port = port;
        this.jdbc = jdbc;
    }

    @DynamicPropertySource
    static void playbackProperties(DynamicPropertyRegistry registry) {
        registry.add("pokeclip.playback.key-pair-id", () -> TestPlaybackKeys.KEY_PAIR_ID);
        registry.add("pokeclip.playback.private-key-pem", TestPlaybackKeys::privateKeyPem);
        registry.add("pokeclip.playback.resource-base-url", () -> BASE);
        registry.add("pokeclip.playback.cookie-domain", () -> DOMAIN);
        registry.add("pokeclip.playback.ttl", () -> TTL.toString());
    }

    @BeforeEach
    void 앞_테스트의_흔적을_지운다() {
        방송과_카드를_비운다(jdbc);
        방송을_넣는다(내_방송);
    }

    /**
     * <b>양성 대조가 서명 검증이다.</b> 정책의 범위가 그 스트리머 경로이고 만료가 본문의 만료와
     * 같으며, 서명이 공개키로 풀린다 — 셋 중 하나라도 어긋나면 CloudFront는 403을 준다.
     */
    @Test
    void 자격이_있으면_이_스트리머_영상을_여는_서명_쿠키_셋을_준다() throws Exception {
        볼_수_있다("EDITOR");
        Instant 부르기_전 = Instant.now();

        HttpResponse<String> 응답 = 부른다(내_방송);

        assertThat(응답.statusCode()).as("본문=%s", 응답.body()).isEqualTo(200);
        JsonNode 본문 = MAPPER.readTree(응답.body());
        assertThat(본문.get("streamId").asString()).isEqualTo(내_방송);
        assertThat(본문.get("resource").asString()).isEqualTo(BASE + "/*/" + 내_방송 + "/*");
        Instant 만료 = Instant.parse(본문.get("expiresAt").asString());
        assertThat(만료).isBetween(부르기_전.plus(TTL).minusSeconds(5), Instant.now().plus(TTL).plusSeconds(5));

        Map<String, String> 쿠키 = 쿠키_값(응답);
        assertThat(쿠키.keySet()).containsExactlyInAnyOrder("CloudFront-Policy", "CloudFront-Signature", "CloudFront-Key-Pair-Id");
        assertThat(쿠키.get("CloudFront-Key-Pair-Id")).isEqualTo(TestPlaybackKeys.KEY_PAIR_ID);

        JsonNode 정책 = MAPPER.readTree(new String(TestPlaybackKeys.decodeCloudFront(쿠키.get("CloudFront-Policy")), StandardCharsets.UTF_8));
        JsonNode 조항 = 정책.get("Statement").get(0);
        assertThat(조항.get("Resource").asString())
                .as("범위가 이 스트리머 경로가 아니다 — 넓으면 남의 방송이 열리고 좁으면 카드마다 다시 받는다")
                .isEqualTo(BASE + "/*/" + 내_방송 + "/*");
        assertThat(조항.get("Condition").get("DateLessThan").get("AWS:EpochTime").asLong())
                .as("정책의 만료와 본문의 만료가 다르면 웹이 갱신 시점을 틀리게 잡는다")
                .isEqualTo(만료.getEpochSecond());
        assertThat(TestPlaybackKeys.verifies(쿠키.get("CloudFront-Policy"), 쿠키.get("CloudFront-Signature")))
                .as("서명이 우리 공개키로 안 풀린다 — CloudFront가 403을 준다").isTrue();
    }

    /**
     * 속성이 하나라도 빠지면 증상이 다르다 — {@code Path}가 좁으면 {@code /dvr}만 열리고,
     * {@code Domain}이 없으면 미디어 도메인에 안 붙고, {@code Max-Age}가 없으면 탭을 닫을 때 사라진다.
     */
    @Test
    void 쿠키_속성이_미디어_도메인_전체_경로_수명을_덮는다() throws Exception {
        볼_수_있다("OWNER");

        HttpResponse<String> 응답 = 부른다(내_방송);

        List<String> 줄들 = 응답.headers().allValues("set-cookie");
        assertThat(줄들).hasSize(3);
        for (String 줄 : 줄들) {
            assertThat(줄).contains("Path=/").contains("Secure").contains("HttpOnly")
                    .contains("SameSite=Lax").contains("Domain=" + DOMAIN)
                    .contains("Max-Age=" + TTL.toSeconds());
        }
    }

    /**
     * 🔴 <b>이 카드가 막는 것: 로그인만 하면 남의 방송 영상을 받는 것.</b> 404 본문이 「없는 방송」과
     * 같고 <b>쿠키가 한 개도 안 나간다</b> — 404인데 쿠키가 실리면 상태 코드만 거절이다.
     */
    @Test
    void 자격이_없으면_404이고_쿠키가_없다() throws Exception {
        볼_수_없다();

        HttpResponse<String> 자격_없음 = 부른다(내_방송);
        HttpResponse<String> 없는_것 = 부른다(없는_방송);

        assertThat(자격_없음.statusCode()).isEqualTo(404);
        assertThat(없는_것.statusCode()).isEqualTo(404);
        assertThat(자격_없음.body()).as("두 404가 갈리면 번호를 넣어 보는 것만으로 실재를 안다")
                .isEqualTo(없는_것.body()).contains("broadcast_not_found");
        assertThat(자격_없음.headers().allValues("set-cookie")).as("거절인데 출입증이 실렸다").isEmpty();
        assertThat(없는_것.headers().allValues("set-cookie")).isEmpty();

        // 양성 대조 — 자격만 바꾸면 같은 주소가 200이다.
        볼_수_있다("OWNER");
        assertThat(부른다(내_방송).statusCode()).isEqualTo(200);
    }

    /** 다른 문 열하나와 같은 바닥. {@link NotFoundFloor#mark}를 지우면 이 시험만 빨간불이다. */
    @Test
    void 없는_방송의_404도_바닥_뒤에_나간다() throws Exception {
        볼_수_없다();
        double 가장_빠른_ms = Double.MAX_VALUE;
        for (int i = 0; i < 5; i++) {
            long 시작 = System.nanoTime();
            assertThat(부른다(없는_방송).statusCode()).isEqualTo(404);
            가장_빠른_ms = Math.min(가장_빠른_ms, (System.nanoTime() - 시작) / 1_000_000.0);
        }
        assertThat(가장_빠른_ms).as("바닥이 안 걸렸다 — auth를 안 태운 만큼 빨라서 실재가 샌다")
                .isGreaterThanOrEqualTo(NotFoundFloor.FLOOR.toMillis());
    }

    @Test
    void auth에_못_물으면_503이고_쿠키가_없다() throws Exception {
        AUTH.respondWith(RESOLVE, 500, "");

        HttpResponse<String> 응답 = 부른다(내_방송);

        assertThat(응답.statusCode()).isEqualTo(503);
        assertThat(응답.body()).contains("authorization_unavailable").doesNotContain("playback_signing");
        assertThat(응답.headers().allValues("set-cookie")).isEmpty();
    }

    @Test
    void 토큰이_없으면_401이고_auth에_안_묻는다() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/clip/broadcasts/" + 내_방송 + "/playback-access"))
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> 응답 = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(응답.statusCode()).isEqualTo(401);
        }
        assertThat(AUTH.callCount()).isZero();
    }

    /**
     * 🔴 <b>명부 값이라고 정책에 그대로 넣지 않는다.</b> 큐로 받은 방송 번호에 {@code *}가 섞이면
     * 정책 범위가 남의 방송까지 넓어진다. 자격은 통과했으니 404가 아니라 503이고, 로그에는
     * <b>값이 아니라 길이</b>만 남는다(값을 찍으면 로그 위조 통로가 된다).
     */
    @Test
    void 방송_번호에_와일드카드가_있으면_503이고_로그에_값이_안_남는다() throws Exception {
        String 위험한_번호 = "s-*";
        방송을_넣는다(위험한_번호);
        볼_수_있다("OWNER");

        try (LogCaptor logs = new LogCaptor()) {
            HttpResponse<String> 응답 = 부른다(위험한_번호);

            assertThat(응답.statusCode()).isEqualTo(503);
            assertThat(응답.body()).contains("playback_signing_unavailable");
            assertThat(응답.headers().allValues("set-cookie")).isEmpty();
            assertThat(logs.messages()).anyMatch(m -> m.contains("clip.playback.stream_id_unsafe"));
            assertThat(logs.messages()).noneMatch(m -> m.contains("clip.playback.stream_id_unsafe") && m.contains(위험한_번호));
        }
    }

    /** 서명 값이 로그에 한 줄 남으면 그 스트리머 영상이 60분 열린다. INFO 한 줄에는 번호·만료만 있다. */
    @Test
    void 발급_로그에_정책과_서명이_없다() throws Exception {
        볼_수_있다("OWNER");

        try (LogCaptor logs = new LogCaptor()) {
            HttpResponse<String> 응답 = 부른다(내_방송);
            Map<String, String> 쿠키 = 쿠키_값(응답);

            assertThat(logs.messages()).anyMatch(m -> m.contains("clip.playback.issued") && m.contains(내_방송));
            String 전부 = String.join("\n", logs.messages());
            assertThat(전부).doesNotContain(쿠키.get("CloudFront-Signature")).doesNotContain(쿠키.get("CloudFront-Policy"));
        }
    }

    @Test
    void GET은_없는_문이다() throws Exception {
        볼_수_있다("OWNER");
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/clip/broadcasts/" + 내_방송 + "/playback-access"))
                .header("Authorization", "Bearer " + TestTokens.access(요청자))
                .timeout(Duration.ofSeconds(30)).GET().build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            assertThat(client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(405);
        }
    }

    // ── 도우미 ──────────────────────────────────────────────────

    private void 볼_수_있다(String relation) {
        AUTH.respondWith(RESOLVE, 200, "{\"relation\":\"%s\"}".formatted(relation));
    }

    private void 볼_수_없다() {
        AUTH.respondWith(RESOLVE, 200, "{\"relation\":\"NONE\"}");
    }

    private HttpResponse<String> 부른다(String streamId) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/clip/broadcasts/" + streamId + "/playback-access"))
                .header("Authorization", "Bearer " + TestTokens.access(요청자))
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    /** {@code Set-Cookie} 줄에서 이름=값만. 속성은 {@link #쿠키_속성이_미디어_도메인_전체_경로_수명을_덮는다()}가 본다. */
    private static Map<String, String> 쿠키_값(HttpResponse<String> 응답) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String 줄 : 응답.headers().allValues("set-cookie")) {
            String 첫_조각 = 줄.split(";", 2)[0];
            int eq = 첫_조각.indexOf('=');
            out.put(첫_조각.substring(0, eq), 첫_조각.substring(eq + 1));
        }
        return out;
    }

    private void 방송을_넣는다(String streamId) {
        jdbc.update("""
                        INSERT INTO broadcasts (stream_id, streamer_id, status, started_at, last_sequence)
                        VALUES (?, ?, 'live', ?, 1)""",
                streamId, TestIds.STREAMER,
                OffsetDateTime.ofInstant(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC));
    }
}
