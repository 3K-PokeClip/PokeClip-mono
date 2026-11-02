package com.pokeclip.auth.db;

import com.pokeclip.auth.AuthApplication;
import com.pokeclip.auth.audiotrack.AudioTrackLabelRepository;
import com.pokeclip.auth.streamkey.pairing.PairingCodeService;
import com.pokeclip.auth.support.IntegrationTestSupport;
import com.pokeclip.db.support.GrantedRoleDatabase;
import com.pokeclip.db.support.SearchPathTraps;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * 운영 DB 계정 app_auth의 권한 목록만으로 이 서버가 도는지 실 PG 역할로 잰다(POK-407). 목록의 정본은
 * services/README.md 「서버별 DB 계정과 권한」이고, 아래 {@link #GRANTS}가 그 줄과 같아야 한다.
 *
 * <p>auth는 남의 표를 안 읽는다. 목록은 표를 만들 자리 하나다 — 자기 스키마 {@code app_auth}(POK-428). 스키마는 운영
 * 10_roles.sql이 마스터 소유로 만들므로 시험도 관리자가 만든다.
 */
class DbRoleGrantsTest extends IntegrationTestSupport {

    static final String DB_USER = "app_auth";

    static final String SCHEMA = "app_auth";

    static final List<String> GRANTS = List.of("GRANT USAGE, CREATE ON SCHEMA app_auth TO {role}");

    private static final Path README = Path.of("..", "README.md");

    @Test
    void 시험_목록이_README의_권한_줄과_같다() throws Exception {
        assertThat(GrantedRoleDatabase.readmeGrants(README, DB_USER)).containsExactlyInAnyOrderElementsOf(GRANTS);
        assertThat(Files.readString(README)).contains(
                "GRANT rds_iam TO " + DB_USER + ";",
                "CREATE SCHEMA " + SCHEMA + ";",
                "GRANT CONNECT ON DATABASE pokeclip TO " + DB_USER + ";");
    }

    @Test
    void 목록의_권한만으로_마이그레이션과_스키마_검증이_돌고_표를_만들_자리를_빼면_거절된다() throws Exception {
        try (GrantedRoleDatabase db = GrantedRoleDatabase.create(POSTGRES, "auth")) {
            db.createSchema(SCHEMA);
            db.grant(GRANTS);

            try (ConfigurableApplicationContext context = boot(db)) {
                JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Long.class)).isZero();
                assertThat(jdbc.queryForObject("SELECT pg_catalog.current_schemas(true)::text", String.class))
                        .as("세션 경로는 자기 스키마 하나다(+ 암묵 pg_catalog)").isEqualTo("{pg_catalog,app_auth}");

                db.revoke(GRANTS.get(0));
                assertThat(GrantedRoleDatabase.isInsufficientPrivilege(
                        catchThrowable(() -> db.asRole("CREATE TABLE app_auth.pc_probe (id int)"))))
                        .as("자기 스키마 권한이 없으면 마이그레이션이 못 돈다").isTrue();
                assertThat(GrantedRoleDatabase.isInsufficientPrivilege(
                        catchThrowable(() -> db.asRole("CREATE TABLE public.pc_probe (id int)"))))
                        .as("public 은 표를 만들 자리가 아니다").isTrue();
            }
        }
    }

    /**
     * 맨 이름 {@code pg_advisory_xact_lock(hashtext(?))}(설계 D5 — 고치지 않는다)이 public 의 덫을 고르지 않는다. 대조로
     * 경로에 public 을 더한 컨텍스트에서 같은 경로를 돌리면 덫이 불린다 — 덫이 살아 있어야 0회가 뜻을 가진다.
     */
    @Test
    void 맨_이름_잠금은_자기_스키마_경로에서_public의_덫을_고르지_않는다() throws Exception {
        try (GrantedRoleDatabase db = GrantedRoleDatabase.create(POSTGRES, "auth")) {
            db.createSchema(SCHEMA);
            db.grant(GRANTS);
            SearchPathTraps traps = SearchPathTraps.plant(db);

            try (ConfigurableApplicationContext context = boot(db)) {
                runAdvisoryLockPaths(context);
            }
            assertThat(traps.calls()).as("자기 스키마 하나가 경로면 pg_catalog 판이 골린다").isZero();

            try (ConfigurableApplicationContext context = boot(db,
                    "--spring.datasource.hikari.connection-init-sql=SET search_path TO app_auth, public")) {
                runAdvisoryLockPaths(context);
            }
            assertThat(traps.calls()).as("대조: 경로에 public 이 있으면 덫이 골린다").isPositive();
        }
    }

    /** auth 의 advisory 잠금 자리 둘을 한 번씩 지난다. 페어링 교환은 없는 코드라 잠금 · 기록 뒤 거절된다. */
    private static void runAdvisoryLockPaths(ConfigurableApplicationContext context) {
        context.getBean(AudioTrackLabelRepository.class).replace(1L, Collections.nCopies(6, null), Instant.now());
        catchThrowable(() -> context.getBean(PairingCodeService.class).exchange("ZZZZZZZZ", "192.0.2.1"));
    }

    private static ConfigurableApplicationContext boot(GrantedRoleDatabase db, String... extra) {
        String[] args = Stream.concat(Stream.of(
                        "--spring.datasource.url=" + db.jdbcUrl(),
                        "--spring.datasource.username=" + db.role(),
                        "--spring.datasource.password=" + db.password()),
                Arrays.stream(extra)).toArray(String[]::new);
        return new SpringApplicationBuilder(AuthApplication.class)
                .web(WebApplicationType.NONE)
                .profiles("test")
                .run(args);
    }
}
