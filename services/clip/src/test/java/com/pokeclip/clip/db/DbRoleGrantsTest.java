package com.pokeclip.clip.db;

import com.pokeclip.clip.ClipApplication;
import com.pokeclip.clip.purge.StreamerPurgeStore;
import com.pokeclip.clip.support.IntegrationTestSupport;
import com.pokeclip.db.support.GrantedRoleDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * 운영 DB 계정 app_clip의 권한 목록만으로 이 서버가 도는지 실 PG 역할로 잰다(POK-407). 목록의 정본은
 * services/README.md 「서버별 DB 계정과 권한」이고, 아래 {@link #GRANTS}가 그 줄과 같아야 한다.
 *
 * <p>자기 표는 자기 스키마 {@code app_clip}에 만든다(POK-428). 스키마는 운영 10_roles.sql이 마스터 소유로 만들므로
 * 시험도 관리자가 만든다. media 표 둘은 public에 있고, 운영 30 · 시험 관리자 모두 public 경로라 GRANT 줄은 맨 이름이다.
 */
class DbRoleGrantsTest extends IntegrationTestSupport {

    static final String DB_USER = "app_clip";

    static final String SCHEMA = "app_clip";

    static final List<String> GRANTS = List.of(
            "GRANT USAGE, CREATE ON SCHEMA app_clip TO {role}",
            // 탈퇴 정리가 조각 장부(media 표)를 읽고 지운다. 조각 조회 · 사진 대상 · 타임라인 원점은 읽기만 한다.
            "GRANT SELECT, DELETE ON stream_segments TO {role}",
            // 탈퇴 정리가 회차 머리 파일(init) 키를 읽는다.
            "GRANT SELECT ON stream_sessions TO {role}");

    private static final Path README = Path.of("..", "README.md");

    @Test
    void 시험_목록이_README의_권한_줄과_같다() throws Exception {
        assertThat(GrantedRoleDatabase.readmeGrants(README, DB_USER)).containsExactlyInAnyOrderElementsOf(GRANTS);
        assertThat(Files.readString(README)).contains(
                "GRANT rds_iam TO " + DB_USER + ";",
                "CREATE SCHEMA " + SCHEMA + ";",
                "GRANT CONNECT ON DATABASE pokeclip TO " + DB_USER + ";");
    }

    /**
     * 목록의 권한만 가진 역할로 빈 DB에서 띄운다. 마이그레이션 · 스키마 검증이 끝까지 돌고(자기 표는 자기 소유),
     * media 표 둘을 건드리는 탈퇴 정리 경로가 돈다. 그 뒤 한 줄씩 거두면 그 줄이 지키던 경로가 권한 부족으로 거절된다.
     */
    @Test
    void 목록의_권한만으로_마이그레이션과_media_표_경로가_돌고_한_줄을_빼면_거절된다() throws Exception {
        try (GrantedRoleDatabase db = GrantedRoleDatabase.create(POSTGRES, "clip")) {
            seedMediaTables(db);
            db.createSchema(SCHEMA);
            db.grant(GRANTS);

            try (ConfigurableApplicationContext context = boot(db)) {
                assertThat(context.getBean(JdbcTemplate.class)
                        .queryForObject("SELECT pg_catalog.current_schemas(true)::text", String.class))
                        .as("세션 경로는 자기 스키마 하나다(+ 암묵 pg_catalog)").isEqualTo("{pg_catalog,app_clip}");
                StreamerPurgeStore store = context.getBean(StreamerPurgeStore.class);
                List<StreamerPurgeStore.SegmentRow> rows = store.segmentBatch(List.of("s1"), 10);
                assertThat(rows).hasSize(1);
                assertThat(store.initKeys(List.of("s1"))).containsExactly("s1/init.mp4");
                assertThat(store.deleteSegments(rows)).isEqualTo(1);

                db.revoke("GRANT SELECT ON stream_sessions TO {role}");
                assertThat(GrantedRoleDatabase.isInsufficientPrivilege(
                        catchThrowable(() -> store.initKeys(List.of("s1")))))
                        .as("회차 장부 읽기 권한을 빼면 거절돼야 한다").isTrue();

                db.revoke("GRANT SELECT, DELETE ON stream_segments TO {role}");
                assertThat(GrantedRoleDatabase.isInsufficientPrivilege(
                        catchThrowable(() -> store.segmentBatch(List.of("s1"), 10))))
                        .as("조각 장부 권한을 빼면 거절돼야 한다").isTrue();

                db.revoke(GRANTS.get(0));
                assertThat(GrantedRoleDatabase.isInsufficientPrivilege(
                        catchThrowable(() -> db.asRole("CREATE TABLE app_clip.pc_probe (id int)"))))
                        .as("자기 스키마 권한이 없으면 마이그레이션이 못 돈다").isTrue();
                assertThat(GrantedRoleDatabase.isInsufficientPrivilege(
                        catchThrowable(() -> db.asRole("CREATE TABLE public.pc_probe (id int)"))))
                        .as("public 은 표를 만들 자리가 아니다").isTrue();
            }
        }
    }

    /**
     * 운영에서는 media가 먼저 만들어 둔 표 둘이다. 칸은 이 시험이 부르는 경로가 읽는 것만 둔다. 전체 칸의 사본은
     * {@code IntegrationTestSupport.seedSegmentLedger}에 있다(정본은 media/internal/index/ddl.go).
     */
    private static void seedMediaTables(GrantedRoleDatabase db) {
        db.asAdmin("""
                CREATE TABLE stream_segments (
                    stream_id       text   NOT NULL,
                    seq             bigint NOT NULL,
                    s3_key          text   NOT NULL,
                    playback_s3_key text,
                    PRIMARY KEY (stream_id, seq)
                )""");
        db.asAdmin("CREATE TABLE stream_sessions (session_id text PRIMARY KEY, stream_id text NOT NULL, init_s3_key text)");
        db.asAdmin("INSERT INTO stream_segments VALUES ('s1', 1, 's1/1.ts', NULL)");
        db.asAdmin("INSERT INTO stream_sessions VALUES ('sess1', 's1', 's1/init.mp4')");
    }

    private static ConfigurableApplicationContext boot(GrantedRoleDatabase db) {
        return new SpringApplicationBuilder(ClipApplication.class)
                // 보안 필터 사슬이 HttpSecurity를 받아 웹 없이 못 뜬다. 아무 빈 포트로 띄운다.
                .web(WebApplicationType.SERVLET)
                .profiles("test")
                .run("--server.port=0",
                        "--spring.datasource.url=" + db.jdbcUrl(),
                        "--spring.datasource.username=" + db.role(),
                        "--spring.datasource.password=" + db.password());
    }
}
