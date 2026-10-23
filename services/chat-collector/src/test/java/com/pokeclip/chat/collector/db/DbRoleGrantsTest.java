package com.pokeclip.chat.collector.db;

import com.pokeclip.chat.collector.CollectorApplication;
import com.pokeclip.chat.collector.support.IntegrationTestSupport;
import com.pokeclip.chat.collector.support.StreamSegmentsFixture;
import com.pokeclip.chat.collector.sync.SegmentLedger;
import com.pokeclip.db.support.GrantedRoleDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * 운영 DB 계정 app_chat_collector의 권한 목록만으로 이 서버가 도는지 실 PG 역할로 잰다(POK-407). 목록의 정본은
 * services/README.md 「서버별 DB 계정과 권한」이고, 아래 {@link #GRANTS}가 그 줄과 같아야 한다.
 */
class DbRoleGrantsTest extends IntegrationTestSupport {

    static final String DB_USER = "app_chat_collector";

    /** 판별 서버 운영 계정 이름. V316이 이 이름에 chat_messages 읽기를 준다. */
    private static final String DETECTOR_USER = "app_chat_detector";

    static final List<String> GRANTS = List.of(
            "GRANT CREATE ON SCHEMA public TO {role}",
            // SegmentLedger: 채팅 시각을 영상 위치로 바꿀 때 조각 장부(media 표)를 읽는다.
            "GRANT SELECT ON stream_segments TO {role}");

    private static final Path README = Path.of("..", "README.md");

    @Test
    void 시험_목록이_README의_권한_줄과_같다() throws Exception {
        assertThat(GrantedRoleDatabase.readmeGrants(README, DB_USER)).containsExactlyInAnyOrderElementsOf(GRANTS);
        assertThat(java.nio.file.Files.readString(README)).contains("GRANT rds_iam TO " + DB_USER + ";");
    }

    /**
     * 목록의 권한만 가진 역할로 빈 DB에서 띄운다. 마이그레이션이 끝까지 돌고(자기 표는 자기 소유), 조각 장부 읽기가
     * 돈다. 그 뒤 한 줄씩 거두면 그 줄이 지키던 경로가 권한 부족으로 거절된다.
     */
    @Test
    void 목록의_권한만으로_마이그레이션과_조각_장부_읽기가_돌고_한_줄을_빼면_거절된다() throws Exception {
        try (GrantedRoleDatabase db = GrantedRoleDatabase.create(POSTGRES, "chat-collector")) {
            // 운영에서는 media가 먼저 만들어 둔 표다.
            StreamSegmentsFixture.ensureTable(adminJdbc(db));
            db.grant(GRANTS);

            try (ConfigurableApplicationContext context = boot(db)) {
                SegmentLedger ledger = context.getBean(SegmentLedger.class);
                assertThat(ledger.hasAnySegment("no-such-stream")).isFalse();

                db.revoke("GRANT SELECT ON stream_segments TO {role}");
                assertThat(GrantedRoleDatabase.isInsufficientPrivilege(
                        catchThrowable(() -> ledger.hasAnySegment("no-such-stream"))))
                        .as("조각 장부 읽기 권한을 빼면 거절돼야 한다").isTrue();

                db.revoke("GRANT CREATE ON SCHEMA public TO {role}");
                assertThat(GrantedRoleDatabase.isInsufficientPrivilege(
                        catchThrowable(() -> db.asRole("CREATE TABLE pc_probe (id int)"))))
                        .as("표를 만들 자리가 없으면 마이그레이션이 못 돈다").isTrue();
            }
        }
    }

    /**
     * V316: 판별 서버 계정이 있으면 chat_messages 읽기만 준다. 쓰기 · 다른 표(수집 키 등)는 안 준다.
     * 역할은 클러스터 전역이라 이 시험이 만들고 지운다.
     */
    @Test
    void 판별_서버_계정이_있으면_수집_서버_마이그레이션이_chat_messages_읽기만_준다() throws Exception {
        try (GrantedRoleDatabase db = GrantedRoleDatabase.create(POSTGRES, "chat-collector")) {
            StreamSegmentsFixture.ensureTable(adminJdbc(db));
            db.grant(GRANTS);
            JdbcTemplate admin = adminJdbc(db);
            admin.execute("CREATE ROLE " + DETECTOR_USER + " NOLOGIN");
            try {
                try (ConfigurableApplicationContext ignored = boot(db)) {
                    assertThat(privilege(admin, "chat_messages", "SELECT")).isTrue();
                    assertThat(privilege(admin, "chat_messages", "INSERT")).isFalse();
                    assertThat(privilege(admin, "chat_ingest_keys", "SELECT")).isFalse();
                    assertThat(privilege(admin, "chat_donations", "SELECT")).isFalse();
                }
            } finally {
                admin.execute("DROP OWNED BY " + DETECTOR_USER);
                admin.execute("DROP ROLE " + DETECTOR_USER);
            }
        }
    }

    private static Boolean privilege(JdbcTemplate admin, String table, String privilege) {
        return admin.queryForObject("SELECT has_table_privilege(?, ?, ?)", Boolean.class, DETECTOR_USER, table, privilege);
    }

    private static JdbcTemplate adminJdbc(GrantedRoleDatabase db) {
        return new JdbcTemplate(new DriverManagerDataSource(db.jdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    private static ConfigurableApplicationContext boot(GrantedRoleDatabase db) {
        return new SpringApplicationBuilder(CollectorApplication.class)
                .web(WebApplicationType.NONE)
                .profiles("test")
                .run("--spring.datasource.url=" + db.jdbcUrl(),
                        "--spring.datasource.username=" + db.role(),
                        "--spring.datasource.password=" + db.password());
    }
}
