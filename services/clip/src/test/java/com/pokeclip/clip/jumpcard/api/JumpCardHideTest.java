package com.pokeclip.clip.jumpcard.api;

import com.pokeclip.clip.jumpcard.JumpCardService;
import com.pokeclip.clip.support.IntegrationTestSupport;
import com.pokeclip.clip.support.NotFoundFloor;
import com.pokeclip.clip.support.TestIds;
import com.pokeclip.clip.support.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * <b>카드 가리기·되살리기 문</b>(POK-463). 가린 카드는 목록 기본값에서 빠지고 화면의 「숨김」 칸으로 간다.
 *
 * <p>자격 판정·404 접기·바닥 시각은 열기 문과 같은 규칙이라 {@code JumpCardOpenTest}와 같은 모양으로 잰다:
 * 가짜 자격 창구는 진짜로 듣는 소켓이고, 답을 안 걸어 두면 503을 받는다.
 * 편집자와 스트리머는 같은 권한이다(2026-10-22 결정): 둘 다 가리고 되살린다.
 *
 * <p>🔴 <b>요청자 번호가 {@link TestIds#STREAMER}와 다르다.</b> 같으면 auth에 안 묻고 문자열만
 * 비교하는 구현에서도 초록이 된다.
 */
@AutoConfigureMockMvc
class JumpCardHideTest extends IntegrationTestSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String RESOLVE = "/internal/editor-delegations/resolve";

    private static final String 요청자 = "4291";

    private static final String 다른_편집자 = "4293";

    private static final String 남남 = "4292";

    private static final String 내_방송 = "s-card-hide";

    private static final long 없는_카드 = 987_654_321L;

    private static final long 바닥_ms = NotFoundFloor.FLOOR.toMillis();

    private final MockMvc mvc;
    private final JumpCardService service;
    private final JdbcTemplate jdbc;

    JumpCardHideTest(MockMvc mvc, JumpCardService service, JdbcTemplate jdbc) {
        this.mvc = mvc;
        this.service = service;
        this.jdbc = jdbc;
    }

    @BeforeEach
    void 앞_테스트의_흔적을_지운다() {
        방송과_카드를_비운다(jdbc);
        방송을_넣는다();
    }

    @Test
    void 가리면_200과_hidden_카드가_오고_목록_기본값에서_빠지고_기록에_hide가_남는다() throws Exception {
        long id = 카드();
        볼_수_있다("EDITOR");

        MvcResult 응답 = 가리기(id, 요청자);

        assertThat(응답.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(응답).get("id").asLong()).isEqualTo(id);
        assertThat(json(응답).get("hidden").asBoolean()).isTrue();
        assertThat(목록_번호("")).isEmpty();
        assertThat(목록_번호("?includeHidden=true")).containsExactly(id);
        assertThat(jdbc.queryForObject("SELECT hidden_by FROM jump_cards WHERE id = ?", String.class, id))
                .isEqualTo(요청자);
        assertThat(jdbc.queryForList("SELECT action, actor FROM jump_card_actions WHERE card_id = ?", id))
                .containsExactly(Map.of("action", "hide", "actor", 요청자));
    }

    /** 되살리기는 누구나 한다: 가린 사람만 되살릴 수 있으면 그 사람이 자리를 비웠을 때 카드가 숨김 칸에 갇힌다. */
    @Test
    void 다른_편집자가_되살리면_200과_보이는_카드가_오고_목록에_돌아온다() throws Exception {
        long id = 카드();
        볼_수_있다("OWNER");
        가리기(id, 요청자);

        MvcResult 응답 = 되살리기(id, 다른_편집자);

        assertThat(응답.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(응답).get("hidden").asBoolean()).isFalse();
        assertThat(목록_번호("")).containsExactly(id);
        assertThat(jdbc.queryForList(
                "SELECT hidden_at, hidden_by FROM jump_cards WHERE id = ?", id).get(0).values()).containsOnlyNulls();
        assertThat(jdbc.queryForList("SELECT action, actor FROM jump_card_actions WHERE card_id = ? ORDER BY id", id))
                .containsExactly(Map.of("action", "hide", "actor", 요청자), Map.of("action", "unhide", "actor", 다른_편집자));
    }

    /**
     * 두 번 눌러도 한 번이다. 안 바뀐 호출을 기록하면 「두 번 숨겼다」로 세지고, 순번이 오르면 화면이 헛돈다.
     * 가린 사람도 처음 가린 사람으로 남는다.
     */
    @Test
    void 이미_가린_카드를_다시_가려도_기록_하나_순번_그대로_가린_사람_그대로다() throws Exception {
        long id = 카드();
        볼_수_있다("OWNER");
        가리기(id, 요청자);
        long 순번 = 순번(id);

        MvcResult 응답 = 가리기(id, 다른_편집자);

        assertThat(응답.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(응답).get("hidden").asBoolean()).isTrue();
        assertThat(순번(id)).isEqualTo(순번);
        assertThat(jdbc.queryForObject("SELECT hidden_by FROM jump_cards WHERE id = ?", String.class, id))
                .isEqualTo(요청자);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM jump_card_actions", Long.class)).isEqualTo(1);
    }

    @Test
    void 안_가린_카드를_되살려도_200이고_기록도_순번도_그대로다() throws Exception {
        long id = 카드();
        볼_수_있다("OWNER");
        long 순번 = 순번(id);

        MvcResult 응답 = 되살리기(id, 요청자);

        assertThat(응답.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(응답).get("hidden").asBoolean()).isFalse();
        assertThat(순번(id)).isEqualTo(순번);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM jump_card_actions", Long.class)).isZero();
    }

    /**
     * 「자격 없는 카드」와 「없는 카드」의 본문이 갈리면 번호를 훑는 것만으로 실재를 안다.
     * <b>양성 대조가 같은 시험 안에 있다</b>: 자격만 바꿔 200이 나오지 않으면 위 404는 판정이 아니라
     * 경로 오타·픽스처 누락이었다.
     */
    @ParameterizedTest
    @ValueSource(strings = {"POST", "DELETE"})
    void 자격이_없으면_404이고_없는_카드와_본문이_같고_카드가_안_바뀐다(String method) throws Exception {
        long id = 카드();
        if (method.equals("DELETE")) {
            jdbc.update("UPDATE jump_cards SET hidden_at = now(), hidden_by = ? WHERE id = ?", 요청자, id);
        }
        long 순번 = 순번(id);
        볼_수_없다();

        MvcResult 남남의_응답 = 문(method, id, 남남);
        assertThat(AUTH.callCount()).as("자격 창구를 안 거쳤다: 이 404는 판정이 낸 것이 아니다").isEqualTo(1);
        MvcResult 없는_카드_응답 = 문(method, 없는_카드, 남남);

        assertThat(남남의_응답.getResponse().getStatus()).isEqualTo(404);
        assertThat(본문(남남의_응답)).isEqualTo(본문(없는_카드_응답)).isEqualTo("{\"error\":\"jump_card_not_found\"}");
        assertThat(순번(id)).as("거절인데 카드가 바뀌었다: 판정이 쓰기보다 뒤에 있다").isEqualTo(순번);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM jump_card_actions", Long.class)).isZero();

        볼_수_있다("OWNER");
        assertThat(문(method, id, 요청자).getResponse().getStatus()).isEqualTo(200);
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "DELETE"})
    void auth를_못_물으면_503이고_카드가_안_바뀐다(String method) throws Exception {
        long id = 카드();
        long 순번 = 순번(id);

        MvcResult 응답 = 문(method, id, 요청자);

        assertThat(응답.getResponse().getStatus()).isEqualTo(503);
        assertThat(본문(응답)).contains("authorization_unavailable");
        assertThat(AUTH.callCount()).as("창구를 안 두드리고 503이면 다른 이유다").isEqualTo(1);
        assertThat(순번(id)).isEqualTo(순번);
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "DELETE"})
    void 토큰이_없으면_401이고_카드가_안_바뀐다(String method) throws Exception {
        long id = 카드();
        long 순번 = 순번(id);

        int status = mvc.perform(request(HttpMethod.valueOf(method), "/api/clip/jump-cards/" + id + "/hide"))
                .andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(401);
        assertThat(순번(id)).as("401인데 카드가 바뀌었으면 체인이 컨트롤러 뒤에 선 것이다").isEqualTo(순번);
    }

    /** 컨트롤러가 {@code NotFoundFloor.mark}를 빠뜨리면 이 문만 조용히 안 늦는다. */
    @ParameterizedTest
    @ValueSource(strings = {"POST", "DELETE"})
    void 없는_카드의_404가_바닥_시간을_채운다(String method) throws Exception {
        long started = System.nanoTime();
        MvcResult 응답 = 문(method, 없는_카드, 요청자);

        assertThat(응답.getResponse().getStatus()).isEqualTo(404);
        assertThat(Duration.ofNanos(System.nanoTime() - started).toMillis()).isGreaterThanOrEqualTo(바닥_ms);
    }

    // ── 도우미 ──────────────────────────────────────────────────

    private void 볼_수_있다(String relation) {
        AUTH.respondWith(RESOLVE, 200, "{\"relation\":\"%s\"}".formatted(relation));
    }

    private void 볼_수_없다() {
        AUTH.respondWith(RESOLVE, 200, "{\"relation\":\"NONE\"}");
    }

    private MvcResult 가리기(long id, String 사람) throws Exception {
        return 문("POST", id, 사람);
    }

    private MvcResult 되살리기(long id, String 사람) throws Exception {
        return 문("DELETE", id, 사람);
    }

    private MvcResult 문(String method, long id, String 사람) throws Exception {
        return mvc.perform(request(HttpMethod.valueOf(method), "/api/clip/jump-cards/" + id + "/hide")
                .header("Authorization", "Bearer " + TestTokens.access(사람))).andReturn();
    }

    private List<Long> 목록_번호(String 질의) throws Exception {
        MvcResult 응답 = mvc.perform(get("/api/clip/broadcasts/" + 내_방송 + "/jump-cards" + 질의)
                .header("Authorization", "Bearer " + TestTokens.access(요청자))).andReturn();
        assertThat(응답.getResponse().getStatus()).as("본문=%s", 본문(응답)).isEqualTo(200);
        return json(응답).get("cards").valueStream().map(c -> c.get("id").asLong()).toList();
    }

    private long 순번(long id) {
        return jdbc.queryForObject("SELECT event_seq FROM jump_cards WHERE id = ?", Long.class, id);
    }

    private JsonNode json(MvcResult 응답) throws Exception {
        return MAPPER.readTree(본문(응답));
    }

    private String 본문(MvcResult 응답) throws Exception {
        return 응답.getResponse().getContentAsString();
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
                OffsetDateTime.ofInstant(Instant.parse("2026-10-25T00:00:00Z"), ZoneOffset.UTC));
    }
}
