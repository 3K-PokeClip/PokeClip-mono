package com.pokeclip.chat.detector.db;

import com.pokeclip.chat.detector.DetectorApplication;
import com.pokeclip.chat.detector.support.IntegrationTestSupport;
import com.pokeclip.db.support.DevSchemaMove;
import com.pokeclip.db.support.GrantedRoleDatabase;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.SQLException;

import static com.pokeclip.db.support.DevSchemaMove.BACKWARD;
import static com.pokeclip.db.support.DevSchemaMove.FORWARD;
import static com.pokeclip.db.support.DevSchemaMove.count;
import static com.pokeclip.db.support.DevSchemaMove.relationsIn;
import static com.pokeclip.db.support.DevSchemaMove.script;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * dev 공유 DB 의 판별 서버 표를 public 에서 app_chat_detector 로 옮기고 되돌리는 스크립트를 잰다(POK-428 · 계획 c7 · H1). 서버마다
 * 자기 시험 DB 에서 자기 블록만 돈다 — dev 처럼 public 하나에 서버 넷 · media 표가 같이 있는 상태는 재지 않는다
 * (계획 12절 한계 6, 끝 검사와 머지 전 읽기 확인이 메운다). 이전 단계를 지우는 후속 PR 에서 함께 지운다.
 */
class DevSchemaMoveTest extends IntegrationTestSupport {

    private static final String SCHEMA = "app_chat_detector";

    private static final String HISTORY = "flyway_schema_history_chat_detector";

    /** 목록에서 빠지거나 지워졌다고 칠 표 — 판별 서버의 표는 이것 하나다. */
    private static final String LEAF_TABLE = "chat_metrics";

    @Test
    void 앞으로_옮기면_새_설정이_행을_그대로_보고_되돌리면_옛_설정이_검증된다() throws Exception {
        try (GrantedRoleDatabase db = GrantedRoleDatabase.create(POSTGRES, "chat-detector")) {
            DevSchemaMove.oldLayoutFlyway(db, HISTORY, false).migrate();
            int tables = relationsIn(db, "public");
            db.asAdmin("INSERT INTO public.chat_metrics (stream_id, window_size_ms, window_start_ms, message_count,"
                    + " chatter_count) VALUES ('move-1', 5000, 1000000, 10, 5)");
            int history = count(db, "SELECT count(*) FROM public." + HISTORY);

            DevSchemaMove.apply(db, script(FORWARD));

            assertThat(relationsIn(db, "public")).as("판별 서버 표가 public 에 남았다").isZero();
            assertThat(relationsIn(db, SCHEMA)).isEqualTo(tables);
            try (ConfigurableApplicationContext context = boot(db)) {
                JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_metrics WHERE stream_id = 'move-1'", Long.class))
                        .as("옮긴 행을 새 경로가 본다").isEqualTo(1);
            }
            assertThat(count(db, "SELECT count(*) FROM " + SCHEMA + "." + HISTORY))
                    .as("새 설정은 마이그레이션을 하나도 다시 돌리지 않는다").isEqualTo(history);

            DevSchemaMove.apply(db, script(BACKWARD));

            Flyway old = DevSchemaMove.oldLayoutFlyway(db, HISTORY, false);
            old.validate();
            assertThat(old.info().pending()).as("되돌린 뒤 옛 설정의 대기 마이그레이션").isEmpty();
            assertThat(count(db, "SELECT count(*) FROM public.chat_metrics WHERE stream_id = 'move-1'")).isEqualTo(1);
            assertThat(relationsIn(db, SCHEMA)).as("되돌린 뒤 app_chat_detector 에 남은 표").isZero();
        }
    }

    @Test
    void 목록의_표가_없으면_앞방향도_역방향도_실패하고_그대로다() throws Exception {
        try (GrantedRoleDatabase db = GrantedRoleDatabase.create(POSTGRES, "chat-detector")) {
            DevSchemaMove.oldLayoutFlyway(db, HISTORY, false).migrate();
            db.asAdmin("DROP TABLE public." + LEAF_TABLE);
            int before = relationsIn(db, "public");

            assertThatThrownBy(() -> DevSchemaMove.apply(db, script(FORWARD))).isInstanceOf(SQLException.class);
            assertThat(relationsIn(db, "public")).isEqualTo(before);
            assertThat(relationsIn(db, SCHEMA)).isZero();
        }
        try (GrantedRoleDatabase db = movedDatabase()) {
            db.asAdmin("DROP TABLE " + SCHEMA + "." + LEAF_TABLE);
            int before = relationsIn(db, SCHEMA);

            assertThatThrownBy(() -> DevSchemaMove.apply(db, script(BACKWARD))).isInstanceOf(SQLException.class);
            assertThat(relationsIn(db, SCHEMA)).isEqualTo(before);
            assertThat(relationsIn(db, "public")).isZero();
        }
    }

    /** 새 마이그레이션이 표를 더하고 스크립트 목록에 안 넣은 경우의 대역이다. */
    @Test
    void public에_목록_밖_표가_남으면_앞방향이_실패하고_그대로다() throws Exception {
        try (GrantedRoleDatabase db = GrantedRoleDatabase.create(POSTGRES, "chat-detector")) {
            DevSchemaMove.oldLayoutFlyway(db, HISTORY, false).migrate();
            db.asAdmin("CREATE TABLE public.pc_unlisted (id int)");
            int before = relationsIn(db, "public");

            assertThatThrownBy(() -> DevSchemaMove.apply(db, script(FORWARD)))
                    .isInstanceOf(SQLException.class).hasMessageContaining("pc_unlisted");
            assertThat(relationsIn(db, "public")).isEqualTo(before);
        }
    }

    /** 이전 뒤 새 마이그레이션이 자기 스키마에 표를 더하고 역방향 목록에 안 넣은 경우의 대역이다. */
    @Test
    void 옮긴_뒤_자기_스키마에_목록_밖_표가_있으면_역방향이_실패하고_그대로다() throws Exception {
        try (GrantedRoleDatabase db = movedDatabase()) {
            db.asAdmin("CREATE TABLE " + SCHEMA + ".pc_unlisted (id int)");
            int before = relationsIn(db, SCHEMA);

            assertThatThrownBy(() -> DevSchemaMove.apply(db, script(BACKWARD)))
                    .isInstanceOf(SQLException.class).hasMessageContaining("pc_unlisted");
            assertThat(relationsIn(db, SCHEMA)).isEqualTo(before);
            assertThat(relationsIn(db, "public")).isZero();
        }
    }

    /** 옛 설정으로 마이그레이션하고 앞방향을 돌린 DB 다. 닫는 것은 부른 쪽이다. */
    private static GrantedRoleDatabase movedDatabase() throws SQLException {
        GrantedRoleDatabase db = GrantedRoleDatabase.create(POSTGRES, "chat-detector");
        DevSchemaMove.oldLayoutFlyway(db, HISTORY, false).migrate();
        DevSchemaMove.apply(db, script(FORWARD));
        return db;
    }

    /** dev 처럼 소유자 계정으로 새 설정(자기 스키마 경로)을 띄운다. */
    private static ConfigurableApplicationContext boot(GrantedRoleDatabase db) {
        return new SpringApplicationBuilder(DetectorApplication.class)
                .web(WebApplicationType.NONE)
                .profiles("test")
                .run("--spring.datasource.url=" + db.jdbcUrl(),
                        "--spring.datasource.username=" + db.adminUsername(),
                        "--spring.datasource.password=" + db.adminPassword());
    }
}
