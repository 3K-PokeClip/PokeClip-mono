package com.pokeclip.chat.collector.persist;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.jdbc.DataSourceUnwrapper;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.Properties;

/**
 * pgjdbc의 {@code reWriteBatchedInserts=true}가 켜져 있으면 <b>부팅을 거부한다.</b>
 *
 * <p>이 옵션은 배치 INSERT를 다중 VALUES 한 문장으로 다시 써서 빠르게 하는 대신
 * <b>행별 결과 수를 지운다</b> — 묶음 하나가 1행이라도 넣었으면 그 묶음의 모든 행이
 * {@code SUCCESS_NO_INFO}(-2)로 온다(pgjdbc 42.7.11 실측: 3건 배치 중 1건 지문 중복 →
 * {@code [-2, -2, 0]}; 묶음 안에서 충돌한 행은 -2에 묻힌다). ChatPersister는 그
 * 결과 수로 persisted/conflicts를 가르므로 이 옵션 아래서는 <b>충돌이 저장으로
 * 둔갑</b>하고 등식 received = persisted + conflicts + dropped가 조용히 거짓이 된다
 * (PR #56 P2). ON CONFLICT DO NOTHING이 붙어 있어도 재작성된다 — 실측.
 *
 * <p>실물 DataSource(Hikari)가 드라이버에 넘기는 값을 읽는다 — yml에 적힌 값이 아니라.
 * 켜는 자리가 둘이라 둘 다 본다: JDBC URL 쿼리와 {@code hikari.data-source-properties}.
 * Hikari가 아니면(우리 배선에서는 없다 — application.yml이 hikari 절을 쓴다) 여기서는
 * 못 보고, 그때는 {@link ChatPersister#flushOnce()}의 -2 거부가 마지막 방어선이다.
 */
@Component
class RewriteBatchedInsertsGuard {

    static final String PROPERTY = "reWriteBatchedInserts";

    RewriteBatchedInsertsGuard(DataSource dataSource) {
        HikariDataSource hikari = DataSourceUnwrapper.unwrap(dataSource, HikariDataSource.class);
        if (hikari == null) {
            return;
        }
        if (enabledInUrl(hikari.getJdbcUrl()) || enabledInProperties(hikari.getDataSourceProperties())) {
            throw new IllegalStateException(PROPERTY + "=true는 쓸 수 없다 — 이 옵션은 배치 결과 행 수를 "
                    + "SUCCESS_NO_INFO로 지워 persisted/conflicts 계상을 못 한다(충돌이 저장으로 둔갑). "
                    + "JDBC URL과 spring.datasource.hikari.data-source-properties에서 빼라.");
        }
    }

    /** pgjdbc는 {@code Boolean.parseBoolean}으로 읽는다 — "true"(대소문자 무관)만 켜짐이다. */
    private static boolean enabledInProperties(Properties properties) {
        return properties != null && Boolean.parseBoolean(properties.getProperty(PROPERTY));
    }

    private static boolean enabledInUrl(String jdbcUrl) {
        int q = jdbcUrl == null ? -1 : jdbcUrl.indexOf('?');
        if (q < 0) {
            return false;
        }
        // pgjdbc와 같은 규칙 — '?' 뒤를 &로 가르고 키는 대소문자를 구분한다.
        for (String pair : jdbcUrl.substring(q + 1).split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(PROPERTY)
                    && Boolean.parseBoolean(pair.substring(eq + 1))) {
                return true;
            }
        }
        return false;
    }
}
