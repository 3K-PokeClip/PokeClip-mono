package com.pokeclip.clip.purge;

import com.pokeclip.clip.support.IntegrationTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** auth가 탈퇴를 알리는 문(POK-256). 실제 시큐리티 체인을 태운다. */
@AutoConfigureMockMvc
class StreamerPurgeControllerTest extends IntegrationTestSupport {

    /** application-test.yml의 pokeclip.internal-api.token과 같은 값. */
    private static final String INTERNAL = "test-only-internal-token-32bytes-long!!";

    private final MockMvc mvc;
    private final JdbcTemplate jdbc;

    StreamerPurgeControllerTest(MockMvc mvc, JdbcTemplate jdbc) {
        this.mvc = mvc;
        this.jdbc = jdbc;
    }

    @BeforeEach
    @AfterEach
    void 비운다() {
        방송과_카드를_비운다(jdbc);
    }

    @Test
    void 명부에_적고_202로_답한다_여러_번_불러도_한_줄이다() throws Exception {
        mvc.perform(delete("/internal/streamers/90011/data").header("X-Internal-Token", INTERNAL))
                .andExpect(status().isAccepted());
        mvc.perform(delete("/internal/streamers/90011/data").header("X-Internal-Token", INTERNAL))
                .andExpect(status().isAccepted());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM purged_streamers WHERE streamer_id = '90011'",
                Integer.class)).isOne();
    }

    @Test
    void 숫자가_아닌_번호는_400이고_적지_않는다() throws Exception {
        mvc.perform(delete("/internal/streamers/u-1/data").header("X-Internal-Token", INTERNAL))
                .andExpect(status().isBadRequest());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM purged_streamers", Integer.class)).isZero();
    }

    @Test
    void 토큰이_없으면_401이고_적지_않는다() throws Exception {
        mvc.perform(delete("/internal/streamers/90011/data")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/internal/streamers/90011/data").header("X-Internal-Token", "wrong"))
                .andExpect(status().isUnauthorized());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM purged_streamers", Integer.class)).isZero();
    }
}
