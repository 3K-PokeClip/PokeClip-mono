package com.pokeclip.chat.detector.db;

import com.pokeclip.chat.detector.DetectorApplication;
import com.pokeclip.chat.detector.support.IntegrationTestSupport;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * {@code POSTGRES_AUTH=iam}이 Boot가 만든 풀까지 닿는지를 실제 부팅으로 잰다(POK-406).
 * 손으로 만든 풀로 재면 이 배선을 안 지나 설정이 통째로 죽어 있어도 초록이다
 * (chat-collector {@code DatasourceTimeoutTest}와 같은 이유).
 *
 * <p>Flyway는 끈다. 시험 컨테이너는 TLS가 없어 verify-full로는 못 붙는다 — 그 실패가 곧 아래
 * 두 번째 시험의 증거다.
 */
class RdsIamWiringTest extends IntegrationTestSupport {

    @TempDir
    Path tmp;

    @Test
    void iam이면_풀에_토큰_공급자와_verify_full이_걸리고_socketTimeout은_그대로다() throws Exception {
        try (ConfigurableApplicationContext context = bootIam(caFile(), new AtomicInteger())) {
            HikariDataSource ds = context.getBean(HikariDataSource.class);

            assertThat(ds.getCredentialsProvider()).isInstanceOf(RdsIamCredentialsProvider.class);
            assertThat(ds.getDataSourceProperties())
                    .containsEntry("sslmode", "verify-full")
                    .containsEntry("sslrootcert", caFile().toString())
                    // 덮어쓰면 반개방 스톨이 다시 영영 매달린다(S3).
                    .containsEntry("socketTimeout", "10");
        }
    }

    @Test
    void iam이면_TLS가_없는_서버에는_토큰을_보내기_전에_못_붙는다() throws Exception {
        AtomicInteger signed = new AtomicInteger();
        try (ConfigurableApplicationContext context = bootIam(caFile(), signed)) {
            HikariDataSource ds = context.getBean(HikariDataSource.class);

            Throwable thrown = catchThrowable(() -> ds.getConnection().close());

            assertThat(thrown).as("평문 서버에 붙었다면 verify-full이 안 걸린 것이다").isNotNull();
            assertThat(sqlStateOf(thrown)).as("서버가 TLS를 안 한다는 거절이어야 한다").isEqualTo("08004");
        }
    }

    @Test
    void iam인데_CA_번들_경로가_없으면_부팅을_거부한다() {
        Throwable thrown = catchThrowable(() -> bootIam(null, new AtomicInteger()).close());

        assertThat(thrown).hasStackTraceContaining("POSTGRES_SSLROOTCERT");
    }

    private Path caFile() throws Exception {
        Path ca = tmp.resolve("ca.pem");
        if (!Files.exists(ca)) {
            Files.writeString(ca, "-----BEGIN CERTIFICATE-----\n-----END CERTIFICATE-----\n");
        }
        return ca;
    }

    /** 서명기는 가짜다 — 이 시험이 실제 AWS 자격 증명을 찾아 나가면 안 된다. */
    private ConfigurableApplicationContext bootIam(Path ca, AtomicInteger signed) {
        return new SpringApplicationBuilder(DetectorApplication.class)
                .web(WebApplicationType.NONE)
                .profiles("test")
                .initializers(ctx -> ((GenericApplicationContext) ctx).registerBean(RdsTokenSigner.class,
                        () -> (host, port, user) -> {
                            signed.incrementAndGet();
                            return POSTGRES.getPassword();
                        }))
                .run("--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=",
                        "--spring.flyway.enabled=false",
                        "--pokeclip.datasource.auth=iam",
                        "--pokeclip.datasource.ssl-root-cert=" + (ca == null ? "" : ca.toString()));
    }

    private static String sqlStateOf(Throwable thrown) {
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return "";
    }
}
