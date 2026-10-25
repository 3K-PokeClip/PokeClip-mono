package com.pokeclip.db.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * 시험 DB가 운영 DB와 같은 DB 권한을 갖는지 잰다(POK-428). 운영 10_roles.sql은 DB의 CONNECT · TEMP를 PUBLIC에서 거두고
 * CONNECT만 계정마다 준다. 시험 DB가 PUBLIC 기본을 남기면 서버가 TEMP에 기대거나 CONNECT 부여가 빠져도 초록이다.
 */
class GrantedRoleDatabaseTest {

    // Testcontainers 2.x에서 이 클래스는 비제네릭이다. 서버와 같은 postgres:17이다.
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

    static {
        POSTGRES.start();
    }

    @Test
    void 역할은_접속되고_다른_새_역할은_접속이_거부된다() throws Exception {
        try (GrantedRoleDatabase db = GrantedRoleDatabase.create(POSTGRES, "db-support")) {
            try (Connection connection = DriverManager.getConnection(db.jdbcUrl(), db.role(), db.password())) {
                assertThat(connection.isValid(5)).isTrue();
            }

            String stranger = db.role() + "_x";
            db.asAdmin("CREATE ROLE " + stranger + " LOGIN PASSWORD 'pw'");
            try {
                assertThat(GrantedRoleDatabase.isInsufficientPrivilege(
                        catchThrowable(() -> DriverManager.getConnection(db.jdbcUrl(), stranger, "pw").close())))
                        .as("PUBLIC에 CONNECT가 남으면 아무 역할이나 붙는다").isTrue();
            } finally {
                db.asAdmin("DROP ROLE " + stranger);
            }
        }
    }

    @Test
    void 역할은_임시_표를_만들지_못한다() {
        try (GrantedRoleDatabase db = GrantedRoleDatabase.create(POSTGRES, "db-support")) {
            assertThat(GrantedRoleDatabase.isInsufficientPrivilege(
                    catchThrowable(() -> db.asRole("CREATE TEMP TABLE pc_probe (id int)"))))
                    .as("운영은 TEMP를 아무에게도 주지 않는다").isTrue();
        }
    }

    @Test
    void README의_DB_CONNECT_줄은_권한_목록에서_뺀다(@TempDir Path dir) throws Exception {
        Path readme = dir.resolve("README.md");
        Files.writeString(readme, """
                GRANT rds_iam TO app_x;
                GRANT CONNECT ON DATABASE pokeclip TO app_x;
                GRANT USAGE, CREATE ON SCHEMA app_x TO app_x;
                """);

        assertThat(GrantedRoleDatabase.readmeGrants(readme, "app_x"))
                .containsExactly("GRANT USAGE, CREATE ON SCHEMA app_x TO {role}");
    }

    @Test
    void 관리자가_만든_스키마에_준_권한으로_역할이_표를_만든다() throws Exception {
        try (GrantedRoleDatabase db = GrantedRoleDatabase.create(POSTGRES, "db-support")) {
            db.createSchema("app_x");
            db.grant(List.of("GRANT USAGE, CREATE ON SCHEMA app_x TO {role}"));

            db.asRole("CREATE TABLE app_x.pc_probe (id int)");
        }
    }
}
