package com.pokeclip.auth.streamkey.secret;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import com.pokeclip.auth.chzzk.ChzzkLinkWriter;
import com.pokeclip.auth.chzzk.ChzzkTokenRefresher;
import com.pokeclip.auth.streamkey.StreamKeyMaterial;
import com.pokeclip.auth.streamkey.StreamKeyService;
import com.pokeclip.auth.support.SecretsLocalStackFixture;
import com.pokeclip.auth.token.TokenService;
import com.pokeclip.auth.user.User;
import com.pokeclip.auth.user.UserService;
import com.pokeclip.auth.withdrawal.WithdrawalCleanupExecutor;
import com.pokeclip.auth.withdrawal.WithdrawalService;
import com.pokeclip.auth.youtube.YoutubeLinkWriter;
import com.pokeclip.auth.youtube.YoutubeTokenRefresher;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static com.pokeclip.auth.support.SecretsLocalStackFixture.PREFIX;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 스트림키 저장소를 <b>aws</b>로 켠 컨텍스트(POK-272). 병행 기간 dev와 같게 {@code retire-from=postgres,aws}다.
 *
 * <p>여기서 재는 것은 셋이다. 바인딩(유튜브·치지직은 그대로 PG인가), 정리(재발급·탈퇴가 양쪽 사본을 다 지우는가),
 * 누설(Secrets Manager 경로의 로그에 값·이름이 없는가). 컨텍스트가 하나 더 뜬다.
 */
class StreamKeySecretsAwsTest extends AwsSecretStoreTestSupport {

    private final ApplicationContext context;
    private final StreamKeyService streamKeyService;
    private final PostgresSecretStore pg;
    private final WithdrawalCleanupExecutor cleanup;

    StreamKeySecretsAwsTest(MockMvc mockMvc, UserService userService, TokenService tokenService, JdbcTemplate jdbc,
                            ApplicationContext context, StreamKeyService streamKeyService, PostgresSecretStore pg,
                            WithdrawalCleanupExecutor cleanup) {
        super(mockMvc, userService, tokenService, jdbc);
        this.context = context;
        this.streamKeyService = streamKeyService;
        this.pg = pg;
        this.cleanup = cleanup;
    }

    /** 1번 설계 8-A 1) 「OAuth 무변경 보증」. 유튜브·치지직 넷은 PG, 스트림키 둘은 Secrets Manager, 탈퇴는 정리기. */
    @Test
    void 유튜브_치지직_토큰은_그대로_PG이고_스트림키만_Secrets_Manager다() {
        for (Class<?> oauth : new Class<?>[]{YoutubeLinkWriter.class, YoutubeTokenRefresher.class,
                ChzzkLinkWriter.class, ChzzkTokenRefresher.class}) {
            assertThat(storeOf(oauth)).as(oauth.getSimpleName()).isSameAs(pg);
        }
        assertThat(storeOf(StreamKeyService.class)).isInstanceOf(SecretsManagerSecretStore.class);
        assertThat(storeOf(classNamed("com.pokeclip.auth.streamkey.StreamKeyCreator")))
                .isInstanceOf(SecretsManagerSecretStore.class);
        assertThat(ReflectionTestUtils.getField(target(WithdrawalService.class), "secretRetirer"))
                .isInstanceOf(StreamKeySecretRetirer.class);
    }

    @Test
    void 새_키의_암호는_접두_이름으로_Secrets_Manager에만_있다() {
        User user = newUser();
        StreamKeyMaterial material = streamKeyService.ensureKey(user.getId());
        String ref = aliveRef(user);

        assertThat(ref).startsWith(PREFIX);
        assertThat(SecretsLocalStackFixture.read(ref)).isPresent();
        assertThat(pg.get(ref)).as("aws 상태 발급은 PG에 사본을 안 만든다").isEmpty();
        assertThat(streamKeyService.resolve(material.streamId().toSrtFormat()).valid()).isTrue();
    }

    /** 재발급의 커밋 뒤 정리가 Secrets Manager에서 실제로 지운다({@code 이전_secret이_지워진다}의 aws판). */
    @Test
    void 재발급하면_옛_암호가_Secrets_Manager에서_지워진다() throws Exception {
        User user = newUser();
        streamKeyService.ensureKey(user.getId());
        String oldRef = aliveRef(user);

        mockMvc.perform(post("/api/stream-keys/rotate").header("Authorization", bearer(user)))
                .andExpect(status().isOk());

        assertThat(SecretsLocalStackFixture.read(oldRef)).isEmpty();
        assertThat(SecretsLocalStackFixture.read(aliveRef(user))).isPresent();
    }

    /**
     * 이행 전 이름({@code streamkey:<uuid>}) 행을 재발급하면 <b>세 이름 모두</b> 양쪽에서 지운다. 병행 기간에 PG의 옛
     * 이름 사본과 Secrets Manager의 새 이름 사본이 같이 있는 키가 그 모양이다.
     */
    @Test
    void 옛_이름_행을_재발급하면_양쪽_사본이_전부_지워진다() throws Exception {
        User user = newUser();
        String uuid = UUID.randomUUID().toString();
        String legacy = "streamkey:" + uuid;
        jdbc.update("INSERT INTO stream_keys (user_id, streamid_hash, passphrase_ref, created_at) VALUES (?, ?, ?, ?)",
                user.getId(), UUID.randomUUID().toString().replace("-", ""), legacy,
                java.sql.Timestamp.from(Instant.now()));
        pg.put(legacy, "token:old");
        pg.put(PREFIX + uuid, "token:old");
        SecretsLocalStackFixture.write(PREFIX + uuid, "token:old");

        mockMvc.perform(post("/api/stream-keys/rotate").header("Authorization", bearer(user)))
                .andExpect(status().isOk());

        assertThat(pg.get(legacy)).isEmpty();
        assertThat(pg.get(PREFIX + uuid)).isEmpty();
        assertThat(SecretsLocalStackFixture.read(PREFIX + uuid)).isEmpty();
    }

    /** aws 상태 탈퇴 → PG 병행 사본까지 0. 정리기가 {@code retire-from}의 두 저장소를 다 돈다. */
    @Test
    void aws_상태_탈퇴는_양쪽_사본을_지운다() throws Exception {
        User user = newUser();
        streamKeyService.ensureKey(user.getId());
        String ref = aliveRef(user);
        pg.put(ref, "token:copy");

        mockMvc.perform(delete("/api/auth/me").header("Authorization", bearer(user)))
                .andExpect(status().isNoContent());
        assertThat(cleanup.awaitIdle(Duration.ofSeconds(20))).isTrue();

        assertThat(SecretsLocalStackFixture.read(ref)).isEmpty();
        assertThat(pg.get(ref)).isEmpty();
    }

    /**
     * 루트를 TRACE로 내려도 Secrets Manager 경로가 값·이름을 안 찍는다. SDK·Apache5 로거 셋은 사진 창고 때 이미
     * INFO로 박아 뒀다(같은 SDK·같은 HTTP 클라이언트). 그것이 이 경로에도 걸리는지 실제 호출로 잰다.
     *
     * <p>이름 검사는 이 변경이 만든 경로(우리 코드·AWS SDK·Apache HTTP)의 로거로 좁힌다. TRACE의 DB 층(Hibernate·
     * PostgreSQL 드라이버)은 SQL 매개변수로 {@code passphrase_ref}를 찍는다. auth/CLAUDE.md 「org.hibernate는
     * 안 막았다」, PG 저장소 때부터 같다. 값(암호·토큰)은 모든 로거에서 잰다.
     */
    @Test
    void 루트를_TRACE로_내려도_암호와_이름이_로그에_없다() {
        LoggerContext logback = (LoggerContext) LoggerFactory.getILoggerFactory();
        ch.qos.logback.classic.Logger root = logback.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        Level before = root.getLevel();
        User user = newUser();
        try (LogCaptor logs = new LogCaptor()) {
            root.setLevel(Level.TRACE);
            StreamKeyMaterial material = streamKeyService.ensureKey(user.getId());
            String ref = aliveRef(user);
            streamKeyService.resolve(material.streamId().toSrtFormat());
            root.setLevel(before);

            assertThat(logs.messages()).isNotEmpty();
            assertThat(logs.messages()).noneMatch(m -> m.contains(material.passphrase()));
            assertThat(logs.messages()).noneMatch(m -> m.contains(material.streamToken()));
            assertThat(logs.events())
                    .filteredOn(e -> e.getLoggerName().startsWith("com.pokeclip")
                            || e.getLoggerName().startsWith("software.amazon")
                            || e.getLoggerName().startsWith("org.apache.hc"))
                    .noneMatch(e -> e.getFormattedMessage().contains(ref.substring(PREFIX.length())));
        } finally {
            root.setLevel(before);
        }
    }

    private String aliveRef(User user) {
        return streamKeyService.findAlive(user.getId()).orElseThrow().getPassphraseRef();
    }

    private Object storeOf(Class<?> type) {
        return ReflectionTestUtils.getField(target(type), "secretStore");
    }

    private Object target(Class<?> type) {
        return AopTestUtils.getUltimateTargetObject(context.getBean(type));
    }

    private static Class<?> classNamed(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }
}
