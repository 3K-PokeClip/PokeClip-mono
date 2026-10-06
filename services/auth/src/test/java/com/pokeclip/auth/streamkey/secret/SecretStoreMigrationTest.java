package com.pokeclip.auth.streamkey.secret;

import com.pokeclip.auth.streamkey.secret.SecretStoreMigration.Command;
import com.pokeclip.auth.streamkey.secret.SecretStoreMigration.Tally;
import com.pokeclip.auth.support.SecretsLocalStackFixture;
import com.pokeclip.auth.token.TokenService;
import com.pokeclip.auth.user.User;
import com.pokeclip.auth.user.UserService;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.pokeclip.auth.support.SecretsLocalStackFixture.PREFIX;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 이행 실행기(POK-272, 1번 설계 8-A 5)·7)). PG + LocalStack에 대고 {@code migrate}·{@code sync}·{@code verify}를
 * 직접 부른다. 부팅 형상(웹·스케줄러 0, 종료 코드)은 {@link SecretStoreMigrationBootTest}가 잰다.
 *
 * <p>🔴 실행기는 {@code stream_keys} <b>전체</b>를 훑는다. 공유 시험 DB에 다른 시험이 남긴 행이 섞이면 건수가 흔들려
 * 매 시험 전에 표를 비운다. 그 표를 가리키는 외래키는 없다.
 */
class SecretStoreMigrationTest extends AwsSecretStoreTestSupport {

    private final PostgresSecretStore pg;
    private final StreamKeySecretRetirer retirer;
    private final StreamKeySecretStoreProperties properties;
    private final ApplicationContext context;
    private SecretStoreMigration migration;

    SecretStoreMigrationTest(MockMvc mockMvc, UserService userService, TokenService tokenService, JdbcTemplate jdbc,
                             PostgresSecretStore pg, StreamKeySecretRetirer retirer,
                             StreamKeySecretStoreProperties properties, ApplicationContext context) {
        super(mockMvc, userService, tokenService, jdbc);
        this.pg = pg;
        this.retirer = retirer;
        this.properties = properties;
        this.context = context;
    }

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM stream_keys");
        // 확인 백오프 31초·정리 간격을 줄인다. LocalStack은 쓰자마자 보인다
        migration = new SecretStoreMigration(jdbc, pg, retirer, properties, context, noExit(),
                List.of(Duration.ofMillis(10), Duration.ofMillis(10)), Duration.ZERO);
    }

    @Test
    void 이행_전_키를_Secrets_Manager로_옮기고_이름을_바꾼다() {
        Key key = legacyKey("token:a");

        assertThat(migration.migrate(Command.SYNC)).isZero();

        assertThat(SecretsLocalStackFixture.read(key.target())).contains("token:a");
        assertThat(pg.get(key.target())).as("롤백 대비 PG 사본").contains("token:a");
        assertThat(refOf(key)).isEqualTo(key.target());
        assertThat(pg.get(key.legacy())).as("옛 이름 PG 행은 병행 종료 PR이 지운다").contains("token:a");
    }

    @Test
    void 다시_돌려도_같고_아무것도_안_쓴다() {
        legacyKey("token:a");
        migration.migrate(Command.SYNC);

        Tally again = sync();

        assertThat(again.exitCode()).isZero();
        assertThat(again.alreadyInSync).isEqualTo(1);
        assertThat(again.smWritten + again.pgWritten + again.refUpdated).isZero();
    }

    /** aws 기간에 발급된 키는 Secrets Manager에만 있다. 롤백 전에 sync가 PG를 채운다. */
    @Test
    void Secrets_Manager에만_있는_키는_PG를_채운다() {
        Key key = newNameKey();
        SecretsLocalStackFixture.write(key.target(), "token:b");

        Tally t = sync();

        assertThat(t.exitCode()).isZero();
        assertThat(t.pgWritten).isEqualTo(1);
        assertThat(t.smWritten).isZero();
        assertThat(pg.get(key.target())).contains("token:b");
    }

    /** 롤백 기간(postgres)에 새 이름으로 발급된 키는 PG에만 있다. 재전환 전에 sync가 Secrets Manager를 채운다. */
    @Test
    void 접두_이름인데_Secrets_Manager에_없는_키는_채운다() {
        Key key = newNameKey();
        pg.put(key.target(), "token:c");

        Tally t = sync();

        assertThat(t.exitCode()).isZero();
        assertThat(t.smWritten).isEqualTo(1);
        assertThat(SecretsLocalStackFixture.read(key.target())).contains("token:c");
    }

    @Test
    void 값이_다르면_결함이고_어느_쪽도_덮어쓰지_않는다() {
        Key key = legacyKey("token:pg");
        SecretsLocalStackFixture.write(key.target(), "token:sm");

        Tally t = sync();

        assertThat(t.exitCode()).isEqualTo(2);
        assertThat(t.defects).isEqualTo(1);
        assertThat(pg.get(key.legacy())).contains("token:pg");
        assertThat(SecretsLocalStackFixture.read(key.target())).contains("token:sm");
        assertThat(refOf(key)).isEqualTo(key.legacy());
    }

    @Test
    void 둘_다_없거나_이름_모양이_틀리면_결함이다() {
        insertKey(PREFIX + UUID.randomUUID(), false);
        insertKey("이상한-이름", false);

        Tally t = sync();

        assertThat(t.defects).isEqualTo(2);
        assertThat(t.exitCode()).isEqualTo(2);
    }

    @Test
    void 폐기_행은_양쪽_사본을_지운다() {
        String uuid = UUID.randomUUID().toString();
        insertKey("streamkey:" + uuid, true);
        pg.put("streamkey:" + uuid, "old");
        SecretsLocalStackFixture.write(PREFIX + uuid, "old");

        Tally t = sync();

        assertThat(t.retired).isEqualTo(1);
        assertThat(t.targets).isZero();
        assertThat(pg.get("streamkey:" + uuid)).isEmpty();
        assertThat(SecretsLocalStackFixture.read(PREFIX + uuid)).isEmpty();
    }

    @Test
    void 출력에_값과_이름이_없다() {
        Key key = legacyKey("token:secret-value");

        try (LogCaptor logs = new LogCaptor()) {
            migration.migrate(Command.SYNC);
            migration.migrate(Command.VERIFY);

            assertThat(logs.messages()).anyMatch(m -> m.startsWith("auth.secret_migration.sync targets=1"));
            assertThat(logs.messages()).contains("auth.secret_migration.verify readable=1 targets=1");
            assertThat(logs.events())
                    .filteredOn(e -> e.getLoggerName().startsWith("com.pokeclip"))
                    .noneMatch(e -> e.getFormattedMessage().contains("secret-value")
                            || e.getFormattedMessage().contains(key.uuid()));
        }
    }

    /** verify는 쓰기가 0이다. 옮기기 전 키를 세면 못 읽히고(2), 아무것도 안 바뀐다. */
    @Test
    void verify는_아무것도_쓰지_않는다() {
        Key key = legacyKey("token:v");

        assertThat(migration.migrate(Command.VERIFY)).isEqualTo(2);

        assertThat(SecretsLocalStackFixture.read(key.target())).isEmpty();
        assertThat(pg.get(key.target())).isEmpty();
        assertThat(refOf(key)).isEqualTo(key.legacy());
    }

    @Test
    void verify는_옮긴_뒤_전부_읽히면_0이다() {
        legacyKey("token:v");
        legacyKey("token:w");
        migration.migrate(Command.SYNC);

        assertThat(migration.migrate(Command.VERIFY)).isZero();
    }

    private Tally sync() {
        StreamKeySecretStoreProperties p = properties;
        try (SecretsManagerClient client = SecretsManagerClients.forMigration(p)) {
            return migration.sync(new SecretStoreMigration.Remote(client));
        }
    }

    /** 이행 전 모양의 살아 있는 키: 행은 {@code streamkey:<uuid>}, 값은 PG에만 있다. */
    private Key legacyKey(String value) {
        String uuid = UUID.randomUUID().toString();
        Key key = new Key(uuid, insertKey("streamkey:" + uuid, false));
        pg.put(key.legacy(), value);
        return key;
    }

    private Key newNameKey() {
        String uuid = UUID.randomUUID().toString();
        return new Key(uuid, insertKey(PREFIX + uuid, false));
    }

    private long insertKey(String ref, boolean revoked) {
        User user = newUser();
        jdbc.update("INSERT INTO stream_keys (user_id, streamid_hash, passphrase_ref, revoked_at, created_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                user.getId(), UUID.randomUUID().toString().replace("-", ""), ref,
                revoked ? Timestamp.from(Instant.now()) : null, Timestamp.from(Instant.now()));
        return jdbc.queryForObject("SELECT id FROM stream_keys WHERE passphrase_ref = ?", Long.class, ref);
    }

    private String refOf(Key key) {
        return jdbc.queryForObject("SELECT passphrase_ref FROM stream_keys WHERE id = ?", String.class, key.id());
    }

    private static ObjectProvider<ProcessExit> noExit() {
        return new ObjectProvider<>() {
            @Override
            public ProcessExit getObject() {
                return code -> { };
            }
        };
    }

    private record Key(String uuid, long id) {
        String legacy() {
            return "streamkey:" + uuid;
        }

        String target() {
            return PREFIX + uuid;
        }
    }
}
