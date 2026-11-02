package com.pokeclip.db.support;

import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 덫이 살아 있고(경로에 public 이 있으면 맨 이름 호출이 덫을 고른다), 덫 없는 스키마 하나가 경로면 0회인지 잰다
 * (POK-428). 서버 시험의 「덫 0회」 는 이 대조가 있어야 뜻을 가진다.
 */
class SearchPathTrapsTest {

    // Testcontainers 2.x에서 이 클래스는 비제네릭이다. 서버와 같은 postgres:17이다.
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

    /** 3번 서버 넷이 쓰는 꼴 그대로다(auth AudioTrackLabelRepository · PairingAttemptRecorder). */
    private static final String LOCK = "SELECT pg_advisory_xact_lock(hashtext(?))";

    static {
        POSTGRES.start();
    }

    @Test
    void 경로에_public이_있으면_맨_이름_잠금이_덫을_부르고_자기_스키마_하나면_부르지_않는다() throws Exception {
        try (GrantedRoleDatabase db = GrantedRoleDatabase.create(POSTGRES, "db-support")) {
            db.createSchema("app_x");
            db.grant(List.of("GRANT USAGE, CREATE ON SCHEMA app_x TO {role}"));
            SearchPathTraps traps = SearchPathTraps.plant(db);

            lockAs(db, "app_x");
            assertThat(traps.calls()).as("덫 없는 스키마 하나가 경로면 pg_catalog 판이 골린다").isZero();

            lockAs(db, "app_x, public");
            assertThat(traps.calls()).as("경로에 public 이 있으면 덫이 정확히 맞는 판이라 골린다").isPositive();
        }
    }

    /** 시험 역할로 경로를 searchPath 로 두고 서버와 같은 꼴로 잠근다(varchar 인자). */
    private static void lockAs(GrantedRoleDatabase db, String searchPath) throws SQLException {
        try (Connection connection = DriverManager.getConnection(db.jdbcUrl(), db.role(), db.password())) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET search_path TO " + searchPath);
            }
            try (PreparedStatement lock = connection.prepareStatement(LOCK)) {
                lock.setString(1, "pc-key");
                lock.execute();
            }
        }
    }
}
