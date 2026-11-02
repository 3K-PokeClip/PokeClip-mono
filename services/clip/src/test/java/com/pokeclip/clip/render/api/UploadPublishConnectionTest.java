package com.pokeclip.clip.render.api;

import com.pokeclip.clip.support.LocalStackFixture;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 🔴 업로드 줄을 만든 요청이 <b>커밋 뒤 발행 때문에 커넥션을 둘 쥐지 않는다</b>(POK-291 로컬 리뷰 1라운드 중대).
 *
 * <p>커밋 뒤 훅은 원 커넥션을 돌려주기 <b>전</b>에 돈다. 그 안에서 발행 트랜잭션을 새로 열면 요청 하나가 커넥션 둘을 쥔다. 풀 크기만큼
 * 겹치면 모두가 두 번째를 기다리며 풀 시한(30초) 동안 clip이 멈춘다. 그 모양을 시험 하나로 만든다: 풀에 <b>하나만</b> 남기고 나머지를
 * 시험이 쥔 채 업로드 줄을 만드는 요청을 보낸다. 훅이 직접 발행하면 요청이 두 번째 커넥션을 30초 기다리고, 전용 스레드에 맡기면 요청은
 * 바로 끝나고 발행은 요청이 돌려준 그 하나로 돈다.
 */
class UploadPublishConnectionTest extends RenderUploadTestSupport {

    private final DataSource dataSource;

    UploadPublishConnectionTest(MockMvc mvc, JdbcTemplate jdbc, DataSource dataSource) {
        super(mvc, jdbc);
        this.dataSource = dataSource;
    }

    @Test
    void 풀에_커넥션이_하나만_남아도_업로드_줄을_만드는_요청이_멈추지_않고_주문서가_실린다() throws Exception {
        long clipId = json(만들기(편집본, null).andExpect(status().isCreated())).get("id").asLong();
        완성시킨다(clipId, 영상_하나());
        HikariDataSource pool = dataSource.unwrap(HikariDataSource.class);

        List<Connection> held = new ArrayList<>();
        Duration 걸린_시간;
        try {
            for (int i = 0; i < pool.getMaximumPoolSize() - 1; i++) {
                held.add(pool.getConnection());
            }
            long 시작 = System.nanoTime();
            // 갈래 (b): 이미 완성된 판이라 이 요청이 업로드 줄을 만들고 커밋 뒤 발행을 건다. 도우미가 발행이 끝날 때까지 기다리는데,
            // 그동안 시험은 커넥션을 계속 쥐고 있다: 발행은 요청이 돌려준 마지막 하나로 돌아야 한다.
            만들기(편집본, 기본_업로드()).andExpect(status().isOk()).andExpect(jsonPath("$.upload.status").value("queued"));
            걸린_시간 = Duration.ofNanos(System.nanoTime() - 시작);
        } finally {
            for (Connection connection : held) {
                connection.close();
            }
        }

        assertThat(걸린_시간).as("커밋 뒤 훅이 요청의 커넥션을 쥔 채 두 번째 커넥션을 기다렸다(풀 시한 30초)")
                .isLessThan(Duration.ofSeconds(10));
        assertThat(jdbc.queryForObject("SELECT published_at IS NOT NULL FROM clip_uploads", Boolean.class))
                .as("발행이 빠졌다(outbox는 30초 뒤에야 싣는다)").isTrue();
        assertThat(LocalStackFixture.receiveAndDelete(업로드줄.queueUrl())).isNotNull();
    }
}
