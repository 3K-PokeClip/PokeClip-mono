package com.pokeclip.chat.detector.db;

import com.pokeclip.chat.detector.DetectorApplication;
import com.pokeclip.chat.detector.metrics.ChatWindowReader;
import com.pokeclip.chat.detector.support.IntegrationTestSupport;
import com.pokeclip.db.support.GrantedRoleDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * 운영 DB 계정 app_chat_detector의 권한 목록만으로 이 서버가 도는지 실 PG 역할로 잰다(POK-407). 목록의 정본은
 * services/README.md 「서버별 DB 계정과 권한」이고, 아래 {@link #GRANTS}가 그 줄과 같아야 한다.
 *
 * <p>chat_messages 읽기는 운영에서 수집 서버 V316이 준다(표 주인이 준다). 그 마이그레이션은 수집 서버
 * {@code DbRoleGrantsTest}가 잰다. 여기서는 그 한 줄이 이 서버에 충분하고 필요한지만 본다.
 */
class DbRoleGrantsTest extends IntegrationTestSupport {

    static final String DB_USER = "app_chat_detector";

    static final List<String> GRANTS = List.of(
            "GRANT CREATE ON SCHEMA public TO {role}",
            // ChatWindowReader: 채팅 원문(수집 서버 표)을 읽는다.
            "GRANT SELECT ON chat_messages TO {role}");

    private static final Path README = Path.of("..", "README.md");

    @Test
    void 시험_목록이_README의_권한_줄과_같다() throws Exception {
        assertThat(GrantedRoleDatabase.readmeGrants(README, DB_USER)).containsExactlyInAnyOrderElementsOf(GRANTS);
        assertThat(Files.readString(README)).contains("GRANT rds_iam TO " + DB_USER + ";");
    }

    @Test
    void 목록의_권한만으로_마이그레이션과_채팅_읽기가_돌고_한_줄을_빼면_거절된다() throws Exception {
        try (GrantedRoleDatabase db = GrantedRoleDatabase.create(POSTGRES, "chat-detector")) {
            // 운영에서는 수집 서버가 V301로 만든 표다. 칸은 ChatWindowReaderTest의 시드와 같다.
            db.asAdmin("""
                    CREATE TABLE chat_messages (
                        id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                        channel_id        TEXT        NOT NULL,
                        sender_channel_id TEXT        NOT NULL,
                        content           TEXT        NOT NULL,
                        message_time      TIMESTAMPTZ NOT NULL,
                        received_at       TIMESTAMPTZ NOT NULL,
                        content_sha256    VARCHAR(64) NOT NULL,
                        stream_id         VARCHAR(128)
                    )""");
            db.asAdmin("INSERT INTO chat_messages (channel_id, sender_channel_id, content, message_time, received_at,"
                    + " content_sha256, stream_id) VALUES ('c1', 'u1', 'hi', now(), now(), repeat('a', 64), 's1')");
            db.grant(GRANTS);

            try (ConfigurableApplicationContext context = new SpringApplicationBuilder(DetectorApplication.class)
                    .web(WebApplicationType.NONE)
                    .profiles("test")
                    .run("--spring.datasource.url=" + db.jdbcUrl(),
                            "--spring.datasource.username=" + db.role(),
                            "--spring.datasource.password=" + db.password())) {
                ChatWindowReader reader = context.getBean(ChatWindowReader.class);
                Instant hourAgo = Instant.now().minusSeconds(3600);
                assertThat(reader.activeStreams(hourAgo)).containsExactly("s1");

                db.revoke("GRANT SELECT ON chat_messages TO {role}");
                assertThat(GrantedRoleDatabase.isInsufficientPrivilege(
                        catchThrowable(() -> reader.activeStreams(hourAgo))))
                        .as("채팅 읽기 권한을 빼면 거절돼야 한다").isTrue();

                db.revoke("GRANT CREATE ON SCHEMA public TO {role}");
                assertThat(GrantedRoleDatabase.isInsufficientPrivilege(
                        catchThrowable(() -> db.asRole("CREATE TABLE pc_probe (id int)"))))
                        .as("표를 만들 자리가 없으면 마이그레이션이 못 돈다").isTrue();
            }
        }
    }
}
