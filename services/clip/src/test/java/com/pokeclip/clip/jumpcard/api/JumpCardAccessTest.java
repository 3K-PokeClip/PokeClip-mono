package com.pokeclip.clip.jumpcard.api;

import com.pokeclip.clip.jumpcard.JumpCardService;
import com.pokeclip.clip.support.IntegrationTestSupport;
import com.pokeclip.clip.support.NotFoundFloor;
import com.pokeclip.clip.support.TestIds;
import com.pokeclip.clip.support.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * <b>카드를 만지는 문 넷에 붙은 자격 판정</b>(집기·놓기·숨기기·되돌리기).
 *
 * <p>POK-174 전까지 이 넷은 「토큰이 유효한 사람인가」까지만 봤다 — 로그인만 하면 남의 방송
 * 카드를 집고 숨길 수 있었고, 커밋되는 {@code services/README.md}에 「알려진 구멍」으로 적혀
 * 있던 자리다.
 *
 * <p><b>가짜 자격 창구를 Mockito로 갈아 끼우지 않는다</b>(스킬 문항 2). 진짜로 듣는 소켓으로
 * 가고, <b>답을 안 걸어 둔 시험은 503을 받는다</b>.
 *
 * <p>🔴 <b>요청자 번호가 {@link TestIds#STREAMER}와 다르다.</b> 같으면 auth에 안 묻고 문자열만
 * 비교하는 구현에서도 초록이 된다.
 */
@AutoConfigureMockMvc
class JumpCardAccessTest extends IntegrationTestSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String RESOLVE = "/internal/editor-delegations/resolve";

    /** JWT {@code sub}. 방송 픽스처의 스트리머 번호와 <b>다른 사람</b>이다. */
    private static final String 요청자 = "4181";

    /** 남남. 자격이 없는 쪽도 실재하는 회원이어야 판정이 실제로 돈다. */
    private static final String 남남 = "4182";

    private static final String 내_방송 = "s-card-access";

    /** 어떤 카드도 이 번호로 존재하지 않는다. 「없는 카드」 갈래를 만든다. */
    private static final long 없는_카드 = 987_654_321L;

    private static final long 바닥_ms = NotFoundFloor.FLOOR.toMillis();

    /** 가짜 자격 창구에 일부러 심는 지연. <b>바닥의 절반</b>이라 바닥이 덮기로 한 범위 안이다. */
    private static final Duration 느린_창구 = NotFoundFloor.FLOOR.dividedBy(2);

    /** 근거는 {@code JumpCardListControllerTest.표본_수}와 같다 — 조금 늘리는 것으로는 안 됐다. */
    private static final int 표본_수 = 15;

    private final MockMvc mvc;
    private final JumpCardService service;
    private final JdbcTemplate jdbc;

    JumpCardAccessTest(MockMvc mvc, JumpCardService service, JdbcTemplate jdbc) {
        this.mvc = mvc;
        this.service = service;
        this.jdbc = jdbc;
    }

    @BeforeEach
    void 앞_테스트의_흔적을_지운다() {
        방송과_카드를_비운다(jdbc);
        방송을_넣는다();
    }

    // ── 거절 ────────────────────────────────────────────────────

    /**
     * <b>양성 대조가 같은 시험 안에 있다</b>(스킬 문항 5) — 같은 카드·같은 문에서 자격만 바꿔 200이
     * 나오는 것을 보지 않으면, 404가 경로 오타나 픽스처 누락이어도 초록이다.
     */
    @Test
    void 관계없는_사람이_남의_카드를_집으면_404고_자격을_주면_같은_카드가_200이다() throws Exception {
        long id = 카드();

        볼_수_없다();
        MvcResult 거절 = 문(HttpMethod.POST, "/claim", id, 남남);
        assertThat(거절.getResponse().getStatus()).isEqualTo(404);
        assertThat(AUTH.callCount()).as("자격 창구를 안 거쳤다 — 이 404는 판정이 낸 것이 아니다").isEqualTo(1);

        볼_수_있다("OWNER");
        MvcResult 통과 = 문(HttpMethod.POST, "/claim", id, 요청자);
        assertThat(통과.getResponse().getStatus())
                .as("자격을 줘도 안 열린다 — 위 404는 판정이 아니라 다른 이유였다. 본문=%s", 본문(통과))
                .isEqualTo(200);
    }

    /**
     * 🔴 <b>이것이 판정을 맨 앞에 둔 이유다.</b> 남이 잡은 카드를 집으려 하면 409와 함께
     * <b>현재 카드 스냅샷</b>이 나간다 — 거기에 누가 잡고 있는지가 실린다. 판정을 뒤에 두면
     * 남남이 그것을 받는다.
     *
     * <p><b>{@code claimedBy}만 보지 않는다</b>(스킬 문항 5) — 다른 칸으로 새는 것을 놓친다.
     * <b>본문 전체가 「없는 카드」의 404와 바이트로 같은지</b>를 본다.
     */
    @Test
    void 관계없는_사람에게는_누가_잡고_있는지가_안_실린다() throws Exception {
        long id = 카드();
        볼_수_있다("OWNER");
        assertThat(문(HttpMethod.POST, "/claim", id, 요청자).getResponse().getStatus())
                .as("먼저 잡혀 있어야 409 갈래를 지난다").isEqualTo(200);

        볼_수_없다();
        MvcResult 남남의_응답 = 문(HttpMethod.POST, "/claim", id, 남남);
        MvcResult 없는_카드_응답 = 문(HttpMethod.POST, "/claim", 없는_카드, 남남);

        assertThat(남남의_응답.getResponse().getStatus())
                .as("409면 본문에 점유자가 실려 나간다").isEqualTo(404);
        assertThat(본문(남남의_응답))
                .as("「자격 없는 카드」와 「없는 카드」의 본문이 갈리면 번호를 훑는 것만으로 실재를 안다")
                .isEqualTo(본문(없는_카드_응답))
                .isEqualTo("{\"error\":\"jump_card_not_found\"}");
    }

    @Test
    void 관계없는_사람이_숨기거나_되돌리면_404다() throws Exception {
        long id = 카드();
        볼_수_없다();

        assertThat(문(HttpMethod.POST, "/hide", id, 남남).getResponse().getStatus()).isEqualTo(404);
        assertThat(문(HttpMethod.DELETE, "/hide", id, 남남).getResponse().getStatus()).isEqualTo(404);

        assertThat(카드_한_장())
                .as("거절인데 카드가 바뀌었다 — 판정이 쓰기보다 뒤에 있다")
                .containsEntry("hidden", false);
    }

    /**
     * 403이 아니라 404다 — 403은 「그 카드는 있는데 네 것이 아니다」라고 말해 <b>실재를 알려 준다</b>.
     * 자격이 없는 사람에게는 애초에 그 카드가 안 보여야 한다.
     */
    @Test
    void 관계없는_사람이_놓으면_403이_아니라_404다() throws Exception {
        long id = 카드();
        볼_수_있다("OWNER");
        문(HttpMethod.POST, "/claim", id, 요청자);

        볼_수_없다();
        MvcResult 응답 = 문(HttpMethod.DELETE, "/claim", id, 남남);

        assertThat(응답.getResponse().getStatus()).isEqualTo(404);
        assertThat(본문(응답)).doesNotContain("not_claim_owner");
        assertThat(카드_한_장())
                .as("거절인데 점유가 풀렸다 — 판정이 쓰기보다 뒤에 있다")
                .containsEntry("claimedBy", 요청자);
    }

    /**
     * 돈 내는 쪽(스트리머)과 매일 쓰는 쪽(전담 편집자)이 다르다. {@code OWNER}와 {@code EDITOR}를
     * 가르지 않는다는 PRD 결정이 실제로 코드에 있는지를 잰다 — <b>둘의 결과를 서로 비교</b>한다.
     * 각각 따로 단언하면 한쪽이 바뀔 때 그 시험만 고쳐지고 갈림이 안 잡힌다.
     */
    @Test
    void 편집자는_넷을_다_쓴다_스트리머와_결과가_같다() throws Exception {
        볼_수_있다("OWNER");
        List<Integer> 주인 = 문_넷을_지난다();

        볼_수_있다("EDITOR");
        List<Integer> 편집자 = 문_넷을_지난다();

        assertThat(편집자).as("편집자가 카드를 못 만지면 이 제품이 빈 화면이다").isEqualTo(주인);
        assertThat(주인).as("주인마저 못 지났다면 위 비교는 「둘 다 막혔다」로도 참이다")
                .containsExactly(200, 200, 200, 204);
    }

    /**
     * 🔴 <b>기존 검사는 그대로 산다.</b> 막는 것이 다르다 — 하나는 「이 방송을 볼 자격」,
     * 하나는 「이 카드를 잡은 사람이 너인가」. 자격 판정이 후자를 삼키면 남의 점유를 뺏는다.
     */
    @Test
    void 자격이_있어도_내가_안_잡은_카드를_놓으면_여전히_403이다() throws Exception {
        long id = 카드();
        볼_수_있다("OWNER");
        문(HttpMethod.POST, "/claim", id, 요청자);

        MvcResult 응답 = 문(HttpMethod.DELETE, "/claim", id, 남남);

        assertThat(응답.getResponse().getStatus()).isEqualTo(403);
        assertThat(본문(응답)).contains("not_claim_owner");
        assertThat(카드_한_장()).containsEntry("claimedBy", 요청자);
    }

    /** 넷 <b>전부</b>를 재는 이유 — 하나만 재면 나머지 셋에 판정을 안 붙여도 초록이다. */
    @Test
    void auth를_못_물으면_넷이_전부_503이다() throws Exception {
        long id = 카드();
        AUTH.respondWith(RESOLVE, 500, "");

        for (Object[] 문 : new Object[][]{
                {HttpMethod.POST, "/claim"}, {HttpMethod.DELETE, "/claim"},
                {HttpMethod.POST, "/hide"}, {HttpMethod.DELETE, "/hide"}}) {
            MvcResult 응답 = 문((HttpMethod) 문[0], (String) 문[1], id, 요청자);
            assertThat(응답.getResponse().getStatus()).as("%s %s", 문[0], 문[1]).isEqualTo(503);
            assertThat(본문(응답)).as("%s %s", 문[0], 문[1]).contains("authorization_unavailable");
        }
        assertThat(AUTH.callCount()).as("창구를 안 두드리고 503이면 다른 이유다").isEqualTo(4);
    }

    /**
     * 「없는 카드」는 DB 조회만, 「자격 없음」은 auth 왕복 — 갈래가 둘이 됐다.
     *
     * <p><b>지연을 안 심으면 자동 초록이다</b> — 실제 왕복 차이는 잡음에 묻힌다.
     */
    @Test
    void auth가_느려도_없는_카드와_자격없음이_같은_시각에_나간다() throws Exception {
        long id = 카드();
        볼_수_없다();
        AUTH.holdFor(느린_창구);
        try {
            가장_빠른_404_ms(1, id);   // 워밍업 — 첫 요청만 유독 느리다
            double 자격_없음 = 가장_빠른_404_ms(표본_수, id);
            double 없는_것 = 가장_빠른_404_ms(표본_수, 없는_카드);

            assertThat(AUTH.callCount()).as("느린 창구를 한 번도 안 지났다 — 잴 차이 자체가 없었다").isPositive();
            assertThat(없는_것).as("바닥이 통째로 안 걸렸다 — 아래 비교가 무의미하다")
                    .isGreaterThanOrEqualTo(바닥_ms);
            assertThat(Math.abs(자격_없음 - 없는_것))
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

    /** 집기 → 숨기기 → 되돌리기 → 놓기. 상태 코드만 모은다 — 카드 번호·시각은 회차마다 다르다. */
    private List<Integer> 문_넷을_지난다() throws Exception {
        long id = 카드();
        List<Integer> 결과 = new ArrayList<>();
        결과.add(문(HttpMethod.POST, "/claim", id, 요청자).getResponse().getStatus());
        결과.add(문(HttpMethod.POST, "/hide", id, 요청자).getResponse().getStatus());
        결과.add(문(HttpMethod.DELETE, "/hide", id, 요청자).getResponse().getStatus());
        결과.add(문(HttpMethod.DELETE, "/claim", id, 요청자).getResponse().getStatus());
        jdbc.update("DELETE FROM jump_cards");
        return 결과;
    }

    private MvcResult 문(HttpMethod method, String 끝, long id, String 사람) throws Exception {
        return mvc.perform(request(method, "/api/clip/jump-cards/" + id + 끝)
                .header("Authorization", "Bearer " + TestTokens.access(사람))).andReturn();
    }

    private String 본문(MvcResult 응답) throws Exception {
        return 응답.getResponse().getContentAsString();
    }

    /**
     * 같은 요청을 {@code 횟수}번 재서 <b>가장 빠른</b> 응답 시간(ms)을 준다. 지연 잡음은 느린 쪽으로만
     * 붙으므로 최솟값이 참값에 가장 가깝다. 매 회 404를 확인하는 것은 갈래가 바뀌어 엉뚱한 응답을
     * 재는 일이 없게 하려는 것이다.
     */
    private double 가장_빠른_404_ms(int 횟수, long id) throws Exception {
        double 최소 = Double.MAX_VALUE;
        for (int i = 0; i < 횟수; i++) {
            long 시작 = System.nanoTime();
            MvcResult 응답 = 문(HttpMethod.POST, "/claim", id, 남남);
            최소 = Math.min(최소, (System.nanoTime() - 시작) / 1_000_000.0);
            assertThat(응답.getResponse().getStatus()).as("본문=%s", 본문(응답)).isEqualTo(404);
        }
        return 최소;
    }

    /** 「거절인데 표가 바뀌었나」를 보는 자리. 응답만 보면 쓰기가 이미 끝난 뒤의 404를 못 가른다. */
    private java.util.Map<String, Object> 카드_한_장() {
        List<java.util.Map<String, Object>> rows = jdbc.query(
                "SELECT claimed_by, hidden_at FROM jump_cards ORDER BY id",
                (rs, i) -> {
                    java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("claimedBy", rs.getString("claimed_by"));
                    row.put("hidden", rs.getTimestamp("hidden_at") != null);
                    return row;
                });
        assertThat(rows).as("카드가 정확히 한 장이어야 이 확인이 뜻을 갖는다").hasSize(1);
        return rows.get(0);
    }

    private long 카드() {
        return service.record(내_방송, new HighlightRequest("evt-" + System.nanoTime(), "auto",
                5_043_000L, new HighlightRequest.Window(5_020_000L, 5_062_000L), 97,
                MAPPER.readTree("{\"multiplier\":4.2}"))).card().id();
    }

    private void 방송을_넣는다() {
        jdbc.update("""
                        INSERT INTO broadcasts (stream_id, streamer_id, status, started_at, last_sequence)
                        VALUES (?, ?, 'live', ?, 1)""",
                내_방송, TestIds.STREAMER,
                OffsetDateTime.ofInstant(Instant.parse("2026-08-26T00:00:00Z"), ZoneOffset.UTC));
    }
}
