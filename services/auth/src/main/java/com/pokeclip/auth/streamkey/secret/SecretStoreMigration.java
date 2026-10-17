package com.pokeclip.auth.streamkey.secret;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.ResourceExistsException;
import software.amazon.awssdk.services.secretsmanager.model.ResourceNotFoundException;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 스트림키 암호를 PG와 Secrets Manager 사이에서 맞추는 일회성 명령(POK-272, 1번 설계 8-A 5)). <b>이행·롤백·재전환을
 * 이 하나로 한다</b>: 빈 쪽을 채우는 양방향이라 postgres 기간에 발급된 키(PG에만 있음)도, aws 기간에 발급된 키
 * (Secrets Manager에만 있음)도 같은 명령으로 맞는다. 병행 종료 PR에서 함께 걷는다.
 *
 * <p>프로필 {@code secret-migration}에서만 뜬다. 그 프로필은 웹·스케줄러를 끈다({@code application-secret-migration.yml}).
 * <pre>
 * docker compose -f services/docker-compose.dev.yml run --rm \
 *   -e SPRING_PROFILES_ACTIVE=&lt;기존 프로필&gt;,secret-migration auth [sync|verify]; echo "exit=$?"
 * </pre>
 *
 * <ul>
 *   <li>{@code sync}(기본). <b>auth를 내린 상태에서</b> 돈다(동시 발급과 경합한다). 쓰기가 있다</li>
 *   <li>{@code verify}: 쓰기 0. 「Secrets Manager에서 읽히는 살아 있는 키 수 / 대상 수」를 센다. Media가 보게 될 것과
 *       같은 판정이라 auth를 띄운 뒤에 돈다. <b>인자를 꼭 붙인다</b>: 빼면 sync다</li>
 * </ul>
 *
 * <p>종료 코드는 셋이다. <b>0</b> 정상. <b>2</b> 결함·검증 지연·정리 실패·원격 오류·처리하지 못한 예외로 중단.
 * <b>1</b> 이 실행기에 닿기 전의 부팅 실패(JVM 기본). 실행기 안의 예외를 여기서 잡아 2로 바꾸는 이유는 그대로 두면
 * {@code SpringApplication.run}을 거쳐 {@code main} 밖으로 나가 1이 되어 「부팅 실패」와 섞이기 때문이다(1번 10-05
 * 정정 2). {@code SpringApplication.exit}가 컨텍스트를 닫은 뒤 끝내므로 스케줄러·풀이 남아 안 끝나는 갈래가 없다.
 *
 * <p>출력은 건수뿐이다. 값·이름은 찍지 않는다.
 */
@Component
@Profile("secret-migration")
class SecretStoreMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SecretStoreMigration.class);

    enum Command { SYNC, VERIFY }

    /** 쓴 뒤 다시 읽어 같음을 확인하는 간격(최종 일관성). 합 31초. */
    static final List<Duration> VERIFY_BACKOFF = List.of(Duration.ofSeconds(1), Duration.ofSeconds(2),
            Duration.ofSeconds(4), Duration.ofSeconds(8), Duration.ofSeconds(16));

    /** 폐기 행 정리 속도. DeleteSecret 할당량이 초당 50이라 그 5분의 1로 묶는다. */
    static final Duration RETIRE_INTERVAL = Duration.ofMillis(100);

    private static final Pattern UUID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private final JdbcTemplate jdbc;
    private final PostgresSecretStore pg;
    private final StreamKeySecretRetirer retirer;
    private final StreamKeySecretStoreProperties properties;
    private final ApplicationContext context;
    private final ObjectProvider<ProcessExit> exit;
    private final List<Duration> verifyBackoff;
    private final Duration retireInterval;

    @Autowired
    SecretStoreMigration(JdbcTemplate jdbc, PostgresSecretStore pg, StreamKeySecretRetirer retirer,
                         StreamKeySecretStoreProperties properties, ApplicationContext context,
                         ObjectProvider<ProcessExit> exit) {
        this(jdbc, pg, retirer, properties, context, exit, VERIFY_BACKOFF, RETIRE_INTERVAL);
    }

    /** 시험용: 확인 백오프 31초와 정리 간격을 줄인다. */
    SecretStoreMigration(JdbcTemplate jdbc, PostgresSecretStore pg, StreamKeySecretRetirer retirer,
                         StreamKeySecretStoreProperties properties, ApplicationContext context,
                         ObjectProvider<ProcessExit> exit, List<Duration> verifyBackoff, Duration retireInterval) {
        this.jdbc = jdbc;
        this.pg = pg;
        this.retirer = retirer;
        this.properties = properties;
        this.context = context;
        this.exit = exit;
        this.verifyBackoff = verifyBackoff;
        this.retireInterval = retireInterval;
    }

    @Override
    public void run(ApplicationArguments args) {
        int code;
        try {
            code = migrate(commandOf(args.getNonOptionArgs()));
        } catch (RuntimeException e) {
            // 원인은 타입 이름만. SDK 권한 거부 메시지에는 비밀 이름이 실려 온다
            log.error("auth.secret_migration.aborted causeType={}", e.getClass().getSimpleName());
            code = 2;
        }
        int exitCode = code;
        exit.getIfAvailable(() -> System::exit).exit(SpringApplication.exit(context, () -> exitCode));
    }

    static Command commandOf(List<String> args) {
        if (args.isEmpty() || args.getFirst().equals("sync")) {
            return Command.SYNC;
        }
        if (args.getFirst().equals("verify")) {
            return Command.VERIFY;
        }
        throw new IllegalArgumentException("알 수 없는 명령이다. sync 또는 verify");
    }

    /** 순수 실행부. 0 정상, 2 결함·검증 지연·정리 실패. 원격 오류는 예외로 올라간다({@link #run}이 2로 바꾼다). */
    int migrate(Command command) {
        try (SecretsManagerClient client = SecretsManagerClients.forMigration(properties)) {
            Remote remote = new Remote(client);
            return command == Command.SYNC ? sync(remote).exitCode() : verify(remote);
        }
    }

    Tally sync(Remote remote) {
        Tally t = new Tally();
        for (Row row : rows()) {
            if (row.revoked()) {
                retire(row, t);
            } else {
                syncAlive(row, remote, t);
            }
        }
        log.info("auth.secret_migration.sync targets={} smWritten={} pgWritten={} refUpdated={} alreadyInSync={} "
                        + "defects={} verifyDelayed={} retired={} retireFailed={}",
                t.targets, t.smWritten, t.pgWritten, t.refUpdated, t.alreadyInSync,
                t.defects, t.verifyDelayed, t.retired, t.retireFailed);
        return t;
    }

    private void syncAlive(Row row, Remote remote, Tally t) {
        t.targets++;
        String target = targetOf(row.ref());
        if (target == null) {
            t.defects++;
            return;
        }
        Optional<String> pgValue = pg.get(row.ref()).or(() -> pg.get(target));
        Optional<String> smValue = remote.get(target);
        if (pgValue.isEmpty() && smValue.isEmpty()) {
            t.defects++;   // resolve도 이미 500인 행이다
            return;
        }
        if (pgValue.isPresent() && smValue.isPresent() && !pgValue.get().equals(smValue.get())) {
            t.defects++;   // 어느 쪽도 덮어쓰지 않는다
            return;
        }
        String value = pgValue.orElseGet(smValue::get);
        boolean wrote = false;

        if (smValue.isEmpty()) {
            remote.put(target, value);
            t.smWritten++;
            wrote = true;
            if (!visibleAfterWrite(remote, target, value)) {
                t.verifyDelayed++;
            }
        }
        if (!pg.get(target).map(value::equals).orElse(false)) {
            pg.put(target, value);   // 롤백 대비 사본
            t.pgWritten++;
            wrote = true;
        }
        if (!row.ref().equals(target)) {
            // 행 단위로 커밋된다(바깥 트랜잭션 없음). 옛 PG 행 streamkey:<uuid>는 이제 아무 행도 안 가리키고, 병행 종료 PR이 지운다
            jdbc.update("UPDATE stream_keys SET passphrase_ref = ? WHERE id = ? AND passphrase_ref = ?",
                    target, row.id(), row.ref());
            t.refUpdated++;
            wrote = true;
        }
        if (!wrote) {
            t.alreadyInSync++;
        }
    }

    private boolean visibleAfterWrite(Remote remote, String target, String value) {
        for (Duration wait : verifyBackoff) {
            sleep(wait);
            if (remote.get(target).map(value::equals).orElse(false)) {
                return true;
            }
        }
        return false;
    }

    private void retire(Row row, Tally t) {
        try {
            retirer.retire(row.ref());
            t.retired++;
        } catch (RuntimeException e) {
            t.retireFailed++;
        }
        sleep(retireInterval);
    }

    /** 쓰기 0. Secrets Manager에서 읽히고 PG 사본과 어긋나지 않는 살아 있는 키 수를 센다. */
    int verify(Remote remote) {
        int targets = 0;
        int readable = 0;
        for (Row row : rows()) {
            if (row.revoked()) {
                continue;
            }
            targets++;
            String target = targetOf(row.ref());
            if (target == null) {
                continue;
            }
            Optional<String> smValue = remote.get(target);
            Optional<String> pgValue = pg.get(row.ref()).or(() -> pg.get(target));
            if (smValue.isPresent() && pgValue.map(smValue.get()::equals).orElse(true)) {
                readable++;
            }
        }
        log.info("auth.secret_migration.verify readable={} targets={}", readable, targets);
        return readable == targets ? 0 : 2;
    }

    /** 이름의 UUID 부분으로 새 이름을 짓는다. 옛 이름·새 이름 두 모양만 받는다. 다른 모양이면 null(결함). */
    String targetOf(String ref) {
        String uuid = null;
        if (ref.startsWith(StreamKeySecretRetirer.LEGACY_PREFIX)) {
            uuid = ref.substring(StreamKeySecretRetirer.LEGACY_PREFIX.length());
        } else if (ref.startsWith(properties.refPrefix())) {
            uuid = ref.substring(properties.refPrefix().length());
        }
        if (uuid == null) {
            return null;
        }
        Matcher m = UUID.matcher(uuid);
        return m.matches() ? properties.refPrefix() + uuid : null;
    }

    private List<Row> rows() {
        return jdbc.query("SELECT id, passphrase_ref, revoked_at IS NOT NULL AS revoked FROM stream_keys ORDER BY id",
                (rs, i) -> new Row(rs.getLong("id"), rs.getString("passphrase_ref"), rs.getBoolean("revoked")));
    }

    private static void sleep(Duration d) {
        if (d.isZero()) {
            return;
        }
        try {
            Thread.sleep(d);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("이행이 끊겼다");
        }
    }

    record Row(long id, String ref, boolean revoked) {
        Row {
            Objects.requireNonNull(ref);
        }
    }

    /** 건수. 값·이름은 담지 않는다. */
    static final class Tally {
        int targets;
        int smWritten;
        int pgWritten;
        int refUpdated;
        int alreadyInSync;
        int defects;
        int verifyDelayed;
        int retired;
        int retireFailed;

        int exitCode() {
            return defects == 0 && verifyDelayed == 0 && retireFailed == 0 ? 0 : 2;
        }
    }

    /**
     * 이행 전용 Secrets Manager 접근. 웹 경로 저장소({@link SecretsManagerSecretStore})를 쓰지 않는 이유는 그 3초 공유
     * 시한이 KMS 기본 키 첫 생성 지연(한 번, 클 수 있다)을 못 견뎌서다. <b>첫 호출 한 번만</b> 30초로 두고 그 뒤는
     * 클라이언트 기본(30초 안전망)이다.
     */
    static final class Remote {

        private static final Duration FIRST_CALL = Duration.ofSeconds(30);

        private final SecretsManagerClient client;
        private boolean first = true;

        Remote(SecretsManagerClient client) {
            this.client = client;
        }

        /** 없음은 빈손. 그 밖의 오류는 그대로 올린다. 실행 중단, 종료 코드 2. */
        Optional<String> get(String name) {
            try {
                return Optional.of(client.getSecretValue(b -> b.secretId(name)
                        .overrideConfiguration(firstCall())).secretString());
            } catch (ResourceNotFoundException notFound) {
                return Optional.empty();
            }
        }

        void put(String name, String value) {
            try {
                client.createSecret(b -> b.name(name).secretString(value).overrideConfiguration(firstCall()));
            } catch (ResourceExistsException exists) {
                client.putSecretValue(b -> b.secretId(name).secretString(value));
            }
        }

        private AwsRequestOverrideConfiguration firstCall() {
            AwsRequestOverrideConfiguration.Builder b = AwsRequestOverrideConfiguration.builder();
            if (first) {
                first = false;
                b.apiCallTimeout(FIRST_CALL).apiCallAttemptTimeout(FIRST_CALL);
            }
            return b.build();
        }
    }
}
