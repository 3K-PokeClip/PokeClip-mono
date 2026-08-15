package com.pokeclip.chat.collector;

import com.pokeclip.chat.collector.support.IntegrationTestSupport;
import com.pokeclip.chat.collector.support.StallingTcpProxy;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * {@code application.yml}의 JDBC {@code socketTimeout}이 <b>실제로 걸리는지</b>를 행동으로
 * 잰다 — {@link HttpTimeoutTest}와 같은 이유, 같은 방식이다. 설정 값을 읽지 않고,
 * <b>연결은 받고 응답을 안 주는 DB</b>를 앞에 두고 그 시한 안에 예외로 끝나는지를 본다.
 *
 * <p>표적은 <b>반개방 스톨</b>이다(PR #55 P1). 죽은 포트는 즉시 거부되고 닫힌 소켓은
 * EOF가 오지만, 연결이 살아 있는 채 응답만 안 오면 동기 {@code batchUpdate}는 시한이
 * 없는 한 영영 매달린다 — 저장 스레드가 하나라 그 뒤의 flush가 전부 막히고, 버퍼는
 * 상한에 닿아 드롭만 세며, DB가 회복돼도 못 빠져나온다. 진짜 PG 앞에 응답을 삼키는
 * 중계기를 세워 그 상태를 결정적으로 만든다.
 *
 * <p><b>왜 socketTimeout인가 (2026-08-15 실측, pgjdbc 42.7.11 · HikariCP 7.0.2):</b>
 * 셋 중 스톨을 끊는 것은 이것뿐이다. JdbcTemplate {@code queryTimeout}은 취소를 <i>서버에
 * 보내는</i> 방식이라 응답이 안 오는 상대에겐 무력하고(20초 넘게 매달림), HikariCP
 * {@code connection-timeout}은 풀에서 커넥션을 <i>빌리는</i> 대기 시한이라 이미 빌린
 * 커넥션의 read는 못 끊는다(역시 매달림). 핸드셰이크 스톨(접속은 받고 인증 응답이 없음)도
 * socketTimeout만 끊는다 — Hikari 시한은 pgjdbc의 loginTimeout에 닿지 않는다.
 *
 * <p><b>왜 별도 컨텍스트로 부팅하나:</b> yml의 키가 드라이버까지 닿는 <i>배선</i>이 검사
 * 대상이기 때문이다. Hikari에 값을 문자열이 아닌 타입으로 넣으면 드라이버가 조용히
 * 무시한다(실측 — Integer면 networkTimeout=0). 손으로 만든 DataSource로 재면 그 배선을
 * 지나지 않아 설정이 통째로 죽어 있어도 초록이다.
 */
class DatasourceTimeoutTest extends IntegrationTestSupport {

    /** 이 값이 실제로 걸리는지가 검사 대상이다. 운영값은 application.yml에 있다. */
    private static final Duration SOCKET_TIMEOUT = Duration.ofSeconds(10);

    /**
     * 판정은 아래 heldFor 단언이 한다. 이 상한은 그 단언에 도달조차 못 하는 경우만
     * 막는 안전망이다 — 시한이 어디에도 안 걸리면 이 검사는 빨간불 대신 <b>멈춘 채로</b>
     * 끝난다(HttpTimeoutTest와 같은 이유).
     */
    private static final Duration UPPER_BOUND = Duration.ofSeconds(40);

    private static final String INSERT = """
            INSERT INTO chat_messages
              (channel_id, sender_channel_id, content, message_time, received_at, content_sha256)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT ON CONSTRAINT uq_chat_messages_fingerprint DO NOTHING
            """;

    private static final String CHANNEL = "datasource-timeout";

    @Test
    void DB가_연결은_받고_응답을_안_하면_socketTimeout에_끊기고_연결_장애로_분류된다() throws Exception {
        try (StallingTcpProxy proxy = new StallingTcpProxy("localhost", POSTGRES.getMappedPort(5432));
             ConfigurableApplicationContext context = bootThrough(proxy)) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            jdbc.update("DELETE FROM chat_messages WHERE channel_id = ?", CHANNEL);
            // 부팅(Flyway)이 끝난 뒤에 건다 — 마이그레이션 SQL에도 표 이름이 있다.
            proxy.stallResponsesAfter("chat_messages");

            Throwable thrown = assertTimeoutPreemptively(UPPER_BOUND,
                    () -> catchThrowable(() -> jdbc.batchUpdate(INSERT, rows())));
            Instant stalledAt = proxy.stalledAt();

            // 양성 대조. 스톨이 안 걸렸다면 위 시간은 아무것도 안 잰 것이다.
            assertThat(stalledAt)
                    .as("응답이 막힌 적이 없으면 시한을 잰 것이 아니다")
                    .isNotNull();
            // 여기가 이 검사의 본체다 — 얼마나 기다렸는지. 상태보다 먼저 단언한다.
            assertThat(Duration.between(stalledAt, Instant.now()))
                    .as("socketTimeout이 어디에도 안 걸리면 batchUpdate가 반개방에서 영영 매달린다")
                    .isLessThan(SOCKET_TIMEOUT.multipliedBy(2));
            // 퍼시스터의 분류가 걸리는 모양이다 — 연결 장애(08류)는 보존·재시도, 22류만 격리.
            assertThat(thrown)
                    .as("시한 초과가 연결 장애로 번역돼야 기존 복원·재시도 경로를 탄다")
                    .isInstanceOf(DataAccessResourceFailureException.class);
            assertThat(sqlStateOf(thrown))
                    .as("22류(데이터 오류)로 오면 멀쩡한 채팅이 격리로 버려진다")
                    .startsWith("08");

            // 회복 — 끊긴 커넥션은 풀에서 빠지고 다음 배치는 새 커넥션으로 저장된다.
            proxy.resume();
            jdbc.batchUpdate(INSERT, rows());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_messages WHERE channel_id = ?",
                    Long.class, CHANNEL)).isEqualTo(1);
        }
    }

    private static List<Object[]> rows() {
        return List.<Object[]>of(new Object[] {
                CHANNEL, "s-1", "안녕", new Timestamp(1_723_600_000_000L),
                new Timestamp(1_723_600_000_175L), "0".repeat(64)
        });
    }

    private static String sqlStateOf(Throwable thrown) {
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return "";
    }

    /**
     * URL만 중계기로 바꾼다. 나머지는 application.yml 그대로라 {@code hikari.*}의
     * socketTimeout이 운영과 같은 길로 드라이버에 닿는다 — 그 길이 검사 대상이다.
     */
    private ConfigurableApplicationContext bootThrough(StallingTcpProxy proxy) {
        return new SpringApplicationBuilder(CollectorApplication.class)
                .web(WebApplicationType.NONE)
                .profiles("test")
                .run("--spring.datasource.url=jdbc:postgresql://localhost:" + proxy.port()
                                + "/" + POSTGRES.getDatabaseName(),
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword());
    }
}
