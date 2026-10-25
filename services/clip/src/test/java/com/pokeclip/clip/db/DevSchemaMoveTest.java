package com.pokeclip.clip.db;

import com.pokeclip.clip.ClipApplication;
import com.pokeclip.clip.support.IntegrationTestSupport;
import com.pokeclip.db.support.DevSchemaMove;
import com.pokeclip.db.support.GrantedRoleDatabase;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;

import static com.pokeclip.db.support.DevSchemaMove.BACKWARD;
import static com.pokeclip.db.support.DevSchemaMove.FORWARD;
import static com.pokeclip.db.support.DevSchemaMove.count;
import static com.pokeclip.db.support.DevSchemaMove.relationsIn;
import static com.pokeclip.db.support.DevSchemaMove.script;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * dev 공유 DB 의 clip 표를 public 에서 app_clip 으로 옮기고 되돌리는 스크립트를 잰다(POK-428 · 계획 c7 · H1). clip 은
 * 표 밖 객체가 둘 있다 — V202 의 독립 시퀀스 jump_card_event_seq 와 트리거 함수 jump_cards_touch(). 1번 장부는
 * 실물 DDL(prod/runtime/sql/stream_schema.sql — 표 넷과 트리거 함수 stream_segments_immutable_axes)로 public 에 먼저
 * 깔고, 앞방향 끝 검사가 그것을 남겨 두는지 본다. 서버마다 자기 시험 DB 에서 자기 블록만 돈다(계획 12절 한계 6). 이전
 * 단계를 지우는 후속 PR 에서 함께 지운다.
 */
class DevSchemaMoveTest extends IntegrationTestSupport {

    private static final String SCHEMA = "app_clip";

    private static final String HISTORY = "flyway_schema_history_clip";

    /** 목록에서 빠지거나 지워졌다고 칠 표 — 다른 표가 이 표를 참조하지 않는다. */
    private static final String LEAF_TABLE = "purged_streamers";

    /** 앞방향 스크립트에서 독립 시퀀스를 옮기는 줄이다(빠뜨린 판의 대역). */
    private static final String SEQUENCE_LINE = "ALTER SEQUENCE public.jump_card_event_seq SET SCHEMA app_clip;";

    /** 1번 장부의 실물 DDL(media ddl.go 의 schemaDDL 사본 — media 계약 시험이 바이트 같음을 강제한다). */
    private static final Path MEDIA_DDL = Path.of("..", "..", "infra", "terraform", "prod", "runtime", "sql",
            "stream_schema.sql");

    /** public 에 남는 1번 장부 표 넷. */
    private static final int MEDIA_TABLES = 4;

    @Test
    void 앞으로_옮기면_새_설정이_행과_카드_순번을_그대로_쓰고_되돌리면_옛_설정이_검증된다() throws Exception {
        try (GrantedRoleDatabase db = oldLayoutDatabase()) {
            int tables = relationsIn(db, "public") - MEDIA_TABLES;
            db.asAdmin("INSERT INTO public.broadcasts (stream_id, streamer_id, status, last_sequence)"
                    + " VALUES ('move-1', 'streamer-1', 'live', 1)");
            int history = count(db, "SELECT count(*) FROM public." + HISTORY);

            DevSchemaMove.apply(db, script(FORWARD));

            assertThat(relationsIn(db, "public")).as("public 에는 1번 장부 넷만 남는다").isEqualTo(MEDIA_TABLES);
            assertThat(count(db, "SELECT count(*) FROM pg_catalog.pg_proc p JOIN pg_catalog.pg_namespace n"
                    + " ON n.oid = p.pronamespace WHERE n.nspname = 'public'"))
                    .as("public 에는 1번 트리거 함수 하나만 남는다").isEqualTo(1);
            assertThat(relationsIn(db, SCHEMA)).isEqualTo(tables);
            try (ConfigurableApplicationContext context = boot(db)) {
                JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM broadcasts WHERE stream_id = 'move-1'",
                        Long.class)).as("옮긴 행을 새 경로가 본다").isEqualTo(1);
                jdbc.update("INSERT INTO jump_cards (stream_id, source, stream_timestamp_ms, window_start_ms,"
                        + " window_end_ms, event_seq) VALUES ('move-1', 'hotkey', 5, 0, 10, 0)");
                long inserted = jdbc.queryForObject("SELECT event_seq FROM jump_cards", Long.class);
                jdbc.update("UPDATE jump_cards SET score = 1");
                long updated = jdbc.queryForObject("SELECT event_seq FROM jump_cards", Long.class);
                assertThat(inserted).as("트리거 함수가 옮긴 독립 시퀀스를 새 경로로 찾는다").isPositive();
                assertThat(updated).as("고치면 순번이 다시 오른다").isGreaterThan(inserted);
            }
            assertThat(count(db, "SELECT count(*) FROM " + SCHEMA + "." + HISTORY))
                    .as("새 설정은 마이그레이션을 하나도 다시 돌리지 않는다").isEqualTo(history);

            DevSchemaMove.apply(db, script(BACKWARD));

            Flyway old = DevSchemaMove.oldLayoutFlyway(db, HISTORY, false);
            old.validate();
            assertThat(old.info().pending()).as("되돌린 뒤 옛 설정의 대기 마이그레이션").isEmpty();
            assertThat(count(db, "SELECT count(*) FROM public.broadcasts WHERE stream_id = 'move-1'")).isEqualTo(1);
            assertThat(relationsIn(db, SCHEMA)).as("되돌린 뒤 app_clip 에 남은 표 · 시퀀스").isZero();
        }
    }

    @Test
    void 목록의_표가_없으면_앞방향도_역방향도_실패하고_그대로다() throws Exception {
        try (GrantedRoleDatabase db = oldLayoutDatabase()) {
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
            assertThat(relationsIn(db, "public")).isEqualTo(MEDIA_TABLES);
        }
    }

    /** 새 마이그레이션이 표를 더하고 스크립트 목록에 안 넣은 경우의 대역이다. */
    @Test
    void public에_목록_밖_표가_남으면_앞방향이_실패하고_그대로다() throws Exception {
        try (GrantedRoleDatabase db = oldLayoutDatabase()) {
            db.asAdmin("CREATE TABLE public.pc_unlisted (id int)");
            int before = relationsIn(db, "public");

            assertThatThrownBy(() -> DevSchemaMove.apply(db, script(FORWARD)))
                    .isInstanceOf(SQLException.class).hasMessageContaining("pc_unlisted");
            assertThat(relationsIn(db, "public")).isEqualTo(before);
        }
    }

    /** 표만 보는 검사는 독립 시퀀스를 빠뜨려도 통과한다 — 끝 검사가 public 에 남은 시퀀스를 잡는다. */
    @Test
    void 독립_시퀀스를_빠뜨린_앞방향은_실패하고_그대로다() throws Exception {
        String body = script(FORWARD);
        assertThat(body).contains(SEQUENCE_LINE);
        String withoutSequence = body.replace(SEQUENCE_LINE, "");
        try (GrantedRoleDatabase db = oldLayoutDatabase()) {
            int before = relationsIn(db, "public");

            assertThatThrownBy(() -> DevSchemaMove.apply(db, withoutSequence))
                    .isInstanceOf(SQLException.class).hasMessageContaining("jump_card_event_seq");
            assertThat(relationsIn(db, "public")).isEqualTo(before);
            assertThat(relationsIn(db, SCHEMA)).isZero();
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
            assertThat(relationsIn(db, "public")).isEqualTo(MEDIA_TABLES);
        }
    }

    /**
     * dev 의 이전 전 상태다 — 1번 장부가 public 에 먼저 있고(media 가 만든다), 옛 설정 Flyway 가 그 위에서 baseline
     * 뒤 clip 표를 public 에 만든다. 닫는 것은 부른 쪽이다.
     */
    private static GrantedRoleDatabase oldLayoutDatabase() throws IOException {
        GrantedRoleDatabase db = GrantedRoleDatabase.create(POSTGRES, "clip");
        db.asAdmin(Files.readString(MEDIA_DDL));
        DevSchemaMove.oldLayoutFlyway(db, HISTORY, false).migrate();
        return db;
    }

    /** 옛 설정으로 마이그레이션하고 앞방향을 돌린 DB 다. 닫는 것은 부른 쪽이다. */
    private static GrantedRoleDatabase movedDatabase() throws IOException, SQLException {
        GrantedRoleDatabase db = oldLayoutDatabase();
        DevSchemaMove.apply(db, script(FORWARD));
        return db;
    }

    /** dev 처럼 소유자 계정으로 새 설정(자기 스키마 경로)을 띄운다. */
    private static ConfigurableApplicationContext boot(GrantedRoleDatabase db) {
        return new SpringApplicationBuilder(ClipApplication.class)
                // 보안 필터 사슬이 HttpSecurity를 받아 웹 없이 못 뜬다. 아무 빈 포트로 띄운다.
                .web(WebApplicationType.SERVLET)
                .profiles("test")
                .run("--server.port=0",
                        "--spring.datasource.url=" + db.jdbcUrl(),
                        "--spring.datasource.username=" + db.adminUsername(),
                        "--spring.datasource.password=" + db.adminPassword());
    }
}
