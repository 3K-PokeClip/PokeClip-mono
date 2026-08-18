package com.pokeclip.chat.collector;

import com.pokeclip.chat.collector.archive.ChatArchive;
import com.pokeclip.chat.collector.broadcast.EndedStreamStore;
import com.pokeclip.chat.collector.broadcast.EndedStreamSweeper;
import com.pokeclip.chat.collector.persist.ChatBuffer;
import com.pokeclip.chat.collector.persist.ChatPersister;
import com.pokeclip.chat.collector.reconnect.ReconnectPolicy;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestClient;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

// @ConfigurationPropertiesScan이 없으면 ChzzkProperties가 빈으로 안 올라
// CollectorRunner 주입이 실패한다(auth도 같은 것이 붙어 있다).
@ConfigurationPropertiesScan
@SpringBootApplication
// 끝난 방송 메모 치우기(EndedStreamSweeper). 없으면 @Scheduled가 애노테이션만 남고 아무것도 안 돈다.
@EnableScheduling
public class CollectorApplication {

    private static final Logger log = LoggerFactory.getLogger(CollectorApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(CollectorApplication.class, args);
    }

    /**
     * DB가 죽은 채 부팅해도 수집은 시작돼야 한다. 기본 전략은 migrate() 실패가
     * 컨텍스트를 통째로 죽인다 — HikariCP도 여기서야 첫 커넥션을 시도하므로
     * 접속 실패까지 같은 예외로 이 경로에 온다. 적재 경로는 DB 없이 버티게
     * 만들어 놓고(버퍼 보존·주기 재시도) 부팅만 그 장애에 무너지면 앞뒤가 안
     * 맞아서, <b>연결 장애만</b> 잡아 데몬 스레드의 백오프 재시도로 넘긴다. 그 사이
     * INSERT 실패는 ChatPersister의 실패-복원 경로가 버틴다.
     *
     * <p><b>허용 목록은 「재시도할 것」이다. 그 밖은 전부 fail-fast다.</b> 반대로
     * 「죽일 것」을 열거하면(검증·SQL 실행 실패) 접속은 됐는데 권한이 없거나 이력
     * 표를 못 만드는 영구 오류가 목록 밖이라 무한 재시도로 새고, "부팅은 성공했는데
     * 표는 영영 안 생기는" 조용한 실패가 된다(PR #53 P1). 모르면 안전한 쪽 —
     * 여기서 안전은 죽어서 드러나는 것이다. 부팅 중이면 재던져 부팅을 죽이고,
     * 재시도 중에 드러나면 컨텍스트를 닫아 재시작(컨테이너 restart 정책)이 부팅
     * fail-fast 경로를 타게 한다.
     */
    @Bean
    FlywayMigrationStrategy retryingMigrationStrategy() {
        return new RetryingMigrationStrategy();
    }

    /**
     * 러너는 {@code @Component}가 아니라 여기서 만든다 — <b>exit을 명시적으로 주기
     * 위해서다.</b> 재시도로 안 풀리는 사유(REVOKED·401·403)로 수집이 영영 끝나면
     * 판정 뒤 프로세스를 exit 1로 내린다(위 마이그레이션 영구 실패와 같은 방식). 검사가
     * 러너를 직접 만들 때는 exit 없는 패키지 생성자를 쓰므로 테스트 JVM은 안전하다.
     */
    @Bean
    CollectorRunner collectorRunner(ChzzkProperties properties, CollectionStatus status,
                                    RestClient.Builder restClientBuilder,
                                    ChatBuffer buffer, ChatPersister persister,
                                    ChatArchive archive) {
        return new CollectorRunner(properties, status, restClientBuilder, buffer, persister, archive,
                () -> System.exit(1));
    }

    /**
     * 스위퍼도 {@code @Component}가 아니라 여기서 만든다 — 보관 기간({@code Duration})과 시계
     * ({@code Supplier<Instant>})는 스프링이 만들 수 있는 타입이 아니라, 생성자에 그대로 두면
     * 부팅이 죽는다({@code EndedStreamSweeper} 주석의 실측 메시지). 값은 {@code @Value}로 받는다.
     */
    @Bean
    EndedStreamSweeper endedStreamSweeper(
            EndedStreamStore store,
            @Value("${pokeclip.broadcast.ended-retention}") Duration retention) {
        return new EndedStreamSweeper(store, retention, Instant::now);
    }

    static final class RetryingMigrationStrategy
            implements FlywayMigrationStrategy, DisposableBean, ApplicationContextAware {

        /**
         * 재시도 대기이자 중단 신호다 — 컨텍스트가 닫히면 destroy()가 내리고,
         * 대기 중이던 스레드가 즉시 깨어나 멈춘다. 이게 없으면 테스트 JVM에서
         * 닫힌 컨텍스트의 죽은 datasource를 향해 영원히 재시도한다.
         */
        private final CountDownLatch stopped = new CountDownLatch(1);

        /** 재연결과 같은 모양 — 기본 5초 시작, 두 배씩, 상한 60초. */
        private final ReconnectPolicy policy;

        private volatile ApplicationContext context;

        /** 운영 기본 — 영구 실패면 exit 1로 내린다. */
        RetryingMigrationStrategy() {
            this(new ReconnectPolicy(Duration.ofSeconds(5), Duration.ofSeconds(60)),
                    () -> System.exit(1));
        }

        /** 테스트가 짧은 백오프와 가짜 exit을 준다 — 실제 exit은 테스트 JVM을 죽인다. */
        RetryingMigrationStrategy(ReconnectPolicy policy, Runnable exitAction) {
            this.policy = policy;
            this.exitAction = exitAction;
        }

        private final Runnable exitAction;

        @Override
        public void setApplicationContext(ApplicationContext applicationContext) {
            this.context = applicationContext;
        }

        @Override
        public void migrate(Flyway flyway) {
            migrateWithRetry(flyway::migrate);
        }

        /** Flyway를 Runnable로 받는 이유는 검사다 — 실물 Flyway 없이 갈래를 밟는다. */
        void migrateWithRetry(Runnable migration) {
            try {
                migration.run();
                return;
            } catch (RuntimeException e) {
                if (!isConnectivityFailure(e)) {
                    // 부팅 중의 영구 실패 — 재던져 부팅을 죽인다(fail-fast).
                    throw e;
                }
                // 예외를 통째로 넘기지 않는다 — 메시지에 접속 문자열이 실릴 수 있다.
                log.warn("chat.schema.migrate_failed causeType={}", e.getClass().getSimpleName());
            }
            Thread retry = new Thread(() -> retryLoop(migration), "chzzk-migrate-retry");
            retry.setDaemon(true);
            retry.start();
        }

        private void retryLoop(Runnable migration) {
            int attempt = 1;
            while (true) {
                try {
                    if (stopped.await(policy.delayFor(attempt++).toMillis(), TimeUnit.MILLISECONDS)) {
                        return;   // 컨텍스트가 닫혔다 — 재시도할 이유가 없다
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                try {
                    migration.run();
                    log.info("chat.schema.migrated");
                    return;
                } catch (RuntimeException e) {
                    if (!isConnectivityFailure(e)) {
                        // 재시도 도중 드러난 영구 실패 — 부팅은 이미 성공한 뒤라 던질
                        // 곳이 없다. 컨텍스트를 닫아 프로세스를 내리고, 재시작이
                        // 부팅 fail-fast 경로를 타게 한다.
                        log.error("chat.schema.migrate_permanent_failure causeType={}",
                                e.getClass().getSimpleName());
                        closeContext();
                        return;
                    }
                    log.warn("chat.schema.migrate_failed causeType={}", e.getClass().getSimpleName());
                }
            }
        }

        /**
         * 재시도할 것 — <b>기다리면 풀릴 수 있는 연결 장애만.</b> 원인 체인을 끝까지 따라
         * SQLException의 SQLState 클래스가 08(connection exception) · 57P(operator
         * intervention — 관리자 종료 57P01·크래시 57P02·기동 중 57P03) · 53(insufficient
         * resources — 접속 초과 53300 등)이거나, 원인에 접속 자체가 안 된 java.net 예외
         * (ConnectException·SocketTimeoutException·UnknownHostException)가 있을 때만 참이다.
         *
         * <p>실측(2026-08-15, Flyway 12.4.0 · postgresql 드라이버) — 죽은 포트는
         * {@code FlywaySqlUnableToConnectToDbException → PSQLException(08001) →
         * ConnectException}, 없는 호스트는 같은 겉옷에 {@code 08001 → UnknownHostException},
         * <b>비밀번호 오류도 같은 FlywaySqlUnableToConnectToDbException</b>인데 안은
         * {@code 28P01}이다 — Flyway 예외 타입만 보고 "접속 실패"로 재시도하면 잘못된
         * 비밀번호를 영원히 두드린다. 그래서 타입이 아니라 <b>SQLState로 가른다.</b>
         * 권한 없음은 {@code FlywaySqlScriptException → PSQLException(42501)}로 와서
         * 목록 밖이라 fail-fast다. 검증(체크섬)·SQL 실행 실패는 원인에 SQLException이
         * 없거나 08/57P/53 밖이라 역시 fail-fast다.
         *
         * <p>모르면 안전한 쪽 — 여기서 안전은 죽어서 드러나는 것이다.
         */
        static boolean isConnectivityFailure(Throwable e) {
            for (Throwable t = e; t != null; t = t.getCause()) {
                if (t instanceof SQLException sql) {
                    String state = sql.getSQLState();
                    if (state != null && (state.startsWith("08")
                            || state.startsWith("57P") || state.startsWith("53"))) {
                        return true;
                    }
                }
                if (t instanceof java.net.ConnectException
                        || t instanceof java.net.SocketTimeoutException
                        || t instanceof java.net.UnknownHostException) {
                    return true;
                }
            }
            return false;
        }

        private void closeContext() {
            if (context instanceof ConfigurableApplicationContext configurable) {
                // 정상 종료(exit 0)로 내리면 restart: on-failure가 재시작하지 않는다 —
                // 비정상 코드 1로 내려야 재시작이 부팅 fail-fast 경로를 탄다.
                SpringApplication.exit(configurable, () -> 1);
                exitAction.run();
            }
        }

        @Override
        public void destroy() {
            stopped.countDown();
        }
    }
}
