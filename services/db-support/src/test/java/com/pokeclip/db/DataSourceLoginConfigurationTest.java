package com.pokeclip.db;

import com.pokeclip.db.support.IamBootSupport;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * {@code POSTGRES_AUTH}가 Boot가 만든 풀까지 닿는지를 실제 부팅으로 잰다(POK-406에서 옮겨 와 POK-407에서 넓힘).
 * 손으로 만든 풀로 재면 이 배선을 안 지나 설정이 통째로 죽어 있어도 초록이다
 * (chat-collector {@code DatasourceTimeoutTest}와 같은 이유).
 *
 * <p>시험 컨테이너는 TLS가 없어 verify-full로는 못 붙는다. 그 실패가 곧 아래 두 번째 시험의 증거다.
 */
class DataSourceLoginConfigurationTest extends PostgresTestBase {

    @TempDir
    Path tmp;

    @Test
    void 로그인_방식이_없으면_비밀번호로_붙고_풀은_건드리지_않는다() throws Exception {
        try (ConfigurableApplicationContext context = boot(
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword());
             Connection connection = context.getBean(HikariDataSource.class).getConnection()) {
            HikariDataSource ds = context.getBean(HikariDataSource.class);

            assertThat(connection.isValid(1)).isTrue();
            assertThat(ds.getCredentialsProvider()).isNull();
            assertThat(ds.getDataSourceProperties()).isEmpty();
        }
    }

    @Test
    void iam이면_풀에_토큰_공급자와_verify_full이_걸리고_socketTimeout은_그대로다() {
        Path ca = IamBootSupport.fakeCaFile(tmp);
        try (ConfigurableApplicationContext context = bootIam(ca, new AtomicInteger(), plainUrl(),
                "--spring.datasource.hikari.data-source-properties.socketTimeout=10")) {
            HikariDataSource ds = context.getBean(HikariDataSource.class);

            assertThat(ds.getCredentialsProvider()).isInstanceOf(RdsIamCredentialsProvider.class);
            assertThat(ds.getDataSourceProperties())
                    .containsEntry("sslmode", "verify-full")
                    .containsEntry("sslrootcert", ca.toString())
                    // 덮어쓰면 반개방 스톨이 다시 영영 매달린다(POK-406 S3).
                    .containsEntry("socketTimeout", "10");
        }
    }

    @Test
    void iam이면_TLS가_없는_서버에는_토큰을_보내기_전에_못_붙는다() {
        AtomicInteger signed = new AtomicInteger();
        try (ConfigurableApplicationContext context = bootIam(IamBootSupport.fakeCaFile(tmp), signed, plainUrl())) {
            HikariDataSource ds = context.getBean(HikariDataSource.class);

            Throwable thrown = catchThrowable(() -> ds.getConnection().close());

            assertThat(thrown).as("평문 서버에 붙었다면 verify-full이 안 걸린 것이다").isNotNull();
            assertThat(sqlStateOf(thrown)).as("서버가 TLS를 안 한다는 거절이어야 한다").isEqualTo("08004");
            assertThat(signed.get()).as("Boot가 만든 풀이 우리 공급자를 거쳐야 한다").isPositive();
        }
    }

    @Test
    void iam인데_CA_번들_경로가_없으면_부팅을_거부한다() {
        Throwable thrown = catchThrowable(() -> bootIam(null, new AtomicInteger(), plainUrl()).close());

        assertThat(thrown).hasStackTraceContaining("POSTGRES_SSLROOTCERT");
    }

    /**
     * iam인데 장기 비밀번호가 환경에 남아 있으면 쓰이지 않아도 태스크 정의 · 비밀 저장소에 실린 채 운영된다.
     * 「비밀번호 없는 로그인」이 겉으로만 맞는 상태라 부팅을 막는다(POK-161 「기본값 없음」과 같은 결). 값은 안 싣는다.
     */
    @Test
    void iam인데_POSTGRES_PASSWORD가_있으면_값을_싣지_않고_부팅을_거부한다() {
        Path ca = IamBootSupport.fakeCaFile(tmp);
        // 도우미는 로컬 .env를 막으려고 POSTGRES_PASSWORD를 빈 값으로 덮는다. 같은 키를 또 주면 명령행 값이
        // 쉼표로 합쳐지므로(",값") 그 덮개를 빼고 이 값 하나만 준다.
        String[] args = Stream.concat(
                Stream.of(IamBootSupport.iamArgs(POSTGRES, plainUrl(), ca))
                        .filter(arg -> !arg.startsWith("--POSTGRES_PASSWORD=")),
                Stream.of("--POSTGRES_PASSWORD=left-over-secret")).toArray(String[]::new);
        Throwable thrown = catchThrowable(() -> new SpringApplicationBuilder(DbSupportTestApplication.class)
                .web(WebApplicationType.NONE)
                .initializers(IamBootSupport.fakeSigner(POSTGRES, new AtomicInteger()))
                .run(args).close());

        assertThat(thrown).hasStackTraceContaining("POSTGRES_PASSWORD");
        assertThat(stackTraceOf(thrown)).doesNotContain("left-over-secret");
    }

    /** pgjdbc는 URL 쿼리 값을 속성보다 앞세운다. 그대로 두면 verify-full이 조용히 풀린다. */
    @Test
    void iam인데_URL에_sslmode가_있으면_부팅을_거부한다() {
        Path ca = IamBootSupport.fakeCaFile(tmp);
        Throwable thrown = catchThrowable(() -> bootIam(ca, new AtomicInteger(),
                withQuery("sslmode=require")).close());

        assertThat(thrown).hasStackTraceContaining("sslmode");
    }

    /**
     * sslmode만 막으면 부족하다. {@code sslfactory=NonValidatingFactory}는 verify-full을 둔 채로 인증서
     * 검증을 통째로 건너뛰어, 가짜 서버가 재사용 가능한 토큰을 받는다(PR #336 codex P1).
     */
    @Test
    void iam인데_URL에_sslfactory가_있으면_부팅을_거부한다() {
        Path ca = IamBootSupport.fakeCaFile(tmp);
        Throwable thrown = catchThrowable(() -> bootIam(ca, new AtomicInteger(),
                withQuery("sslfactory=org.postgresql.ssl.NonValidatingFactory")).close());

        assertThat(thrown).hasStackTraceContaining("sslfactory");
    }

    /**
     * 비밀번호 · 접속 대상도 URL로 바뀐다. {@code password=}면 iam인데 장기 비밀번호로 붙고,
     * {@code PGHOST=}면 토큰을 서명한 호스트와 실제 접속 대상이 갈린다(PR #336 codex 2판 P1).
     * 키를 하나씩 막으면 옆길이 계속 나와 쿼리를 통째로 안 받는다.
     */
    @Test
    void iam인데_URL에_쿼리가_있으면_무엇이든_부팅을_거부한다() {
        Path ca = IamBootSupport.fakeCaFile(tmp);
        for (String param : List.of("password=long-lived", "PGHOST=evil.example.com", "loggerLevel=OFF")) {
            Throwable thrown = catchThrowable(() -> bootIam(ca, new AtomicInteger(), withQuery(param)).close());

            assertThat(thrown).as(param).hasStackTraceContaining("URL 쿼리");
        }
    }

    /**
     * 거부 오류에는 키만 싣는다. 값 안의 {@code %26}(인코딩된 &)이 풀린 뒤 쪼개지면 값 조각이 키처럼 실린다
     * (PR #340 codex P2: {@code password=abc%26secret-token} → 키 {@code secret-token}).
     */
    @Test
    void iam인데_URL_쿼리를_거부할_때_인코딩된_값_조각을_키로_싣지_않는다() {
        Path ca = IamBootSupport.fakeCaFile(tmp);
        Throwable thrown = catchThrowable(() -> bootIam(ca, new AtomicInteger(),
                withQuery("password=abc%26secret-token")).close());

        assertThat(thrown).hasStackTraceContaining("URL 쿼리");
        assertThat(stackTraceOf(thrown)).doesNotContain("secret-token").doesNotContain("abc");
    }

    /** 드라이버 속성은 yml의 socketTimeout 하나만 받는다. TLS 이름이 아니어도 접속을 바꾸는 키가 있다. */
    @Test
    void iam인데_드라이버_속성에_허용_밖_키가_있으면_부팅을_거부한다() {
        Path ca = IamBootSupport.fakeCaFile(tmp);
        Throwable thrown = catchThrowable(() -> bootIam(ca, new AtomicInteger(), plainUrl(),
                "--spring.datasource.hikari.data-source-properties.gssEncMode=prefer").close());

        assertThat(thrown).hasStackTraceContaining("gssEncMode");
    }

    /** URL이 아니라 yml의 드라이버 속성으로 들어와도 같다. */
    @Test
    void iam인데_드라이버_속성에_ssl_설정이_있으면_부팅을_거부한다() {
        Path ca = IamBootSupport.fakeCaFile(tmp);
        Throwable thrown = catchThrowable(() -> bootIam(ca, new AtomicInteger(), plainUrl(),
                "--spring.datasource.hikari.data-source-properties.sslhostnameverifier=x.AllowAll").close());

        assertThat(thrown).hasStackTraceContaining("sslhostnameverifier");
    }

    /**
     * env가 있는데 비어 있으면 yml 기본값({@code ${POSTGRES_AUTH:password}})이 안 쓰이고 빈 글자가 온다. 사이드카
     * (media pgconf)는 빈 값을 password로 읽는다. 같은 이름 · 같은 뜻이어야 해서 여기도 그렇다(PR #340 codex P2).
     */
    @Test
    void 로그인_방식이_빈_값이면_비밀번호로_붙는다() throws Exception {
        try (ConfigurableApplicationContext context = boot(
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--pokeclip.datasource.auth=");
             Connection connection = context.getBean(HikariDataSource.class).getConnection()) {

            assertThat(connection.isValid(1)).isTrue();
            assertThat(context.getBean(HikariDataSource.class).getCredentialsProvider()).isNull();
        }
    }

    /** 오타가 password 모드로 조용히 떨어지면 원인 없는 인증 실패만 남는다. */
    @Test
    void 로그인_방식이_iam도_password도_아니면_부팅을_거부한다() {
        Throwable thrown = catchThrowable(() -> boot(
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--pokeclip.datasource.auth=IAM").close());

        assertThat(thrown).hasStackTraceContaining("POSTGRES_AUTH");
    }

    private static String plainUrl() {
        return IamBootSupport.plainUrl(POSTGRES);
    }

    private static String withQuery(String param) {
        return plainUrl() + "?" + param;
    }

    private static ConfigurableApplicationContext boot(String... args) {
        return new SpringApplicationBuilder(DbSupportTestApplication.class)
                .web(WebApplicationType.NONE)
                .run(args);
    }

    private static ConfigurableApplicationContext bootIam(Path ca, AtomicInteger signed, String jdbcUrl,
                                                          String... extra) {
        return new SpringApplicationBuilder(DbSupportTestApplication.class)
                .web(WebApplicationType.NONE)
                .initializers(IamBootSupport.fakeSigner(POSTGRES, signed))
                .run(IamBootSupport.iamArgs(POSTGRES, jdbcUrl, ca, extra));
    }

    private static String sqlStateOf(Throwable thrown) {
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return "";
    }

    private static String stackTraceOf(Throwable thrown) {
        StringBuilder all = new StringBuilder();
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            all.append(t).append('\n');
        }
        return all.toString();
    }
}
