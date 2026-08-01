package com.pokeclip.core.auth;

import com.pokeclip.core.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaMigrationTest extends IntegrationTestSupport {

    private final JdbcTemplate jdbc;

    SchemaMigrationTest(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Test
    void Flyway가_users와_refresh_tokens_테이블을_만든다() {
        var tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
                String.class);

        assertThat(tables).contains("users", "refresh_tokens");
    }

    @Test
    void Flyway_이력_테이블_이름이_모듈별로_분리돼_있다() {
        var tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
                String.class);

        assertThat(tables).contains("flyway_schema_history_core");
        assertThat(tables).doesNotContain("flyway_schema_history");
    }

    @Test
    void google_sub에_UNIQUE_제약이_걸려_있다() {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.table_constraints tc
                JOIN information_schema.key_column_usage kcu
                  ON tc.constraint_name = kcu.constraint_name
                WHERE tc.table_name = 'users'
                  AND tc.constraint_type = 'UNIQUE'
                  AND kcu.column_name = 'google_sub'
                """, Integer.class);

        assertThat(count).isEqualTo(1);
    }
}
