package com.pokeclip.clip.jumpcard.api;

import com.pokeclip.clip.broadcast.Broadcast;
import com.pokeclip.clip.broadcast.BroadcastRepository;
import com.pokeclip.clip.jumpcard.JumpCardRepository;
import com.pokeclip.clip.support.IntegrationTestSupport;
import com.pokeclip.clip.support.TestIds;
import com.pokeclip.clip.support.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 계약 2A의 문. MockMvc지만 <b>실제 시큐리티 체인을 태운다</b> — 내부 토큰이 없으면
 * 컨트롤러에 닿지도 못하는 것까지 여기서 닫힌다.
 */
@AutoConfigureMockMvc
class HighlightIntakeControllerTest extends IntegrationTestSupport {

    /** application-test.yml의 pokeclip.internal-api.token과 같은 값. */
    private static final String INTERNAL = "test-only-internal-token-32bytes-long!!";

    private final MockMvc mvc;
    private final JumpCardRepository cards;
    private final BroadcastRepository broadcasts;
    private final JdbcTemplate jdbc;

    HighlightIntakeControllerTest(MockMvc mvc, JumpCardRepository cards,
                                  BroadcastRepository broadcasts, JdbcTemplate jdbc) {
        this.mvc = mvc;
        this.cards = cards;
        this.broadcasts = broadcasts;
        this.jdbc = jdbc;
    }

    @BeforeEach
    void 정리() {
        jdbc.update("DELETE FROM jump_cards");
        broadcasts.deleteAllInBatch();
        broadcasts.save(Broadcast.startedNow("s-1", TestIds.STREAMER, 1L, Instant.now(), null));
    }

    @Test
    void 계약_형태로_보내면_201과_카드가_온다() throws Exception {
        mvc.perform(post("/internal/broadcasts/s-1/highlights")
                        .header("X-Internal-Token", INTERNAL).contentType(APPLICATION_JSON)
                        .content("""
                                {"eventId":"evt-1","source":"auto","streamTimestampMs":5043000,
                                 "window":{"startMs":5020000,"endMs":5062000},"score":97,
                                 "evidence":{"multiplier":4.2,"messageCount":183}}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.source").value("auto"))
                .andExpect(jsonPath("$.window.startMs").value(5020000))
                .andExpect(jsonPath("$.evidence.multiplier").value(4.2))
                .andExpect(jsonPath("$.claimedBy").value(nullValue()))
                .andExpect(jsonPath("$.hidden").value(false));
    }

    @Test
    void 같은_창을_다시_보내면_200과_기존_카드다() throws Exception {
        MvcResult first = mvc.perform(요청("evt-1", 5_020_000L, 5_062_000L, 5_043_000L))
                .andExpect(status().isCreated()).andReturn();
        long id = idOf(first);

        mvc.perform(요청("evt-재전송", 5_020_000L, 5_062_000L, 5_043_000L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));

        assertThat(cards.findAll()).hasSize(1);
    }

    @Test
    void 없는_방송이면_404다() throws Exception {
        mvc.perform(post("/internal/broadcasts/s-없음/highlights")
                        .header("X-Internal-Token", INTERNAL).contentType(APPLICATION_JSON)
                        .content(본문("evt-1", "auto", 1_500L, 1_000L, 2_000L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("broadcast_not_found"));
    }

    @Test
    void 창이_뒤집히면_400이다() throws Exception {
        mvc.perform(요청("evt-1", 6_000L, 5_000L, 5_500L)).andExpect(status().isBadRequest());
        assertThat(cards.findAll()).isEmpty();
    }

    @Test
    void 지점이_창_밖이면_400이다() throws Exception {
        mvc.perform(요청("evt-1", 1_000L, 2_000L, 9_000L)).andExpect(status().isBadRequest());
        assertThat(cards.findAll()).isEmpty();
    }

    @Test
    void 출처가_빠지면_400이다() throws Exception {
        mvc.perform(post("/internal/broadcasts/s-1/highlights")
                        .header("X-Internal-Token", INTERNAL).contentType(APPLICATION_JSON)
                        .content("""
                                {"eventId":"evt-1","streamTimestampMs":1500,"window":{"startMs":1000,"endMs":2000}}
                                """))
                .andExpect(status().isBadRequest());
    }

    /**
     * <b>{@code event_id}는 {@code VARCHAR(128)}이다.</b> 길이 검증이 없으면 그 값이 DB까지 가서
     * {@code value too long for type character varying(128)}(SQLState 22001)로 터지고
     * <b>500</b>이 나간다 — 계약은 400 {@code invalid_request}다(PR #109 봇 지적 ⑤, 재현 확인).
     *
     * <p>500이면 <b>판별기가 재시도한다.</b> 같은 payload라 영영 성공하지 못하고(실측: 재시도도
     * 500), 그 카드는 버려지는데 원인은 「clip 서버 오류」로 보인다. 요청이 잘못된 것이라면
     * 판별기가 그것을 알아야 재시도를 멈춘다.
     *
     * <p>길이 검증 없이 DB까지 가는 칸은 <b>{@code eventId} 하나뿐이다</b> — {@code streamId}는
     * 명부에 없어 404로 먼저 걸리고, {@code source}는 컨트롤러가 400으로 좁혀 던진다(재현 확인).
     */
    @Test
    void eventId가_128자를_넘으면_400이고_128자는_통과한다() throws Exception {
        mvc.perform(요청("e".repeat(129), 1_000L, 2_000L, 1_500L))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"))
                .andExpect(jsonPath("$.field").value("eventId"));
        assertThat(cards.findAll()).as("400인데 행이 남으면 검증이 DB 뒤에 있는 것이다").isEmpty();

        mvc.perform(요청("e".repeat(128), 3_000L, 4_000L, 3_500L))
                .andExpect(status().isCreated());
    }

    @Test
    void 모르는_출처면_400이다() throws Exception {
        mvc.perform(post("/internal/broadcasts/s-1/highlights")
                        .header("X-Internal-Token", INTERNAL).contentType(APPLICATION_JSON)
                        .content(본문("evt-1", "magic", 1_500L, 1_000L, 2_000L)))
                .andExpect(status().isBadRequest());
        assertThat(cards.findAll()).isEmpty();
    }

    @Test
    void 내부_토큰이_없으면_401이고_카드는_안_생긴다() throws Exception {
        mvc.perform(post("/internal/broadcasts/s-1/highlights").contentType(APPLICATION_JSON)
                        .content(본문("evt-1", "auto", 1_500L, 1_000L, 2_000L)))
                .andExpect(status().isUnauthorized());
        assertThat(cards.findAll()).isEmpty();
    }

    /** 사람 토큰으로는 이 문에 못 들어간다 — 인증 수단이 섞이면 "어느 쪽으로든 통과"가 된다. */
    @Test
    void 사람_토큰으로는_401이다() throws Exception {
        mvc.perform(post("/internal/broadcasts/s-1/highlights")
                        .header("Authorization", "Bearer " + TestTokens.access("7"))
                        .contentType(APPLICATION_JSON)
                        .content(본문("evt-1", "auto", 1_500L, 1_000L, 2_000L)))
                .andExpect(status().isUnauthorized());
        assertThat(cards.findAll()).isEmpty();
    }

    private org.springframework.test.web.servlet.RequestBuilder 요청(String eventId, long start, long end, long ts) {
        return post("/internal/broadcasts/s-1/highlights")
                .header("X-Internal-Token", INTERNAL).contentType(APPLICATION_JSON)
                .content(본문(eventId, "auto", ts, start, end));
    }

    private String 본문(String eventId, String source, long ts, long start, long end) {
        return """
                {"eventId":"%s","source":"%s","streamTimestampMs":%d,"window":{"startMs":%d,"endMs":%d},"score":97}
                """.formatted(eventId, source, ts, start, end);
    }

    private long idOf(MvcResult result) throws Exception {
        return com.jayway.jsonpath.JsonPath.parse(result.getResponse().getContentAsString()).read("$.id", Long.class);
    }
}
