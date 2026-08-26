package com.pokeclip.clip.jumpcard.api;

import com.pokeclip.clip.broadcast.Broadcast;
import com.pokeclip.clip.broadcast.BroadcastRepository;
import com.pokeclip.clip.jumpcard.JumpCardRepository;
import com.pokeclip.clip.jumpcard.JumpCardService;
import com.pokeclip.clip.support.IntegrationTestSupport;
import com.pokeclip.clip.support.TestIds;
import com.pokeclip.clip.support.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 편집자가 쓰는 문 넷. 사용자 번호는 <b>토큰의 subject에서만</b> 온다 — 본문이나 쿼리로 받으면
 * 남의 번호로 집을 수 있다.
 */
@AutoConfigureMockMvc
class JumpCardControllerTest extends IntegrationTestSupport {

    private static final String RESOLVE = "/internal/editor-delegations/resolve";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final MockMvc mvc;
    private final JumpCardService service;
    private final JumpCardRepository cards;
    private final BroadcastRepository broadcasts;
    private final JdbcTemplate jdbc;

    JumpCardControllerTest(MockMvc mvc, JumpCardService service, JumpCardRepository cards,
                           BroadcastRepository broadcasts, JdbcTemplate jdbc) {
        this.mvc = mvc;
        this.service = service;
        this.cards = cards;
        this.broadcasts = broadcasts;
        this.jdbc = jdbc;
    }

    @BeforeEach
    void 정리() {
        jdbc.update("DELETE FROM jump_cards");
        broadcasts.deleteAllInBatch();
        broadcasts.save(Broadcast.startedNow("s-1", TestIds.STREAMER, 1L, Instant.now(), null));
        AUTH.respondWith(RESOLVE, 200, "{\"relation\":\"OWNER\"}");
    }

    @Test
    void 집으면_200과_카드가_오고_claimedBy는_내_번호다() throws Exception {
        long id = 카드();

        mvc.perform(post("/api/clip/jump-cards/" + id + "/claim").header("Authorization", bearer("17")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.claimedBy").value("17"))
                .andExpect(jsonPath("$.claimExpiresAt").exists());
    }

    /** 409에 현재 카드를 실어야 웹이 새로고침 없이 "누가 잡고 있는지"를 띄운다. */
    @Test
    void 남이_잡은_카드를_집으면_409와_현재_카드가_온다() throws Exception {
        long id = 카드();
        mvc.perform(post("/api/clip/jump-cards/" + id + "/claim").header("Authorization", bearer("17")))
                .andExpect(status().isOk());

        mvc.perform(post("/api/clip/jump-cards/" + id + "/claim").header("Authorization", bearer("18")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.claimedBy").value("17"))
                .andExpect(jsonPath("$.id").value(id));
    }

    @Test
    void 놓기_본인_204_남_403() throws Exception {
        long id = 카드();
        mvc.perform(post("/api/clip/jump-cards/" + id + "/claim").header("Authorization", bearer("17")))
                .andExpect(status().isOk());

        mvc.perform(delete("/api/clip/jump-cards/" + id + "/claim").header("Authorization", bearer("18")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("not_claim_owner"));

        mvc.perform(delete("/api/clip/jump-cards/" + id + "/claim").header("Authorization", bearer("17")))
                .andExpect(status().isNoContent());

        assertThat(cards.findById(id).orElseThrow().getClaimedBy()).isNull();
    }

    @Test
    void 숨기기_200_hidden_true_hiddenBy_내_번호_되돌리기_200_false() throws Exception {
        long id = 카드();

        mvc.perform(post("/api/clip/jump-cards/" + id + "/hide").header("Authorization", bearer("17")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hidden").value(true))
                .andExpect(jsonPath("$.hiddenBy").value("17"));

        mvc.perform(delete("/api/clip/jump-cards/" + id + "/hide").header("Authorization", bearer("18")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hidden").value(false))
                .andExpect(jsonPath("$.hiddenBy").value(nullValue()));
    }

    @Test
    void 없는_카드는_404다() throws Exception {
        mvc.perform(post("/api/clip/jump-cards/999999/claim").header("Authorization", bearer("17")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("jump_card_not_found"));
    }

    /**
     * 🔴 <b>이 400 봉투는 POK-174가 문 넷에 새로 붙인 것이다.</b> 경로 조각이 숫자가 아니면
     * 컨트롤러 메서드에 들어오기 전에 끝나는데, {@code develop}에서는 그 예외를 다루는 조언이
     * {@code SegmentController}로 <b>좁혀져 있어</b> 스프링 기본 {@code /error} 봉투로 나갔다.
     * 목록 문 둘 때문에 전역으로 옮기면서 <b>카드 문 넷까지 범위에 딸려 들어왔다</b> —
     * 방향은 개선(봉투가 한 벌로 통일된다)이지만 <b>웹에 약속하는 것이 하나 늘었다</b>.
     *
     * <p>문 넷을 다 재는 이유는 셋만 재면 나머지 하나가 조용히 갈려도 안 보이기 때문이다.
     * 유출은 없다 — {@code id}는 우리 시그니처의 조각 이름이지 웹이 보낸 값이 아니다.
     */
    @Test
    void 카드_번호가_숫자가_아니면_문_넷이_같은_400_봉투를_낸다() throws Exception {
        for (MockHttpServletRequestBuilder 요청 : List.of(
                post("/api/clip/jump-cards/abc/claim"),
                delete("/api/clip/jump-cards/abc/claim"),
                post("/api/clip/jump-cards/abc/hide"),
                delete("/api/clip/jump-cards/abc/hide"))) {
            mvc.perform(요청.header("Authorization", bearer("17")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("invalid_request"))
                    .andExpect(jsonPath("$.field").value("id"));
        }

        assertThat(AUTH.callCount())
                .as("칸이 안 읽히는데 자격 창구를 두드리면 형식 오류가 auth 왕복을 태운다").isZero();
    }

    @Test
    void 토큰_없이는_401이다() throws Exception {
        long id = 카드();

        mvc.perform(post("/api/clip/jump-cards/" + id + "/claim")).andExpect(status().isUnauthorized());

        assertThat(cards.findById(id).orElseThrow().getClaimedBy())
                .as("401인데 집혔으면 체인이 컨트롤러 뒤에 선 것이다").isNull();
    }

    private long 카드() {
        return service.record("s-1", new HighlightRequest("evt-1", "auto", 5_043_000L,
                new HighlightRequest.Window(5_020_000L, 5_062_000L), 97,
                MAPPER.readTree("{\"multiplier\":4.2}"))).card().id();
    }

    private String bearer(String userId) {
        return "Bearer " + TestTokens.access(userId);
    }
}
