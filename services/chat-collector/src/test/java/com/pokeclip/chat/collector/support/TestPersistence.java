package com.pokeclip.chat.collector.support;

import com.pokeclip.chat.collector.persist.ChatBuffer;
import com.pokeclip.chat.collector.persist.ChatPersister;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 적재를 검증하지 않는 테스트가 러너 생성자를 채울 때 쓰는 no-op 더미.
 * 이름이 곧 의도다 — 인라인 {@code new ChatPersister(new JdbcTemplate(), …)}가
 * 20여 곳에 복붙되면 "왜 datasource 없는 JdbcTemplate인가"를 매번 다시 읽게 된다.
 */
public final class TestPersistence {

    private TestPersistence() { }

    /** 러너가 offer만 하고 아무도 안 읽는 바구니. */
    public static ChatBuffer unusedBuffer() {
        return new ChatBuffer(1_000);
    }

    /**
     * {@code start()}를 부르지 않으므로 flush가 돌지 않는 더미다 — datasource 없는
     * JdbcTemplate이라 만약 flush가 돌면 조용히 넘어가는 대신 예외로 드러난다.
     * 러너의 stop()이 close()를 불러도 무해하다(멱등 + close가 예외를 삼킨다).
     */
    public static ChatPersister disabledPersister() {
        return new ChatPersister(new JdbcTemplate(), new ChatBuffer(1_000));
    }

    /**
     * 본문에 {@code poisonMarker}가 들어간 행만 SQLSTATE 22021(data exception)로
     * 거부한다 — 배치·단건 둘 다. NUL이 생성 지점에서 소멸된 뒤 격리 기계를
     * 결정적으로 재는 수단이다(실물 22류 재현은 timestamp 오버플로 테스트가 따로 든다).
     */
    public static JdbcTemplate rejecting22(javax.sql.DataSource dataSource, String poisonMarker) {
        return new JdbcTemplate(dataSource) {
            @Override
            public int[] batchUpdate(String sql, java.util.List<Object[]> args) {
                if (args.stream().anyMatch(row -> contentOf(row).contains(poisonMarker))) {
                    throw poison();
                }
                return super.batchUpdate(sql, args);
            }

            @Override
            public int update(String sql, Object... args) {
                if (contentOf(args).contains(poisonMarker)) {
                    throw poison();
                }
                return super.update(sql, args);
            }
        };
    }

    /** INSERT 파라미터 배열의 셋째 자리가 content다 — ChatPersister.toRow와 같은 순서. */
    private static String contentOf(Object[] row) {
        return (String) row[2];
    }

    private static org.springframework.dao.DataIntegrityViolationException poison() {
        return new org.springframework.dao.DataIntegrityViolationException(
                "data", new java.sql.SQLException("bad data", "22021"));
    }
}
