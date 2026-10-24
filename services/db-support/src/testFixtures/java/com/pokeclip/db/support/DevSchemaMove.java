package com.pokeclip.db.support;

import org.flywaydb.core.Flyway;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

/**
 * dev 공유 DB 의 앱 표 이전 스크립트(services/scripts)를 서버마다 시험하는 공통부다(POK-428). 서버 넷의
 * {@code DevSchemaMoveTest} 가 쓴다.
 *
 * <p>dev 는 이 PR 전까지 서버 넷이 public 하나에 표를 만들었다(경로 public · baseline-on-migrate · baseline 0 · 서버마다
 * 이력 표). {@link #oldLayoutFlyway} 가 그 옛 설정을 그대로 흉내 내고, {@link #apply} 가 스크립트를 관리자로 돌린다 —
 * dev 의 배포 잡도 소유자 계정으로 psql 을 돌린다. 이전 단계를 지우는 후속 PR 에서 이 클래스도 지운다.
 */
public final class DevSchemaMove {

    /** 앞방향 — public 에서 자기 스키마로. */
    public static final String FORWARD = "dev-move-to-app-schemas.sql";

    /** 역방향 — 자기 스키마에서 public 으로(H1). */
    public static final String BACKWARD = "dev-move-back-to-public.sql";

    /** 서버 모듈 디렉터리(시험의 작업 디렉터리)에서 본 스크립트 디렉터리다. */
    private static final Path SCRIPTS = Path.of("..", "scripts");

    private DevSchemaMove() {
    }

    /** 스크립트 본문이다. */
    public static String script(String name) {
        try {
            return Files.readString(SCRIPTS.resolve(name));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 이 PR 전의 설정(서버 application.yml 의 옛 flyway 블록)인 Flyway 다. schemas 를 주지 않아 관리자 세션의 현재
     * 스키마(public)에 표 · 이력 표를 만든다. sessionLock 은 수집 서버의 {@code transactional-lock: false} 다.
     */
    public static Flyway oldLayoutFlyway(GrantedRoleDatabase db, String historyTable, boolean sessionLock) {
        // 속성 묶음을 먼저 준다 — 뒤의 개별 설정이 그것에 덮이지 않게.
        return Flyway.configure()
                .configuration(Map.of("flyway.postgresql.transactional.lock", Boolean.toString(!sessionLock)))
                .dataSource(db.adminDataSource())
                .table(historyTable)
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .locations("classpath:db/migration")
                .load();
    }

    /** sql(스크립트 본문 — DO 블록 하나)을 관리자로 돌린다. 실패는 SQLException 그대로 던진다(시험이 잰다). */
    public static void apply(GrantedRoleDatabase db, String sql) throws SQLException {
        try (Connection connection = db.adminConnection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /** schema 에 있는 표 · 뷰 · 시퀀스(relkind r · p · v · m · S) 수다. */
    public static int relationsIn(GrantedRoleDatabase db, String schema) {
        return count(db, "SELECT count(*) FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n"
                + " ON n.oid = c.relnamespace WHERE c.relkind IN ('r', 'p', 'v', 'm', 'S') AND n.nspname = '"
                + schema + "'");
    }

    /** 관리자로 sql(정수 하나를 내는 질의)을 돌린 결과다. */
    public static int count(GrantedRoleDatabase db, String sql) {
        try (Connection connection = db.adminConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getInt(1);
        } catch (SQLException e) {
            throw new IllegalStateException("시험 질의 실패: " + sql, e);
        }
    }
}
