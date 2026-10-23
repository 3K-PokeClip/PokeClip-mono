package com.pokeclip.db.support;

import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 서버 DB 계정의 권한 목록을 실 PG 역할로 재는 시험 도우미다(POK-407, 선례 media {@code pgtest/role.go}).
 *
 * <p>운영은 서버마다 DB 계정이 따로고(app_auth 등), 각 서버가 자기 표를 자기 계정으로 만든다(Flyway). 로컬 compose ·
 * CI는 소유자 계정 하나로 붙어 그 경계를 못 잰다. 그래서 시험마다 빈 DB와 「목록의 권한만 가진」 역할을 만들어
 * 그 역할로 서버를 띄운다. 마이그레이션이 끝까지 돌면 자기 표 권한은 소유로 충분하다는 뜻이고, 남는 것은 남의 표에
 * 대한 교차 접근뿐이다.
 *
 * <p>목록의 한 줄은 {@code GRANT ... TO {role}} 꼴이다. README(services/README.md 「서버별 DB 계정과 권한」)의
 * 같은 줄과 {@link #readmeGrants}로 대조한다. 목록 = 시험 = README.
 */
public final class GrantedRoleDatabase implements AutoCloseable {

    /** 권한 부족 SQLSTATE. */
    public static final String INSUFFICIENT_PRIVILEGE = "42501";

    private static final String ROLE = "{role}";

    private final PostgreSQLContainer postgres;
    private final String database;
    private final String role;
    private final String password;

    private GrantedRoleDatabase(PostgreSQLContainer postgres, String database, String role, String password) {
        this.postgres = postgres;
        this.database = database;
        this.role = role;
        this.password = password;
    }

    /**
     * 빈 DB 하나와 로그인 역할 하나를 만든다. 이름에 난수를 붙인다(역할은 클러스터 전역이라 다른 시험과 안 겹치게).
     * 역할에는 아직 아무 권한도 없다. {@link #grant}로 목록을 준다.
     */
    public static GrantedRoleDatabase create(PostgreSQLContainer postgres, String server) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String name = "pc_" + server.replace('-', '_') + "_" + suffix;
        GrantedRoleDatabase db = new GrantedRoleDatabase(postgres, name, name, "pw" + suffix);
        db.onAdminDatabase("CREATE DATABASE " + name);
        db.onAdminDatabase("CREATE ROLE " + name + " LOGIN PASSWORD '" + db.password + "'");
        return db;
    }

    public String jdbcUrl() {
        return "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/" + database;
    }

    public String role() {
        return role;
    }

    public String password() {
        return password;
    }

    /** 이 DB에서 관리자(시험 컨테이너 소유자)로 문장을 돌린다. 남의 표를 심을 때 쓴다(운영에서 media가 만드는 표). */
    public void asAdmin(String sql) {
        execute(jdbcUrl(), postgres.getUsername(), postgres.getPassword(), sql);
    }

    /** 이 DB에서 시험 역할로 문장을 돌린다. */
    public void asRole(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl(), role, password);
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /** {@code GRANT ... TO {role}} 줄들을 이 역할에 준다. */
    public void grant(List<String> grants) {
        for (String grant : grants) {
            asAdmin(grant.replace(ROLE, role));
        }
    }

    /** {@code GRANT X ON Y TO {role}} 한 줄을 거둔다. 한 줄을 빼면 거부되는지 잴 때 쓴다. */
    public void revoke(String grant) {
        Matcher m = Pattern.compile("^GRANT (.+) TO \\{role}$").matcher(grant);
        if (!m.matches()) {
            throw new IllegalArgumentException("GRANT ... TO {role} 꼴이 아니다: " + grant);
        }
        asAdmin("REVOKE " + m.group(1) + " FROM " + role);
    }

    /**
     * README에서 {@code GRANT ... TO <dbUser>;} 줄을 모아 {@code {role}} 꼴로 돌려준다. {@code rds_iam}은 뺀다. RDS에만
     * 있는 역할이라 시험 DB에서는 줄 수 없고, 그 줄이 README에 있는지는 따로 본다.
     */
    public static Set<String> readmeGrants(Path readme, String dbUser) {
        String text;
        try {
            text = Files.readString(readme);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Set<String> grants = new TreeSet<>();
        Matcher m = Pattern.compile("(?m)^(GRANT (.+) TO " + Pattern.quote(dbUser) + ");\\s*$").matcher(text);
        while (m.find()) {
            if (!m.group(2).equals("rds_iam")) {
                grants.add("GRANT " + m.group(2) + " TO " + ROLE);
            }
        }
        return grants;
    }

    /** 원인 사슬에 권한 부족(42501)이 있나. */
    public static boolean isInsufficientPrivilege(Throwable thrown) {
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && INSUFFICIENT_PRIVILEGE.equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    /** 이 역할의 접속을 끊고 DB와 역할을 지운다. */
    @Override
    public void close() {
        onAdminDatabase("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
        onAdminDatabase("DROP ROLE IF EXISTS " + role);
    }

    private void onAdminDatabase(String sql) {
        execute(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), sql);
    }

    private static void execute(String url, String user, String password, String sql) {
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("시험 DB 준비 실패: " + sql, e);
        }
    }
}
