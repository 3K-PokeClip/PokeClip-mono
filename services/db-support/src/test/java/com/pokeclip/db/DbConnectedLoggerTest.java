package com.pokeclip.db;

import ch.qos.logback.classic.Level;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.net.ServerSocket;
import java.util.List;
import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 기동 직후 {@code db_connected} 한 줄을 잰다(POK-407). 사이드카(media {@code pgconf.ConfirmConnection})와 같은 이름 ·
 * 같은 칸이고, 배포 확인이 이 줄로 판정한다. {@code tls}는 설정값이 아니라 실제 연결이 TLS인가다.
 */
class DbConnectedLoggerTest extends PostgresTestBase {

    /** TLS를 켠 서버. 이미지에 든 자체 서명 인증서를 쓴다. 비밀번호 방식에서 sslmode=require로만 붙는다. */
    private static final PostgreSQLContainer TLS_POSTGRES = new PostgreSQLContainer("postgres:17")
            .withCommand("postgres", "-c", "ssl=on",
                    "-c", "ssl_cert_file=/etc/ssl/certs/ssl-cert-snakeoil.pem",
                    "-c", "ssl_key_file=/etc/ssl/private/ssl-cert-snakeoil.key");

    static {
        TLS_POSTGRES.start();
    }

    /**
     * 기동 중에 붙인다. Boot가 기동 초반에 로그 설정을 새로 깔며 미리 붙여 둔 appender를 떼기 때문이다.
     * 초기화기는 그 뒤, 줄이 찍히는 기동 완료보다 앞에 돈다.
     */
    private LogCaptor logs;

    @AfterEach
    void detach() {
        if (logs != null) {
            logs.close();
        }
    }

    @Test
    void 평문_연결이면_tls_false와_접속_값을_한_줄로_남긴다() {
        try (ConfigurableApplicationContext ignored = boot(POSTGRES)) {
            List<String> lines = connectedLines();

            assertThat(lines).containsExactly("db_connected auth=password sslmode=prefer tls=false user="
                    + POSTGRES.getUsername() + " host=" + POSTGRES.getHost() + " port=" + POSTGRES.getMappedPort(5432)
                    + " db=" + POSTGRES.getDatabaseName());
            assertThat(logs.levelOf("db_connected")).isEqualTo(Level.INFO);
        }
    }

    @Test
    void TLS로_붙었으면_tls_true다() {
        try (ConfigurableApplicationContext ignored = boot(TLS_POSTGRES,
                "--spring.datasource.hikari.data-source-properties.sslmode=require")) {

            assertThat(connectedLines()).singleElement().asString()
                    .contains("sslmode=require", "tls=true");
        }
    }

    /** 줄은 판정용이다. 못 붙어도 기동은 지금처럼 이어진다(비밀번호 방식에서 동작을 바꾸지 않는다). */
    @Test
    void 못_붙으면_줄_없이_원인_종류만_경고하고_기동은_이어진다() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(DbSupportTestApplication.class)
                .web(WebApplicationType.NONE)
                .initializers(ctx -> logs = new LogCaptor())
                .run("--spring.datasource.url=jdbc:postgresql://localhost:" + closedPort + "/nope",
                             "--spring.datasource.username=nobody",
                             "--spring.datasource.password=not-logged-secret",
                             "--spring.datasource.hikari.connection-timeout=500")) {

            assertThat(context.isActive()).isTrue();
            assertThat(connectedLines()).isEmpty();
            assertThat(logs.levelOf("db_connect_failed")).isEqualTo(Level.WARN);
            assertThat(logs.messages()).noneMatch(line -> line.contains("not-logged-secret"));
        }
    }

    /** 확인 줄 때문에 기동이 죽으면 안 된다. 풀이 둘이면 어느 것을 볼지 모르니 줄만 건너뛴다. */
    @Test
    void DataSource가_둘이면_줄_없이_기동은_이어진다() {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(DbSupportTestApplication.class)
                .web(WebApplicationType.NONE)
                .initializers(ctx -> {
                    logs = new LogCaptor();
                    // 하나만 더하면 Boot가 자기 풀을 안 만들어 여전히 하나다. 둘을 직접 둔다.
                    for (String name : List.of("firstDataSource", "secondDataSource")) {
                        ((GenericApplicationContext) ctx).registerBean(name, DataSource.class,
                                () -> new DriverManagerDataSource(POSTGRES.getJdbcUrl()));
                    }
                })
                .run("--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword())) {

            assertThat(context.isActive()).isTrue();
            assertThat(connectedLines()).isEmpty();
        }
    }

    private ConfigurableApplicationContext boot(PostgreSQLContainer postgres, String... extra) {
        String[] base = {
                "--spring.datasource.url=jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432)
                        + "/" + postgres.getDatabaseName(),
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword()};
        String[] args = new String[base.length + extra.length];
        System.arraycopy(base, 0, args, 0, base.length);
        System.arraycopy(extra, 0, args, base.length, extra.length);
        return new SpringApplicationBuilder(DbSupportTestApplication.class)
                .web(WebApplicationType.NONE)
                .initializers(ctx -> logs = new LogCaptor())
                .run(args);
    }

    private List<String> connectedLines() {
        return logs.messages().stream().filter(line -> line.startsWith("db_connected")).toList();
    }
}
