package com.pokeclip.clip.jumpcard.api;

import com.pokeclip.clip.broadcast.Broadcast;
import com.pokeclip.clip.broadcast.BroadcastRepository;
import com.pokeclip.clip.jumpcard.JumpCardService;
import com.pokeclip.clip.support.IntegrationTestSupport;
import com.pokeclip.clip.support.TestIds;
import com.pokeclip.clip.support.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 편집자가 카드에 쓰는 문. 집기·놓기는 없어졌고(POK-393) 열기와 숨기기·되살리기(POK-463)가 남았다.
 * 열기는 {@code JumpCardOpenTest}, 숨기기는 {@code JumpCardHideTest}가 잰다. 여기는 <b>옛 문이 정말 닫혔나</b>와
 * 경로·토큰 수준의 응답을 본다.
 */
@AutoConfigureMockMvc
class JumpCardControllerTest extends IntegrationTestSupport {

    private static final String RESOLVE = "/internal/editor-delegations/resolve";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final MockMvc mvc;
    private final JumpCardService service;
    private final BroadcastRepository broadcasts;
    private final JdbcTemplate jdbc;

    JumpCardControllerTest(MockMvc mvc, JumpCardService service, BroadcastRepository broadcasts, JdbcTemplate jdbc) {
        this.mvc = mvc;
        this.service = service;
        this.broadcasts = broadcasts;
        this.jdbc = jdbc;
    }

    @BeforeEach
    void 정리() {
        jdbc.update("DELETE FROM jump_cards");
        broadcasts.deleteAllInBatch();
        broadcasts.save(Broadcast.startedNow("s-1", TestIds.STREAMER, null, 1L, Instant.now(), null));
        AUTH.respondWith(RESOLVE, 200, "{\"relation\":\"OWNER\"}");
    }

    /**
     * 자격이 있는 사람이 실재하는 카드에 옛 집기 문을 두드려도 404이고 아무것도 안 남는다.
     * 옛 웹이 배포 사이에 부를 수 있다. 그때 카드가 바뀌거나 기록이 남으면 안 된다.
     */
    @ParameterizedTest
    @CsvSource({"POST,/claim", "DELETE,/claim"})
    void 옛_집기_문_둘은_없다(String method, String path) throws Exception {
        long id = 카드();

        int status = mvc.perform(request(HttpMethod.valueOf(method), "/api/clip/jump-cards/" + id + path)
                .header("Authorization", bearer("17"))).andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM jump_card_actions", Long.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM jump_cards WHERE claimed_by IS NOT NULL OR hidden_at IS NOT NULL", Long.class))
                .isZero();
    }

    @Test
    void 없는_카드는_404다() throws Exception {
        mvc.perform(post("/api/clip/jump-cards/999999/open").header("Authorization", bearer("17")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("jump_card_not_found"));
    }

    /**
     * 🔴 <b>이 400 봉투는 POK-174가 카드 문에 붙인 것이다.</b> 경로 조각이 숫자가 아니면 컨트롤러
     * 메서드에 들어오기 전에 끝나는데, 그 예외를 다루는 조언을 전역으로 옮기면서 카드 문까지
     * 범위에 딸려 들어왔다. 유출은 없다: {@code id}는 우리 시그니처의 조각 이름이지 웹이 보낸 값이 아니다.
     */
    @Test
    void 카드_번호가_숫자가_아니면_400_봉투를_낸다() throws Exception {
        mvc.perform(post("/api/clip/jump-cards/abc/open").header("Authorization", bearer("17")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"))
                .andExpect(jsonPath("$.field").value("id"));

        assertThat(AUTH.callCount())
                .as("칸이 안 읽히는데 자격 창구를 두드리면 형식 오류가 auth 왕복을 태운다").isZero();
    }

    @Test
    void 토큰_없이는_401이다() throws Exception {
        long id = 카드();

        mvc.perform(post("/api/clip/jump-cards/" + id + "/open")).andExpect(status().isUnauthorized());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM jump_card_actions", Long.class))
                .as("401인데 기록이 남았으면 체인이 컨트롤러 뒤에 선 것이다").isZero();
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
