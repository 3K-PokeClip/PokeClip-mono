package com.pokeclip.clip.jumpcard.stream;

import com.pokeclip.clip.jumpcard.JumpCardService;
import com.pokeclip.clip.jumpcard.api.HighlightRequest;
import com.pokeclip.clip.support.IntegrationTestSupport;
import com.pokeclip.clip.support.NotFoundFloor;
import com.pokeclip.clip.support.SseReader;
import com.pokeclip.clip.support.TestIds;
import com.pokeclip.clip.support.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>통로(SSE)에 붙은 자격 판정과, 통로가 지난 카드를 더 이상 안 보낸다는 것.</b>
 *
 * <p>POK-174 전까지 이 문은 「토큰이 유효한 사람인가」까지만 봤다 — 로그인만 하면 남의 방송
 * 카드를 실시간으로 볼 수 있었고, 커밋되는 {@code services/README.md}에 「알려진 구멍」으로
 * 적혀 있던 자리다.
 *
 * <p><b>가짜 자격 창구를 Mockito로 갈아 끼우지 않는다.</b> 그렇게 하면 이 클래스가 「판정이 붙기
 * 전과 정확히 같은 것」을 재게 된다(스킬 문항 2). 진짜로 듣는 소켓({@link IntegrationTestSupport#AUTH})으로
 * 가고, <b>답을 안 걸어 둔 시험은 503을 받는다</b>.
 *
 * <p>🔴 <b>요청자 번호가 {@link TestIds#STREAMER}와 다르다.</b> 같으면 auth에 안 묻고 문자열만
 * 비교하는 구현에서도 초록이 된다.
 *
 * <p>🔴 <b>커넥션을 쥐지 않는가는 여기서 안 잰다</b> — 표집 장치가
 * {@code BroadcastListTransactionTest}에 있고, 그 클래스가 통로 갈래도 함께 잰다
 * ({@code 통로도_auth_왕복_동안_커넥션을_안_쥔다}). 쪼개면 그 클래스의 대부분인 도우미가 복사된다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StreamAccessTest extends IntegrationTestSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String RESOLVE = "/internal/editor-delegations/resolve";

    /** JWT {@code sub}. 방송 픽스처의 스트리머 번호와 <b>다른 사람</b>이고, 통로 자리가 사람 단위라 이 클래스 전용이다. */
    private static final String 요청자 = "4180";

    private static final String 내_방송 = "s-access";
    private static final String 없는_방송 = "s-access-없음";

    /** 404가 이보다 빨리 나가면 안 된다. 정본은 {@link NotFoundFloor#FLOOR} — 여기서 베끼지 않는다. */
    private static final long 바닥_ms = NotFoundFloor.FLOOR.toMillis();

    /** 가짜 자격 창구에 일부러 심는 지연. <b>바닥의 절반</b>이라 바닥이 덮기로 한 범위 안이다. */
    private static final Duration 느린_창구 = NotFoundFloor.FLOOR.dividedBy(2);

    /**
     * 차이를 잴 때 한 갈래를 몇 번 두드리나. 근거는 {@code JumpCardListControllerTest.표본_수}와 같다 —
     * <b>표본을 조금 늘리는 것으로는 안 되고</b>(3 → 6.723ms · 6 → <b>8.563ms</b> · 15 → 2.763ms) 15가 필요했다.
     */
    private static final int 표본_수 = 15;

    /**
     * 「안 온다」를 재는 대기. 이 값이 근거를 가지려면 <b>같은 대기로 오는 것</b>을 같은 시험에서
     * 봐야 한다(스킬 문항 4) — 그래서 카드 갈래가 부정·긍정을 한 시험에 담는다.
     */
    private static final Duration 대기 = Duration.ofSeconds(2);

    private final int port;
    private final JumpCardService service;
    private final JdbcTemplate jdbc;

    StreamAccessTest(@LocalServerPort int port, JumpCardService service, JdbcTemplate jdbc) {
        this.port = port;
        this.service = service;
        this.jdbc = jdbc;
    }

    @BeforeEach
    void 앞_테스트의_흔적을_지운다() {
        방송과_카드를_비운다(jdbc);
        방송을_넣는다(내_방송, "live");
    }

    // ── 자격 ────────────────────────────────────────────────────

    /**
     * <b>양성 대조가 같은 시험 안에 있다.</b> 같은 주소·같은 방송·같은 토큰에서 자격만 바꿔 200이
     * 나오는 것을 보지 않으면, 404가 경로 오타나 픽스처 누락이어도 초록이다(스킬 문항 5).
     *
     * <p>두 404의 <b>본문을 서로 비교</b>한다. 각각 따로 단언하면 한쪽이 바뀔 때 그 시험만 고쳐지고
     * 갈림이 안 잡힌다.
     */
    @Test
    void 관계없는_사람이_통로를_열면_404고_자격을_주면_같은_주소가_200이다() {
        볼_수_없다();

        String 자격_없음;
        try (SseReader reader = 연다(내_방송)) {
            assertThat(reader.statusCode()).isEqualTo(404);
            자격_없음 = reader.body();
        }
        assertThat(AUTH.callCount()).as("자격 창구를 안 거쳤다 — 이 404는 판정이 낸 것이 아니다").isEqualTo(1);

        String 없는_것;
        try (SseReader reader = 연다(없는_방송)) {
            assertThat(reader.statusCode()).isEqualTo(404);
            없는_것 = reader.body();
        }
        assertThat(자격_없음)
                .as("「자격 없음」과 「없는 방송」의 본문이 갈리면 방송 이름을 넣어 보는 것만으로 실재를 안다")
                .isEqualTo(없는_것)
                .contains("broadcast_not_found");

        볼_수_있다("OWNER");
        try (SseReader reader = 연다(내_방송)) {
            assertThat(reader.statusCode())
                    .as("자격을 줘도 안 열린다 — 위 404는 판정이 아니라 다른 이유였다. 본문=%s", reader.body())
                    .isEqualTo(200);
        }
    }

    /**
     * 돈 내는 쪽(스트리머)과 매일 쓰는 쪽(전담 편집자)이 다르다 — <b>편집자가 통로를 못 열면
     * 이 제품이 빈 화면이다.</b> {@code OWNER}와 {@code EDITOR}를 가르지 않는다는 PRD 결정을 잰다.
     */
    @Test
    void 편집자도_통로를_연다() {
        볼_수_있다("EDITOR");

        try (SseReader reader = 연다(내_방송)) {
            assertThat(reader.statusCode()).as("본문=%s", reader.body()).isEqualTo(200);
            assertThat(AUTH.lastPath()).isEqualTo(RESOLVE);
        }
    }

    /**
     * 503의 이유가 「자격을 못 물었다」인지 「연결 상한」인지 본문까지 봐야 갈린다(스킬 문항 5) —
     * 이 문은 상한에서도 503을 낸다.
     */
    @Test
    void auth를_못_물으면_503이고_본문이_상한과_구분된다() {
        AUTH.respondWith(RESOLVE, 500, "");

        try (SseReader reader = 연다(내_방송)) {
            assertThat(reader.statusCode()).isEqualTo(503);
            assertThat(reader.body())
                    .contains("authorization_unavailable")
                    .doesNotContain("stream_limit");
        }
        assertThat(AUTH.callCount()).as("창구를 안 두드리고 503이면 다른 이유다").isPositive();
    }

    // ── 초기 전송 ────────────────────────────────────────────────

    /**
     * 🔴 <b>이 시험은 전에 있던 시험의 반대다.</b> 지우지 않고 뒤집는 이유 — 지우면 통로가 지난
     * 카드를 다시 흘려도 아무도 모른다. 따라잡기는 목록 문({@code GET .../jump-cards})이 맡는다.
     *
     * <p><b>부정과 긍정이 한 시험에 있다</b>(스킬 문항 4). 「{@value #대기}만큼 기다렸는데 안 왔다」는
     * <b>더 늦게 오는 구현에서도 통과</b>하므로, 같은 대기로 <b>새 카드는 온다</b>는 것을 같이 재야
     * 그 대기 시간에 근거가 생긴다.
     *
     * <p>주석({@code :ok}·하트비트)은 {@code named()}가 안 센다 — 그래서 이 단언은 「이름 있는
     * 이벤트가 하나도 없다」를 정확히 잰다.
     */
    @Test
    void 통로를_열어도_기존_카드가_안_오고_그_뒤에_생긴_카드는_같은_대기_안에_온다() throws Exception {
        볼_수_있다("OWNER");
        service.record(내_방송, auto("evt-old-1", 1_000_000L));
        service.record(내_방송, auto("evt-old-2", 2_000_000L));
        service.record(내_방송, auto("evt-old-3", 3_000_000L));

        try (SseReader reader = 연다(내_방송)) {
            assertThat(reader.statusCode()).as("본문=%s", reader.body()).isEqualTo(200);
            assertThat(reader.await(1, Duration.ofSeconds(3)))
                    .as("주석조차 안 왔다 — 연결이 명부에 안 올랐다면 아래는 아무것도 안 잰다").isTrue();

            Thread.sleep(대기.toMillis());
            assertThat(reader.named())
                    .as("연결 직후 지난 카드가 나갔다 — 화면이 같은 카드를 목록과 통로 양쪽에서 받는다")
                    .isEmpty();

            // 같은 대기로 오는 것을 보여 위 대기에 근거를 준다.
            service.record(내_방송, auto("evt-new", 9_000_000L));
            assertThat(reader.awaitNamed(1, 대기))
                    .as("같은 대기 안에 새 카드도 안 왔다 — 위 「안 온다」가 아무것도 증명하지 못한다")
                    .isTrue();

            assertThat(카드창시작(reader))
                    .as("온 것은 연 뒤에 생긴 그 카드 하나여야 한다").containsExactly(9_000_000L);
        }
    }

    /**
     * 🔴 <b>초기 전송을 없애도 {@code ended}는 남는다.</b> 없애면 이미 끝난 방송에 붙은 화면이
     * 영영 그 상태로 남는다 — 연결은 토큰 만료까지 살아 있고 클라이언트는 끝난 줄 모른다.
     */
    @Test
    void 이미_끝난_방송의_통로를_열면_카드는_없고_종료_알림만_온다() {
        볼_수_있다("OWNER");
        방송을_넣는다("s-access-ended", "ended");
        service.record("s-access-ended", auto("evt-ended", 1_000_000L));

        try (SseReader reader = 연다("s-access-ended")) {
            assertThat(reader.statusCode()).as("본문=%s", reader.body()).isEqualTo(200);
            assertThat(reader.awaitName("ended", Duration.ofSeconds(3)))
                    .as("끝난 방송인데 종료 알림이 안 오면 그 화면은 영영 붙어 있는다").isTrue();
            assertThat(reader.named()).extracting(SseReader.Event::name)
                    .as("카드가 섞여 나왔다 — 초기 전송이 되살아났다").containsExactly("ended");
            assertThat(reader.awaitClosed(Duration.ofSeconds(3))).isTrue();
        }
    }

    // ── 404 두 갈래의 시각 ────────────────────────────────────────

    /**
     * 「없는 방송」은 명부 조회 하나로 끝나고 「자격 없음」은 auth 왕복을 태운다 — 본문이 같아도
     * <b>시간이 갈리면</b> 남의 방송 번호를 넣어 보는 것만으로 실재를 안다(세그먼트 문에서
     * 1,240회 실측 1.488ms 대 4.422ms, 오독 0건).
     *
     * <p><b>지연을 안 심으면 자동 초록이다</b> — 실제 왕복 차이는 잡음에 묻힌다. 그래서 창구에
     * {@link #느린_창구}를 심어 차이를 일부러 키우고, 바닥이 그것을 덮는지를 본다.
     */
    @Test
    void auth가_느려도_두_404가_같은_시각에_나간다() {
        볼_수_없다();
        AUTH.holdFor(느린_창구);
        try {
            가장_빠른_404_ms(1, 없는_방송);   // 워밍업 — 첫 요청만 유독 느리다
            double 없는_방송_ms = 가장_빠른_404_ms(표본_수, 없는_방송);
            double 자격_없음_ms = 가장_빠른_404_ms(표본_수, 내_방송);

            assertThat(AUTH.callCount()).as("느린 창구를 한 번도 안 지났다 — 잴 차이 자체가 없었다").isPositive();
            assertThat(없는_방송_ms).as("바닥이 통째로 안 걸렸다 — 아래 비교가 무의미하다")
                    .isGreaterThanOrEqualTo(바닥_ms);
            assertThat(Math.abs(자격_없음_ms - 없는_방송_ms))
                    .as("두 404의 시각이 심은 지연만큼 갈렸다 — 기준 시각이 갈림 뒤에 찍힌 것이다")
                    .isLessThan(느린_창구.toMillis() / 2.0);
        } finally {
            AUTH.holdFor(Duration.ZERO);
        }
    }

    // ── 도우미 ──────────────────────────────────────────────────

    private void 볼_수_있다(String relation) {
        AUTH.respondWith(RESOLVE, 200, "{\"relation\":\"%s\"}".formatted(relation));
    }

    private void 볼_수_없다() {
        AUTH.respondWith(RESOLVE, 200, "{\"relation\":\"NONE\"}");
    }

    private SseReader 연다(String streamId) {
        return new SseReader(url(streamId), Map.of("Authorization", "Bearer " + TestTokens.access(요청자)));
    }

    private String url(String streamId) {
        return "http://localhost:" + port + "/api/clip/broadcasts/" + streamId + "/events";
    }

    /**
     * 같은 요청을 {@code 횟수}번 재서 <b>가장 빠른</b> 응답 시간(ms)을 준다. 지연 잡음은 느린 쪽으로만
     * 붙으므로 최솟값이 참값에 가장 가깝다. 매 회 404를 확인하는 것은 갈래가 바뀌어 엉뚱한 응답을
     * 재는 일이 없게 하려는 것이다.
     *
     * <p>🔴 <b>{@code HttpClient.send}로 재면 결함이 들어왔을 때 시험이 영영 안 끝난다.</b>
     * {@code HttpRequest.timeout}은 <b>응답이 도착할 때까지</b>를 재지 <b>본문이 끝날 때까지</b>를
     * 재지 않는다 — 이 문이 404 대신 200을 주면 그때부터 본문은 <b>SSE 스트림</b>이라 끝나지 않는다.
     * 2026-08-26에 판정을 지우는 결함 주입에서 실제로 밟았다: 시한 30초를 걸어 뒀는데
     * {@code jstack}이 {@code HttpClientImpl.send}에서 <b>20분째 park</b>인 것을 보여 줬고,
     * 워커를 죽여야 끝났다. <b>빨간불이 아니라 멈춤은 아무것도 안 재는 것보다 나쁘다</b> —
     * 전수 실행이 통째로 선다.
     *
     * <p>그래서 {@link SseReader}로 잰다 — 생성자가 <b>헤더까지만</b> 막히고, 200이 와도
     * {@code close()}로 즉시 놓는다. 재는 대상(응답이 나가는 시각)은 같다.
     */
    private double 가장_빠른_404_ms(int 횟수, String streamId) {
        double 최소 = Double.MAX_VALUE;
        for (int i = 0; i < 횟수; i++) {
            long 시작 = System.nanoTime();
            try (SseReader reader = 연다(streamId)) {
                최소 = Math.min(최소, (System.nanoTime() - 시작) / 1_000_000.0);
                assertThat(reader.statusCode()).as("본문=%s", reader.body()).isEqualTo(404);
            }
        }
        return 최소;
    }

    private java.util.List<Long> 카드창시작(SseReader reader) {
        return reader.named().stream().filter(e -> "card".equals(e.name()))
                .map(e -> MAPPER.readTree(e.data()).get("window").get("startMs").asLong()).toList();
    }

    private void 방송을_넣는다(String streamId, String status) {
        jdbc.update("""
                        INSERT INTO broadcasts (stream_id, streamer_id, status, started_at, last_sequence)
                        VALUES (?, ?, ?, ?, 1)""",
                streamId, TestIds.STREAMER, status,
                OffsetDateTime.ofInstant(Instant.parse("2026-08-26T00:00:00Z"), ZoneOffset.UTC));
    }

    private HighlightRequest auto(String eventId, long start) {
        return new HighlightRequest(eventId, "auto", start + 23_000L,
                new HighlightRequest.Window(start, start + 42_000L), 97,
                MAPPER.readTree("{\"multiplier\":4.2}"));
    }
}
