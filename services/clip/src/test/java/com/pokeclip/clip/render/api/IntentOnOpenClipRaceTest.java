package com.pokeclip.clip.render.api;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 「영상 만들기」 갈래 (a)(만드는 중이면 의도만 덮어쓴다)와 렌더 성공 보고가 겹칠 때(POK-291 로컬 리뷰 1라운드).
 *
 * <p>성공 보고는 영상 줄을 잠근 뒤 의도를 {@code FOR UPDATE}로 읽는다. 갈래 (a)가 영상 줄을 <b>안 잠그고</b> 「만드는 중」으로 보고
 * 의도를 넣으면, 성공 쪽은 아직 커밋 안 된 그 줄을 못 보고(기다리지도 않는다) 「의도 없음」으로 끝난다. 결과는 완성 + 의도 + 업로드 없음이고
 * 응답은 「다 만들어지면 올린다」다. 그 겹침을 두 트랜잭션으로 그대로 만든다: 시험이 성공 보고 노릇을 하는 트랜잭션을 열어 영상 줄을
 * 잡고 의도를 읽은 채로 요청을 보내고, 요청이 그 잠금에 줄을 선 것을 본 뒤에 완성으로 커밋한다.
 */
class IntentOnOpenClipRaceTest extends RenderUploadTestSupport {

    private final DataSource dataSource;

    IntentOnOpenClipRaceTest(MockMvc mvc, JdbcTemplate jdbc, DataSource dataSource) {
        super(mvc, jdbc);
        this.dataSource = dataSource;
    }

    @Test
    void 만드는_중으로_보던_사이_성공이_끝나면_새_의도로_바로_올린다() throws Exception {
        long clipId = json(만들기(편집본, null).andExpect(status().isCreated())).get("id").asLong();
        String outputs = 영상_하나().replace("{prefix}", "clips/" + clipId + "/t1");

        ExecutorService 요청_스레드 = Executors.newSingleThreadExecutor();
        try (Connection 성공_보고 = dataSource.getConnection()) {
            성공_보고.setAutoCommit(false);
            try (Statement st = 성공_보고.createStatement()) {
                // 성공 보고가 하는 순서 그대로: 영상 줄을 잡고(JobEventService) 의도를 FOR UPDATE로 읽는다(UploadAutoStarter). 아직 없다.
                st.execute("SELECT id FROM clips WHERE id = " + clipId + " FOR NO KEY UPDATE");
                st.execute("SELECT id FROM upload_requests FOR UPDATE");
            }

            Future<MvcResult> 요청 = 요청_스레드.submit(() -> 만들기(편집본, 기본_업로드()).andReturn());
            // 고친 뒤: 요청이 영상 줄 잠금에 줄을 선다. 고치기 전: 요청이 안 기다리고 의도만 넣고 끝난다.
            long 마감 = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!요청.isDone() && 잠금을_기다리는_수() == 0 && System.nanoTime() < 마감) {
                Thread.sleep(20);
            }

            try (Statement st = 성공_보고.createStatement()) {
                st.executeUpdate("UPDATE clips SET status = 'rendered', outputs = '" + outputs + "'::jsonb WHERE id = " + clipId);
            }
            성공_보고.commit();

            MvcResult 결과 = 요청.get(30, TimeUnit.SECONDS);
            JsonNode 응답 = MAPPER.readTree(결과.getResponse().getContentAsString());
            assertThat(결과.getResponse().getStatus()).as(응답.toString()).isEqualTo(200);
            assertThat(응답.at("/upload/status").asString()).as("응답이 업로드 줄을 안 실었다").isEqualTo("queued");
        } finally {
            요청_스레드.shutdownNow();
        }

        assertThat(업로드_줄_수()).as("완성 + 의도인데 업로드 줄이 없다: 성공 쪽은 의도를 못 봤고 요청은 「만드는 중」으로 봤다").isOne();
        assertThat(jdbc.queryForObject("SELECT clip_id || ':' || title || ':' || privacy_status FROM clip_uploads", String.class))
                .isEqualTo(clipId + ":펜타킬:unlisted");
    }

    private int 잠금을_기다리는_수() {
        return jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' "
                + "AND datname = current_database()", Integer.class);
    }
}
