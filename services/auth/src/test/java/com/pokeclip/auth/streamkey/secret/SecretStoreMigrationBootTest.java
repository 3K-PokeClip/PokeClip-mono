package com.pokeclip.auth.streamkey.secret;

import com.pokeclip.auth.AuthApplication;
import com.pokeclip.auth.chzzk.ChzzkTokenRefreshScheduler;
import com.pokeclip.auth.retention.RetentionCleanupScheduler;
import com.pokeclip.auth.support.IntegrationTestSupport;
import com.pokeclip.auth.support.SecretsLocalStackFixture;
import com.pokeclip.auth.youtube.YoutubeRevocationCheckScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.SecurityFilterChain;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 이행 실행기의 <b>부팅 형상</b>(POK-272, 1번 설계 8-A 5) E9). 진짜 {@code main}처럼 {@code secret-migration} 프로필로
 * 앱을 새로 띄운다. 시험 컨텍스트 캐시와 별개라 실행기가 끝나며 컨텍스트를 닫아도 다른 시험에 안 번진다.
 *
 * <ul>
 *   <li>웹 보안 체인 0개 · 스케줄러 0개: 웹 없이 떠야 하고({@code HttpSecurity} 빈이 없다), 이행 중에 청소·갱신이
 *       돌면 안 된다</li>
 *   <li>종료 코드 0(정상 데이터) · 2(결함 행) · 2(실행기 안의 처리하지 못한 예외: 1번 10-05 정정 2)</li>
 * </ul>
 *
 * <p>종료는 {@link ProcessExit} 빈으로 받는다. 시험 JVM을 끝낼 수는 없어서다.
 */
class SecretStoreMigrationBootTest extends IntegrationTestSupport {

    private static final AtomicInteger EXIT = MigrationExitCapture.EXIT;
    private static final AtomicReference<ConfigurableApplicationContext> SEEN = MigrationExitCapture.SEEN;

    /** 웹 보안 체인 둘 + 스케줄러 셋의 빈 이름. */
    private static final String[] WEB_AND_SCHEDULER_BEANS = {"securityFilterChain", "internalFilterChain",
            "chzzkTokenRefreshScheduler", "youtubeRevocationCheckScheduler", "retentionCleanupScheduler"};

    private final JdbcTemplate jdbc;
    private final PostgresSecretStore pg;
    private final org.springframework.context.ApplicationContext webContext;

    SecretStoreMigrationBootTest(JdbcTemplate jdbc, PostgresSecretStore pg,
                                 org.springframework.context.ApplicationContext webContext) {
        this.jdbc = jdbc;
        this.pg = pg;
        this.webContext = webContext;
    }

    /**
     * 대조. 위 이름들이 틀리면 「없다」가 저절로 참이 된다. 웹 시험 컨텍스트에는 보안 체인 둘이 그 이름으로 있다.
     * 스케줄러는 시험 프로필이 꺼 두어 여기서도 없으므로 클래스 이름에서 빈 이름이 나오는지만 맞춰 본다.
     */
    @Test
    void 대조_웹_컨텍스트에는_그_이름의_보안_체인이_있다() {
        assertThat(webContext.getBeanNamesForType(SecurityFilterChain.class))
                .contains("securityFilterChain", "internalFilterChain");
        assertThat(WEB_AND_SCHEDULER_BEANS).contains(
                beanNameOf(ChzzkTokenRefreshScheduler.class), beanNameOf(YoutubeRevocationCheckScheduler.class),
                beanNameOf(RetentionCleanupScheduler.class));
    }

    private static String beanNameOf(Class<?> type) {
        String simple = type.getSimpleName();
        return Character.toLowerCase(simple.charAt(0)) + simple.substring(1);
    }

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM stream_keys");
        EXIT.set(-1);
    }

    /** 빈 표면 「0으로 끝난다」가 저절로 참이다. 옮길 키 하나를 심고 실제로 옮겨졌는지까지 본다. */
    @Test
    void 웹과_스케줄러_없이_떠서_정상이면_0으로_끝난다() {
        Long userId = newUserRow();
        String uuid = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO stream_keys (user_id, streamid_hash, passphrase_ref, created_at) VALUES (?, ?, ?, ?)",
                userId, UUID.randomUUID().toString().replace("-", ""), "streamkey:" + uuid, Timestamp.from(Instant.now()));
        pg.put("streamkey:" + uuid, "token:boot");
        try {
            runMigration(SecretsLocalStackFixture.endpoint(), "sync");

            assertThat(EXIT.get()).isZero();
            assertThat(SecretsLocalStackFixture.read(SecretsLocalStackFixture.PREFIX + uuid)).contains("token:boot");
        } finally {
            deleteUserRow(userId);
        }
        // 이름으로 본다. 같은 이름의 빈이 기본 프로필(웹)에서는 실제로 있다. 아래 대조 시험이 그것을 잰다
        assertThat(MigrationExitCapture.BEANS.get()).doesNotContain(WEB_AND_SCHEDULER_BEANS);
        assertThat(SEEN.get().isActive()).as("끝나며 컨텍스트를 닫아야 프로세스가 남지 않는다").isFalse();
    }

    private Long newUserRow() {
        return jdbc.queryForObject(
                "INSERT INTO users (google_sub, email, name, created_at, updated_at) VALUES (?, ?, ?, now(), now()) RETURNING id",
                Long.class, "boot-" + UUID.randomUUID(), "boot-" + UUID.randomUUID() + "@x.test", "부팅시험");
    }

    private void deleteUserRow(Long userId) {
        jdbc.update("DELETE FROM secrets WHERE ref IN (SELECT passphrase_ref FROM stream_keys WHERE user_id = ?)", userId);
        jdbc.update("DELETE FROM stream_keys WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM users WHERE id = ?", userId);
    }

    @Test
    void 결함_행이_있으면_2로_끝난다() {
        Long userId = newUserRow();
        // 값이 어디에도 없는 살아 있는 키 = 결함
        jdbc.update("INSERT INTO stream_keys (user_id, streamid_hash, passphrase_ref, created_at) VALUES (?, ?, ?, ?)",
                userId, UUID.randomUUID().toString().replace("-", ""), "streamkey:" + UUID.randomUUID(),
                Timestamp.from(Instant.now()));
        try {
            runMigration(SecretsLocalStackFixture.endpoint(), "sync");
            assertThat(EXIT.get()).isEqualTo(2);
        } finally {
            deleteUserRow(userId);
        }
    }

    /** Secrets Manager에 못 닿으면(원격 오류) 실행기 안에서 예외가 난다. 1(부팅 실패)이 아니라 2다. */
    @Test
    void 실행기_안의_예외는_1이_아니라_2다() {
        Long userId = newUserRow();
        String uuid = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO stream_keys (user_id, streamid_hash, passphrase_ref, created_at) VALUES (?, ?, ?, ?)",
                userId, UUID.randomUUID().toString().replace("-", ""), "streamkey:" + uuid, Timestamp.from(Instant.now()));
        try {
            runMigration("http://127.0.0.1:1", "sync");
            assertThat(EXIT.get()).isEqualTo(2);
        } finally {
            deleteUserRow(userId);
        }
    }

    /** 진짜 실행처럼 명령줄 인자로 준다. 기본 속성(.properties)은 우선순위가 가장 낮아 yml의 자리표시에 밀린다. */
    private static void runMigration(String smEndpoint, String command) {
        new SpringApplicationBuilder(AuthApplication.class, MigrationExitCapture.class)
                .profiles("test", "secret-migration")
                .run(command,
                        "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword(),
                        "--spring.datasource.hikari.maximum-pool-size=2",
                        "--pokeclip.stream-key-secret-store.type=postgres",
                        "--pokeclip.stream-key-secret-store.retire-from=postgres,aws",
                        "--pokeclip.stream-key-secret-store.ref-prefix=" + SecretsLocalStackFixture.PREFIX,
                        "--pokeclip.stream-key-secret-store.endpoint=" + smEndpoint,
                        "--pokeclip.stream-key-secret-store.region=" + SecretsLocalStackFixture.region());
    }

}
