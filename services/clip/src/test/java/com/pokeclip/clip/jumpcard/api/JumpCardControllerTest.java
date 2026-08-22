package com.pokeclip.clip.jumpcard.api;

import com.pokeclip.clip.broadcast.Broadcast;
import com.pokeclip.clip.broadcast.BroadcastRepository;
import com.pokeclip.clip.jumpcard.JumpCardRepository;
import com.pokeclip.clip.jumpcard.JumpCardService;
import com.pokeclip.clip.support.IntegrationTestSupport;
import com.pokeclip.clip.support.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

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
        broadcasts.save(Broadcast.startedNow("s-1", "u-1", 1L, Instant.now(), null));
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
