package com.pokeclip.chat.detector.db;

import com.pokeclip.chat.detector.DetectorApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * POK-406 완료 조건 S1~S5를 <b>실험 RDS</b>에서 잰다. {@code RDS_SPIKE_HOST}가 없으면 통째로 건너뛴다.
 * 평소 시험·CI에서는 안 돈다.
 *
 * <p>서버 전체를 실제 배선 그대로 띄운다({@code POSTGRES_AUTH=iam}과 같은 값). 서명기만 실제 서명기를
 * 감싸 발급 시각을 센다. 서명 자체는 바꾸지 않는다. 토큰 값은 어디에도 안 찍는다.
 *
 * <pre>
 * RDS_SPIKE_HOST=&lt;엔드포인트&gt; AWS_PROFILE=&lt;실험 프로필&gt; ./gradlew :chat-detector:test --tests '*RdsIamSpikeTest' -i
 * </pre>
 * 선택: {@code RDS_SPIKE_DB}(기본 pokeclip) · {@code RDS_SPIKE_USER}(기본 detector_iam) ·
 * {@code RDS_SPIKE_S5}(granted · revoked) · {@code RDS_SPIKE_LONG_MINUTES}(S2, 32 이상).
 */
@EnabledIfEnvironmentVariable(named = "RDS_SPIKE_HOST", matches = ".+")
class RdsIamSpikeTest {

    private static final String HOST = System.getenv("RDS_SPIKE_HOST");
    private static final String DB = envOr("RDS_SPIKE_DB", "pokeclip");
    private static final String USER = envOr("RDS_SPIKE_USER", "detector_iam");
    /** media 이미지가 고정해 둔 RDS 통합 CA 번들이다. 작업 디렉터리는 services/chat-detector. */
    private static final Path CA_BUNDLE =
            Path.of("..", "..", "media", "certs", "rds-global-bundle.pem").toAbsolutePath().normalize();

    private final List<Instant> issuedAt = new CopyOnWriteArrayList<>();

    @Test
    void S1_S3_S4_마이그레이션_소켓시한_TLS() {
        try (ConfigurableApplicationContext context = boot()) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);

            // S1: 부팅이 끝났다는 것이 곧 Flyway가 IAM 사용자로 돌았다는 뜻이다. 이력 표로 확인한다.
            List<Map<String, Object>> history = jdbc.queryForList("""
                    SELECT version, success, installed_by
                      FROM flyway_schema_history_chat_detector ORDER BY installed_rank""");
            print("S1", "current_user=" + jdbc.queryForObject("SELECT current_user", String.class)
                    + " history=" + history);
            assertThat(history).anySatisfy(row -> {
                assertThat(row.get("version")).isEqualTo("401");
                assertThat(row.get("success")).isEqualTo(true);
                assertThat(row.get("installed_by")).isEqualTo(USER);
            });

            // S4: 실제 연결이 TLS인지. 설정이 아니라 서버가 본 연결 상태를 묻는다.
            Map<String, Object> ssl = jdbc.queryForMap(
                    "SELECT ssl, version, cipher FROM pg_stat_ssl WHERE pid = pg_backend_pid()");
            print("S4", "pg_stat_ssl=" + ssl + " sslmode=" + RdsIamDataSourceConfiguration.SSL_MODE);
            assertThat(ssl.get("ssl")).isEqualTo(true);

            // S3: 40초 걸리는 질의가 socketTimeout 10초에 끊기는지. 응답이 안 오는 상대와 같은 길이다
            // (드라이버가 소켓 읽기에서 기다린다).
            //
            // 🔴 TLS에서는 호출자가 약 20초(시한의 두 배)를 기다린다(2026-10-23 실측: 평문 10.0초 · TLS 20.0초).
            // 10초에 Read timed out이 난 뒤, 드라이버가 연결을 버리며 TLS 소켓을 닫는데 JDK가 닫기 전에 남은
            // 입력을 한 번 더 읽어 비운다(SSLSocketImpl.close → bruteForceCloseInput, 스레드 덤프로 확인).
            // 그 읽기에도 같은 시한이 걸려 상한은 두 배로 정해진다. 15초 질의면 서버가 15초에 답해 15초에 풀린다.
            int sleepSeconds = Integer.parseInt(envOr("RDS_SPIKE_S3_SLEEP", "40"));
            Instant start = Instant.now();
            Throwable thrown = catchThrowable(() -> jdbc.execute("SELECT pg_sleep(" + sleepSeconds + ")"));
            Duration held = Duration.between(start, Instant.now());
            print("S3", "sleep=" + sleepSeconds + "s held=" + held.toMillis() + "ms sqlState=" + sqlStateOf(thrown)
                    + " chain=" + causeChain(thrown));
            assertThat(thrown).as("질의가 끝까지 돌았다면 시한이 안 걸린 것이다").isNotNull();
            assertThat(causeChain(thrown)).contains("SocketTimeoutException");
            assertThat(held).isBetween(Duration.ofSeconds(9), Duration.ofSeconds(25));
            assertThat(sqlStateOf(thrown)).startsWith("08");
        }
    }

    /** 관리 세션이 GRANT를 준 상태(granted)와 뺀 상태(revoked)에서 한 번씩 돌린다. */
    @Test
    @EnabledIfEnvironmentVariable(named = "RDS_SPIKE_S5", matches = "granted|revoked")
    void S5_교차_SELECT는_GRANT로만_통과한다() {
        boolean granted = "granted".equals(System.getenv("RDS_SPIKE_S5"));
        try (ConfigurableApplicationContext context = boot()) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);

            Throwable thrown = catchThrowable(() ->
                    jdbc.queryForObject("SELECT count(*) FROM chat_messages", Long.class));
            print("S5", "expect=" + (granted ? "granted" : "revoked") + " sqlState=" + sqlStateOf(thrown));

            if (granted) {
                assertThat(thrown).isNull();
            } else {
                assertThat(sqlStateOf(thrown)).isEqualTo("42501");
            }
        }
    }

    /**
     * S2: 연결 수명을 2분으로 줄이고 토큰 수명(15분)을 넘겨 돌린다. 판별 한 바퀴(1초)도 같은 풀을 쓰므로
     * 실제 부하가 그대로 실린다. 30초마다 한 번 붙어 그 연결이 언제 만들어졌는지를 서버에 묻는다.
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "RDS_SPIKE_LONG_MINUTES", matches = "\\d+")
    void S2_토큰_수명을_넘겨도_새_연결이_붙는다() throws Exception {
        Duration runFor = Duration.ofMinutes(Long.parseLong(System.getenv("RDS_SPIKE_LONG_MINUTES")));
        List<String> failures = new ArrayList<>();
        OffsetDateTime newestBackend = null;
        Instant begin = Instant.now();

        try (ConfigurableApplicationContext context = boot("--spring.datasource.hikari.max-lifetime=120000")) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            while (Duration.between(begin, Instant.now()).compareTo(runFor) < 0) {
                try {
                    OffsetDateTime backendStart = jdbc.queryForObject(
                            "SELECT backend_start FROM pg_stat_activity WHERE pid = pg_backend_pid()",
                            OffsetDateTime.class);
                    newestBackend = Stream.of(newestBackend, backendStart).filter(Objects::nonNull)
                            .max(OffsetDateTime::compareTo).orElseThrow();
                } catch (RuntimeException e) {
                    failures.add(Instant.now() + " " + sqlStateOf(e));
                }
                Thread.sleep(30_000);
            }
        }

        Instant firstToken = issuedAt.getFirst();
        long afterExpiry = issuedAt.stream().filter(t -> t.isAfter(firstToken.plus(Duration.ofMinutes(15)))).count();
        print("S2", "ran=" + runFor + " tokensIssued=" + issuedAt.size() + " issuedAfter15m=" + afterExpiry
                + " newestBackendStart=" + newestBackend + " firstToken=" + firstToken + " failures=" + failures);
        assertThat(failures).isEmpty();
        assertThat(afterExpiry).as("첫 토큰 만료 뒤에도 새 연결이 토큰을 받아 붙어야 한다").isPositive();
        assertThat(newestBackend.toInstant()).isAfter(firstToken.plus(Duration.ofMinutes(15)));
    }

    private ConfigurableApplicationContext boot(String... extra) {
        RdsTokenSigner real = RdsTokenSigner.rds(Region.AP_NORTHEAST_2, DefaultCredentialsProvider.builder().build());
        List<String> args = new ArrayList<>(List.of(
                "--spring.datasource.url=jdbc:postgresql://" + HOST + ":5432/" + DB,
                "--spring.datasource.username=" + USER,
                "--spring.datasource.password=",
                "--pokeclip.datasource.auth=iam",
                "--pokeclip.datasource.ssl-root-cert=" + CA_BUNDLE,
                "--pokeclip.internal-api.token=spike-unused"));
        args.addAll(List.of(extra));
        return new SpringApplicationBuilder(DetectorApplication.class)
                .web(WebApplicationType.NONE)
                .initializers(ctx -> ((GenericApplicationContext) ctx).registerBean(RdsTokenSigner.class,
                        () -> (host, port, user) -> {
                            String token = real.sign(host, port, user);
                            issuedAt.add(Instant.now());
                            return token;
                        }))
                .run(args.toArray(String[]::new));
    }

    private static void print(String check, String summary) {
        System.out.println("SPIKE " + check + " " + summary);
    }

    private static String sqlStateOf(Throwable thrown) {
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return "";
    }

    /** 예외 사슬의 종류와 문구. 드라이버 문구에는 비밀이 안 섞인다(접속 문자열 · 토큰을 싣지 않는다). */
    private static String causeChain(Throwable thrown) {
        StringBuilder chain = new StringBuilder();
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            chain.append(" <- ").append(t.getClass().getSimpleName()).append(": ").append(t.getMessage());
        }
        return chain.toString();
    }

    private static String envOr(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
