package com.pokeclip.auth.db;

import com.pokeclip.auth.AuthApplication;
import com.pokeclip.auth.support.IntegrationTestSupport;
import com.pokeclip.db.support.StallingTcpProxy;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * {@code application.yml}의 JDBC {@code socketTimeout}이 이 서버에서 <b>실제로 걸리는지</b>를 행동으로 잰다(POK-414,
 * 수집 서버 {@code DatasourceTimeoutTest}와 같은 꼴). 값을 읽지 않고, 연결은 받고 응답을 안 주는 DB를 앞에 두고 그 시한 안에
 * 예외로 끝나는지를 본다. 없으면 그 요청 스레드가 끝없이 매달려 풀이 바닥난다.
 *
 * <p>별도 컨텍스트로 부팅하는 이유: yml의 키가 드라이버까지 닿는 <i>배선</i>(db-support 허용 목록 포함)이 검사 대상이다.
 * Hikari에 정수로 넣으면 드라이버가 조용히 무시한다. 주소만 중계기로 바꾸고 나머지는 이 서버 yml 그대로다.
 *
 * <p>표식은 이 시험만 보내는 글자라 풀의 생존 검사나 다른 질의가 먼저 막히지 않는다.
 */
class DatasourceTimeoutTest extends IntegrationTestSupport {

    /** 이 값이 실제로 걸리는지가 검사 대상이다. 운영값은 application.yml에 있다. */
    private static final Duration SOCKET_TIMEOUT = Duration.ofSeconds(10);

    /** 시한이 어디에도 안 걸리면 빨간불 대신 멈춘 채로 끝나는 것을 막는 안전망. 판정은 아래 단언이 한다. */
    private static final Duration UPPER_BOUND = Duration.ofSeconds(40);

    private static final String MARKER = "pok414_stall_marker";

    @Test
    void DB가_연결은_받고_응답을_안_하면_socketTimeout에_끊기고_다음_질의는_새_연결로_된다() throws Exception {
        try (StallingTcpProxy proxy = new StallingTcpProxy(POSTGRES.getHost(), POSTGRES.getMappedPort(5432));
             ConfigurableApplicationContext context = bootThrough(proxy)) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            proxy.stallResponsesAfter(MARKER);

            Throwable thrown = assertTimeoutPreemptively(UPPER_BOUND,
                    () -> catchThrowable(() -> jdbc.queryForObject("SELECT '" + MARKER + "'", String.class)));
            Instant stalledAt = proxy.stalledAt();

            // 양성 대조. 응답이 막힌 적이 없으면 아래 시간은 아무것도 안 잰 것이다
            assertThat(stalledAt).as("응답이 막힌 적이 없으면 시한을 잰 것이 아니다").isNotNull();
            assertThat(Duration.between(stalledAt, Instant.now()))
                    .as("socketTimeout이 안 걸리면 반개방에서 영영 매달린다")
                    .isLessThan(SOCKET_TIMEOUT.multipliedBy(2));
            assertThat(sqlStateOf(thrown)).as("연결 장애(08류)로 끝나야 한다").startsWith("08");

            // 회복: 끊긴 연결은 풀에서 빠지고 다음 질의는 새 연결로 된다
            proxy.resume();
            assertThat(jdbc.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
        }
    }

    private static String sqlStateOf(Throwable thrown) {
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return "";
    }

    private ConfigurableApplicationContext bootThrough(StallingTcpProxy proxy) {
        return new SpringApplicationBuilder(AuthApplication.class)
                .web(WebApplicationType.NONE)
                .profiles("test")
                .run("--spring.datasource.url=jdbc:postgresql://127.0.0.1:" + proxy.port()
                                + "/" + POSTGRES.getDatabaseName(),
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword());
    }
}
