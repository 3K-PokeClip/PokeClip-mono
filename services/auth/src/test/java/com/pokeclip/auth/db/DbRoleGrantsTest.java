package com.pokeclip.auth.db;

import com.pokeclip.auth.AuthApplication;
import com.pokeclip.auth.support.IntegrationTestSupport;
import com.pokeclip.db.support.GrantedRoleDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * 운영 DB 계정 app_auth의 권한 목록만으로 이 서버가 도는지 실 PG 역할로 잰다(POK-407). 목록의 정본은
 * services/README.md 「서버별 DB 계정과 권한」이고, 아래 {@link #GRANTS}가 그 줄과 같아야 한다.
 *
 * <p>auth는 남의 표를 안 읽는다. 목록은 표를 만들 자리 하나다.
 */
class DbRoleGrantsTest extends IntegrationTestSupport {

    static final String DB_USER = "app_auth";

    static final List<String> GRANTS = List.of("GRANT CREATE ON SCHEMA public TO {role}");

    private static final Path README = Path.of("..", "README.md");

    @Test
    void 시험_목록이_README의_권한_줄과_같다() throws Exception {
        assertThat(GrantedRoleDatabase.readmeGrants(README, DB_USER)).containsExactlyInAnyOrderElementsOf(GRANTS);
        assertThat(Files.readString(README)).contains("GRANT rds_iam TO " + DB_USER + ";");
    }

    @Test
    void 목록의_권한만으로_마이그레이션과_스키마_검증이_돌고_표를_만들_자리를_빼면_거절된다() throws Exception {
        try (GrantedRoleDatabase db = GrantedRoleDatabase.create(POSTGRES, "auth")) {
            db.grant(GRANTS);

            try (ConfigurableApplicationContext context = new SpringApplicationBuilder(AuthApplication.class)
                    .web(WebApplicationType.NONE)
                    .profiles("test")
                    .run("--spring.datasource.url=" + db.jdbcUrl(),
                            "--spring.datasource.username=" + db.role(),
                            "--spring.datasource.password=" + db.password())) {
                JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Long.class)).isZero();

                db.revoke("GRANT CREATE ON SCHEMA public TO {role}");
                assertThat(GrantedRoleDatabase.isInsufficientPrivilege(
                        catchThrowable(() -> db.asRole("CREATE TABLE pc_probe (id int)"))))
                        .as("표를 만들 자리가 없으면 마이그레이션이 못 돈다").isTrue();
            }
        }
    }
}
