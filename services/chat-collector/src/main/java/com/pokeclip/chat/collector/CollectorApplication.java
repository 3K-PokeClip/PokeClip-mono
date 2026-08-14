package com.pokeclip.chat.collector;

import com.pokeclip.chat.collector.reconnect.ReconnectPolicy;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.exception.FlywayValidateException;
// internal 패키지지만 12.4.0 실물에서 확인한 타입이다 — 마이그레이션 SQL 실행
// 실패를 api 쪽에서 대신 말해 주는 타입이 없다. Flyway를 올리는 날 다시 본다.
import org.flywaydb.core.internal.exception.FlywayMigrateException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

// @ConfigurationPropertiesScan이 없으면 ChzzkProperties가 빈으로 안 올라
// CollectorRunner 주입이 실패한다(auth도 같은 것이 붙어 있다).
@ConfigurationPropertiesScan
@SpringBootApplication
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
     * 맞아서, 접속 실패류를 잡아 데몬 스레드의 백오프 재시도로 넘긴다. 그 사이
     * INSERT 실패는 ChatPersister의 실패-복원 경로가 버틴다.
     *
     * <p><b>재시도로 절대 안 풀리는 실패는 fail-fast다</b> — 검증(체크섬) 실패와
     * 마이그레이션 SQL 실행 실패. 부팅 중이면 재던져 부팅을 죽이고, 재시도 중에
     * 드러나면 컨텍스트를 닫아 재시작(컨테이너 restart 정책)이 부팅 fail-fast
     * 경로를 타게 한다 — 백오프로 넘기면 "부팅은 성공했는데 표는 영영 안 맞는"
     * 조용한 실패가 된다.
     */
    @Bean
    FlywayMigrationStrategy retryingMigrationStrategy() {
        return new RetryingMigrationStrategy();
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
                if (isPermanent(e)) {
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
                    if (isPermanent(e)) {
                        // 재시도 도중 드러난 영구 실패 — 부팅은 이미 성공한 뒤라 던질
                        // 곳이 없다. 컨텍스트를 닫아 프로세스를 내리고, 재시작이
                        // 부팅 fail-fast 경로를 타게 한다.
                        log.error("chat.schema.validate_failed causeType={}",
                                e.getClass().getSimpleName());
                        closeContext();
                        return;
                    }
                    log.warn("chat.schema.migrate_failed causeType={}", e.getClass().getSimpleName());
                }
            }
        }

        /** 재시도로 절대 안 풀리는 실패 — 검증(체크섬)과 마이그레이션 SQL 실행 실패. */
        private static boolean isPermanent(RuntimeException e) {
            return e instanceof FlywayValidateException || e instanceof FlywayMigrateException;
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
