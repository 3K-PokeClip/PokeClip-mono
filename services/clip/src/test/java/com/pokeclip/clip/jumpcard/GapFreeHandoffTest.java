package com.pokeclip.clip.jumpcard;

import com.pokeclip.clip.jumpcard.api.HighlightRequest;
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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>「통로 먼저, 목록 나중」이 화면의 계약이다.</b> 그 순서로 읽으면 카드가 하나도 안 빠지고,
 * 뒤집으면 빠진다 — 이 클래스가 그 둘을 나란히 잰다.
 *
 * <p>POK-174가 통로의 <b>초기 전송을 없앴다</b>. 그 전에는 연결 직후 지난 카드가 통째로 흘러
 * 나와서 순서가 아무래도 상관없었다. 지금은 통로가 <b>연 뒤에 생긴 것만</b> 보내므로,
 * 화면이 목록을 먼저 받고 통로를 나중에 열면 <b>그 사이에 생긴 카드가 어디에도 없다</b>.
 * 계약을 문서에만 적으면 다음 사람이 순서를 뒤집어도 아무 시험이 안 잡는다.
 *
 * <p><b>본 코드는 이 클래스 때문에 한 줄도 안 바뀐다.</b> 안 고쳐도 통과해야 태스크 6·7이 옳게
 * 된 것이다 — 통과하지 않으면 그 태스크로 돌아간다.
 *
 * <h2>두 시험은 짝이다</h2>
 * {@link #순서를_뒤집으면_카드가_빠진다()}가 <b>초록인 동안에만</b>
 * {@link #통로를_먼저_열면_사이에_생긴_카드가_안_빠진다()}가 「순서가 중요하다」를 증명한다.
 * 뒤엣것이 빨간불이면 통로가 다시 초기 전송을 하고 있다는 뜻이다(태스크 7 회귀).
 *
 * <p>🔴 <b>두 시험 모두 목록·통로 <u>한쪽만으로는 통과하지 못하게</u> 짰다.</b> 지시서의 얼개
 * (셋을 심고 → 통로 → 둘 더 심고 → 목록)는 <b>목록 하나로 다섯 장이 다 나온다</b> — 통로를
 * 통째로 죽여도 초록이다(스킬 문항 3). 그래서 마지막 한 장을 <b>목록을 받은 뒤에</b> 심어
 * 통로가 유일한 경로가 되게 했다. 장수(다섯)와 결론은 지시서와 같다.
 *
 * <p><b>자격 판정의 부정 갈래는 여기서 안 잰다</b> — 그것은 {@code StreamAccessTest}와
 * {@code JumpCardListControllerTest}가 문마다 따로 맡는다. 다만 <b>두 문이 실제로 판정을
 * 거쳤다</b>는 것은 {@link IntegrationTestSupport#AUTH} 호출 수로 여기서도 확인한다 —
 * 0이면 이 클래스가 판정 없는 경로를 재고 있는 것이다(스킬 문항 2).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GapFreeHandoffTest extends IntegrationTestSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String RESOLVE = "/internal/editor-delegations/resolve";

    /** 통로 자리는 사람 단위라 다른 클래스와 겹치면 남의 시험이 503을 맞는다 — 이 클래스 전용 번호다. */
    private static final String 요청자 = "4181";

    private static final String 방송 = "s-handoff";

    /**
     * 통로로 올 것을 기다리는 상한. <b>이 값에 근거를 주는 것은 「같은 대기로 실제로 오는 것」이다</b>
     * (스킬 문항 4) — 두 시험 모두 <b>안 오는 카드와 오는 카드를 같은 연결·같은 대기</b>로 재므로,
     * 이 시간이 짧아서 「안 왔다」가 나온 것이라면 오는 쪽도 같이 빨간불이 된다.
     */
    private static final Duration 대기 = Duration.ofSeconds(3);

    private final int port;
    private final JumpCardService service;
    private final JdbcTemplate jdbc;

    GapFreeHandoffTest(@LocalServerPort int port, JumpCardService service, JdbcTemplate jdbc) {
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
        // 답을 안 걸면 503이라 두 문이 다 안 열린다 — 이 클래스는 자격이 아니라 순서를 잰다.
        AUTH.respondWith(RESOLVE, 200, "{\"relation\":\"OWNER\"}");
    }

    /**
     * 화면이 하는 순서 그대로 — <b>통로를 먼저 열고 목록을 나중에 받는다</b>. 카드 다섯 장이
     * 세 시점에 흩어져 있어도 합치면 하나도 안 빠진다.
     *
     * <pre>
     *   a b c          통로를 열기 전        → 목록에만 있다
     *   ── 통로를 연다 ──
     *   d              열고 목록을 받기 전    → 양쪽에 다 있다(겹침)
     *   ── 목록을 받는다 ──
     *   e              목록을 받은 뒤        → 통로에만 있다
     * </pre>
     *
     * <p><b>세 시점이 다 필요하다.</b> {@code e}가 없으면 목록 한 번으로 다섯 장이 다 나와서
     * <b>통로가 죽어도 초록</b>이고, {@code a·b·c}가 없으면 통로 하나로 다 나온다.
     * {@code d}는 <b>겹침</b>이라 「번호로 중복을 지운다」가 빈말이 아니게 만든다 —
     * 겹치는 장이 없으면 합집합이 그냥 이어붙이기다.
     */
    @Test
    void 통로를_먼저_열면_사이에_생긴_카드가_안_빠진다() {
        long a = 심는다("evt-a", 1_000_000L);
        long b = 심는다("evt-b", 2_000_000L);
        long c = 심는다("evt-c", 3_000_000L);

        try (SseReader reader = 연다()) {
            assertThat(reader.statusCode()).as("통로가 안 열렸다. 본문=%s", reader.body()).isEqualTo(200);
            assertThat(reader.await(1, 대기))
                    .as("주석조차 안 왔다 — 연결이 명부에 안 올랐다면 아래는 아무것도 안 잰다").isTrue();

            long d = 심는다("evt-d", 4_000_000L);       // ① 틈: 통로를 연 뒤, 목록을 받기 전
            통로로_올_때까지(reader, d);

            List<Long> 목록 = 목록의_카드_번호();          // ② 목록을 받는다 — 여기가 목록의 시점이다

            long e = 심는다("evt-e", 5_000_000L);       // ③ 목록을 받은 뒤 — 통로가 유일한 경로다
            통로로_올_때까지(reader, e);

            List<Long> 통로 = 통로의_카드_번호(reader);

            assertThat(목록).as("목록이 열기 전 카드를 못 준다 — 그러면 합집합이 통로 하나짜리다")
                    .contains(a, b, c);
            assertThat(목록).as("목록을 받은 뒤에 생긴 카드가 목록에 있다 — 그러면 통로가 죽어도 이 시험은 초록이다")
                    .doesNotContain(e);
            assertThat(통로).as("통로가 e를 안 줬다 — 그 카드는 어디에도 없다")
                    .contains(e);
            assertThat(통로).as("통로가 열기 전 카드까지 줬다 — 초기 전송이 되살아났다(태스크 7 회귀)")
                    .doesNotContain(a, b, c);

            assertThat(목록).as("겹침이 없다 — 중복 제거가 아무것도 안 지운다").contains(d);
            assertThat(통로).as("겹침이 없다 — 중복 제거가 아무것도 안 지운다").contains(d);
            assertThat(목록.size() + 통로.size())
                    .as("두 쪽의 합이 다섯이면 겹친 장이 없었다는 뜻이다").isGreaterThan(5);

            assertThat(합친다(목록, 통로))
                    .as("합쳤는데 다섯 장이 아니다 — 빠졌거나(틈) 겹친 것이 두 번 셈됐다")
                    .containsExactlyInAnyOrder(a, b, c, d, e);
        }

        assertThat(AUTH.callCount())
                .as("두 문 중 하나가 자격 창구를 안 거쳤다 — 판정 없는 경로를 재고 있다")
                .isGreaterThanOrEqualTo(2);
    }

    /**
     * 🔴 <b>이 시험이 계약의 값어치를 잰다.</b> 순서를 뒤집으면 — 목록을 먼저 받고 통로를 나중에
     * 열면 — 그 사이에 생긴 카드가 <b>어디에도 없다</b>.
     *
     * <pre>
     *   a b c          → 목록에 있다
     *   ── 목록을 받는다 ──
     *   d              → 🔴 목록에도 통로에도 없다
     *   ── 통로를 연다 ──
     *   e              → 통로에 있다
     * </pre>
     *
     * <p><b>{@code doesNotContain}은 합집합이 비면 자동으로 참이다</b>(스킬 문항 3). 그래서
     * {@code a·b·c}로 목록 쪽이, {@code e}로 통로 쪽이 <b>살아 있다는 것</b>을 같은 시험에서
     * 먼저 보인다. {@code e}는 {@code d}와 <b>같은 연결·같은 대기</b>로 오므로, {@code d}가
     * 안 온 것이 「대기가 짧아서」가 아니라는 것까지 증명한다(문항 4).
     *
     * <p>이것이 빨간불이면 통로가 다시 초기 전송을 하고 있다는 뜻이다 — 그러면 {@code d}가
     * 통로로 흘러들어 합집합에 낀다.
     */
    @Test
    void 순서를_뒤집으면_카드가_빠진다() {
        long a = 심는다("evt-a", 1_000_000L);
        long b = 심는다("evt-b", 2_000_000L);
        long c = 심는다("evt-c", 3_000_000L);

        List<Long> 목록 = 목록의_카드_번호();              // ① 목록을 먼저 받는다
        assertThat(목록).as("목록이 비었다 — 아래 doesNotContain이 자동으로 참이 된다")
                .containsExactlyInAnyOrder(a, b, c);

        long d = 심는다("evt-d", 4_000_000L);           // ② 틈: 목록 뒤, 통로 앞

        try (SseReader reader = 연다()) {                // ③ 통로를 나중에 연다
            assertThat(reader.statusCode()).as("통로가 안 열렸다. 본문=%s", reader.body()).isEqualTo(200);
            assertThat(reader.await(1, 대기))
                    .as("주석조차 안 왔다 — 연결이 명부에 안 올랐다면 아래는 아무것도 안 잰다").isTrue();

            long e = 심는다("evt-e", 5_000_000L);
            통로로_올_때까지(reader, e);

            List<Long> 통로 = 통로의_카드_번호(reader);
            assertThat(통로).as("같은 대기 안에 e도 안 왔다 — 아래 「d가 없다」가 아무것도 증명하지 못한다")
                    .contains(e);

            List<Long> 합 = 합친다(목록, 통로);
            assertThat(합)
                    .as("틈에서 생긴 카드가 합집합에 있다 — 통로가 초기 전송을 하고 있다(태스크 7 회귀)")
                    .doesNotContain(d);
            assertThat(합)
                    .as("빠지는 것이 그 한 장뿐이어야 이 시험이 「순서」를 재는 것이다")
                    .containsExactlyInAnyOrder(a, b, c, e);
        }
    }

    // ── 도우미 ──────────────────────────────────────────────────

    /**
     * 카드 한 장을 만들고 <b>번호</b>를 준다. JDBC로 직접 넣지 않는 것이 핵심이다 —
     * 표에 직접 꽂으면 {@code publishAfterCommit}을 안 지나 <b>통로로 아무것도 안 나간다</b>.
     * 그러면 「통로가 안 준다」가 언제나 참이 되어 두 시험이 통째로 무의미해진다.
     */
    private long 심는다(String eventId, long start) {
        return service.record(방송, new HighlightRequest(eventId, "auto", start + 23_000L,
                new HighlightRequest.Window(start, start + 42_000L), 97,
                MAPPER.readTree("{\"multiplier\":4.2}"))).card().id();
    }

    private SseReader 연다() {
        return new SseReader("http://localhost:" + port + "/api/clip/broadcasts/" + 방송 + "/events",
                Map.of("Authorization", "Bearer " + TestTokens.access(요청자)));
    }

    /**
     * 그 번호가 통로로 올 때까지 {@link #대기}만큼 기다린다. <b>기다린 결과를 여기서 단언하지
     * 않는다</b> — 부르는 쪽이 자기 문장으로 단언해야 실패 메시지가 무엇을 재던 것인지 말한다.
     *
     * <p>{@code awaitNamed(n, …)}을 안 쓰는 이유: 그것은 <b>개수</b>만 세므로 초기 전송이 되살아나
     * 엉뚱한 카드가 먼저 오면 곧바로 통과하고, 우리가 기다리던 장이 아직 안 온 채로 다음 줄이 돈다.
     */
    private void 통로로_올_때까지(SseReader reader, long id) {
        long 마감 = System.nanoTime() + 대기.toNanos();
        while (System.nanoTime() < 마감 && !통로의_카드_번호(reader).contains(id)) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** 통로로 온 {@code card} 이벤트의 번호. 주석(하트비트·{@code :ok})과 {@code ended}는 안 센다. */
    private List<Long> 통로의_카드_번호(SseReader reader) {
        List<Long> ids = new ArrayList<>();
        for (SseReader.Event event : reader.named()) {
            if ("card".equals(event.name())) {
                ids.add(MAPPER.readTree(event.data()).get("id").asLong());
            }
        }
        return ids;
    }

    private List<Long> 목록의_카드_번호() {
        HttpResponse<String> response;
        try {
            response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(
                                    "http://localhost:" + port + "/api/clip/broadcasts/" + 방송 + "/jump-cards"))
                            .header("Authorization", "Bearer " + TestTokens.access(요청자))
                            .timeout(Duration.ofSeconds(10))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        assertThat(response.statusCode()).as("목록 문이 200이 아니다: %s", response.body()).isEqualTo(200);
        JsonNode body = MAPPER.readTree(response.body());
        assertThat(body.path("nextCursor").isNull())
                .as("목록이 두 장으로 갈렸다 — 첫 장만 보면 「빠졌다」가 이어받기 때문인지 순서 때문인지 못 가른다")
                .isTrue();
        List<Long> ids = new ArrayList<>();
        body.path("cards").forEach(card -> ids.add(card.get("id").asLong()));
        return ids;
    }

    /** 두 쪽을 번호로 합치고 겹친 것을 지운다. 화면이 하는 일이 이것이다. */
    private List<Long> 합친다(List<Long> 목록, List<Long> 통로) {
        Set<Long> 합 = new LinkedHashSet<>(목록);
        합.addAll(통로);
        return List.copyOf(합);
    }
}
