package com.pokeclip.db;

import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;

import java.net.URI;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;

/**
 * 기동 직후 연결 하나를 빌려 {@code db_connected} 한 줄을 남긴다(POK-407). 사이드카(media {@code pgconf.ConfirmConnection})와
 * 같은 이름 · 같은 칸이고, 배포 확인이 이 문자열로 줄을 찾는다. 바꾸면 그쪽도 바꾼다.
 *
 * <p>{@code tls}는 설정값이 아니라 실제 연결이 TLS인가다. 비밀번호 방식의 기본 sslmode(prefer)는 평문으로 내려갈 수
 * 있어서, DB에 자기 세션을 물어 적는다. 비밀번호 · 토큰 · URL 원문은 싣지 않는다.
 *
 * <p>판정용 줄이라 못 붙어도 기동을 막지 않는다. 비밀번호 방식은 지금까지 이 확인 없이 떴고, 그 동작을 바꾸지 않는다.
 * 못 붙으면 원인 종류만 WARN으로 남긴다(드라이버 오류 문구에는 호스트 · 사용자 이름이 섞인다).
 */
class DbConnectedLogger implements ApplicationListener<ApplicationStartedEvent> {

    private static final Logger log = LoggerFactory.getLogger(DbConnectedLogger.class);

    /** pgjdbc가 sslmode를 따로 안 받았을 때 쓰는 값이다. */
    private static final String DRIVER_DEFAULT_SSL_MODE = "prefer";

    private final Environment env;
    private final ObjectProvider<DataSource> dataSource;

    DbConnectedLogger(Environment env, ObjectProvider<DataSource> dataSource) {
        this.env = env;
        this.dataSource = dataSource;
    }

    /**
     * 러너(CommandLineRunner)보다 앞이다. 러너가 오래 돌아도 줄이 늦어지지 않는다. 풀이 없거나 둘 이상이면 아무것도
     * 안 한다({@code getIfAvailable}은 둘이면 예외를 던져 판정용 줄 때문에 기동이 죽는다).
     */
    @Override
    public void onApplicationEvent(ApplicationStartedEvent event) {
        if (dataSource.getIfUnique() instanceof HikariDataSource ds) {
            log(ds, DataSourceLoginConfiguration.isIam(env) ? "iam" : "password");
        }
    }

    private void log(HikariDataSource ds, String auth) {
        boolean tls;
        URI uri;
        try (Connection connection = ds.getConnection();
             Statement statement = connection.createStatement();
             // 자기 세션 행은 일반 사용자도 본다(남의 세션 칸만 가려진다).
             ResultSet rs = statement.executeQuery("SELECT ssl FROM pg_stat_ssl WHERE pid = pg_backend_pid()")) {
            tls = rs.next() && rs.getBoolean(1);
            // 비밀번호 방식은 URL 모양을 따지지 않는다. 낯선 꼴이라 못 읽어도 기동은 이어져야 해서 여기 안에 둔다.
            uri = URI.create(ds.getJdbcUrl().substring("jdbc:".length()));
        } catch (SQLException | RuntimeException e) {
            log.warn("db_connect_failed auth={} causeType={}", auth, e.getClass().getSimpleName());
            return;
        }
        log.info("db_connected auth={} sslmode={} tls={} user={} host={} port={} db={}",
                auth, sslMode(ds, uri), tls, ds.getUsername(), uri.getHost(),
                uri.getPort() == -1 ? 5432 : uri.getPort(), uri.getPath() == null ? "" : uri.getPath().replaceFirst("^/", ""));
    }

    /** pgjdbc와 같은 순서다: URL 쿼리 → 드라이버 속성 → 드라이버 기본값. iam이면 URL 쿼리는 거부됐고 속성에 verify-full이 박혔다. */
    private static String sslMode(HikariDataSource ds, URI uri) {
        if (uri.getQuery() != null) {
            for (String pair : uri.getQuery().split("&")) {
                if (pair.startsWith("sslmode=")) {
                    return pair.substring("sslmode=".length());
                }
            }
        }
        return ds.getDataSourceProperties().getProperty("sslmode", DRIVER_DEFAULT_SSL_MODE);
    }
}
