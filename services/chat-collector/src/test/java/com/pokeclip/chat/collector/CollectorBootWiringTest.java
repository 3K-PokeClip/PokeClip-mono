package com.pokeclip.chat.collector;

import com.pokeclip.chat.collector.support.IntegrationTestSupport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 부팅 배선을 실제로 밟는다. <b>이 파일이 없으면 러너가 한 번도 안 도는 상태가
 * 전 테스트 초록으로 통과한다.</b>
 *
 * <p>원인은 DISABLED가 두 가지를 뜻한다는 것이다 — "러너가 돌아서 껐다"와
 * "러너가 한 번도 안 돌아 초기값 그대로다"가 같은 값이다. 그래서 @Component
 * 누락·run()이 start()를 안 부름·러너와 health가 다른 인스턴스를 봄, 셋 중
 * 무엇이 깨져도 기존 단언은 전부 참이었다.
 *
 * <p>운영에서의 모습이 이 카드가 막으려는 그 실패다. enabled=true로 띄웠는데
 * 배선이 깨져 있으면 수집은 안 도는데 health는 UP + status:disabled를 응답한다.
 * 배포도 헬스체크도 통과한다.
 *
 * <p>죽은 포트로 향하게 해서 가짜 서버 없이 부팅 경로 전체를 밟는다. 연결 거부는
 * SESSION_AUTH_FAILED로 감싸이고 러너가 잡으므로 부팅 자체는 살아남는다 —
 * <b>그 "실패해도 부팅은 산다"까지가 이 테스트가 확인하는 것이다.</b>
 *
 * <p><b>재시도 간격을 크게 준다.</b> 죽은 포트라 재연결은 영원히 실패하는데,
 * 스프링은 이 컨텍스트를 JVM이 끝날 때까지 캐시한다 — 짧은 간격이면 뒤따르는
 * 모든 테스트 클래스가 도는 내내 이 러너가 계속 두드린다.
 *
 * <p><b>상한도 같이 올린다.</b> {@code application-test.yml}의 상한이 1초라,
 * 첫 간격만 30초로 올리면 {@code delayFor}가 그것을 상한으로 잘라 <b>실제로는
 * 1초마다 두드린다</b> — 위 문단이 막으려던 것이 그대로 일어난다.
 */
@SpringBootTest(properties = {
        "pokeclip.chzzk.enabled=true",
        "pokeclip.chzzk.base-url=http://localhost:1",
        "pokeclip.chzzk.reconnect-first-delay=30s",
        "pokeclip.chzzk.reconnect-max-delay=30s"
})
@ActiveProfiles("test")
class CollectorBootWiringTest extends IntegrationTestSupport {

    @Autowired CollectionStatus status;   // 러너가 쓰는 그 싱글턴이어야 한다
    @Autowired CollectorHealth health;
    @Autowired CollectorRunner runner;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    /**
     * <b>반드시 멈춘다.</b> 컨텍스트는 캐시돼 JVM 끝까지 사는데 그 안의 러너는
     * 닿을 수 없는 주소로 영원히 재시도한다. 안 멈추면 남의 테스트가 도는 동안
     * 로그가 그 재시도로 덮인다.
     */
    @AfterEach
    void tearDown() {
        runner.stop();
    }

    /**
     * PR #52 P1 ⑦. Flyway는 부팅 중에 eager로 접속한다 — DB가 죽어 있으면
     * 컨텍스트가 통째로 죽어 <b>수집까지 같이 못 시작한다.</b> 적재 경로는 DB 없이
     * 버티게 만들어 놓고(버퍼 보존·재시도) 정작 부팅이 그 장애에 무너지면 앞뒤가
     * 안 맞는다. FlywayMigrationStrategy가 실패를 잡아 데몬 재시도로 넘기면
     * 부팅이 살고, 수집·health·적재 배선이 전부 기동한다.
     *
     * <p>별도 컨텍스트로 부팅하는 이유는 {@code CollectorShutdownTest}와 같다 —
     * 이 클래스의 캐시된 컨텍스트는 살아 있는 컨테이너 datasource를 쓰므로
     * 죽은 datasource는 새 부팅으로만 만들 수 있다.
     */
    @Test
    void DB가_죽어_있어도_부팅이_살고_수집_배선이_기동한다() {
        try (org.springframework.context.ConfigurableApplicationContext context =
                     new org.springframework.boot.builder.SpringApplicationBuilder(CollectorApplication.class)
                             .web(org.springframework.boot.WebApplicationType.NONE)
                             .profiles("test")
                             .run("--pokeclip.chzzk.enabled=true",
                                     "--pokeclip.chzzk.base-url=http://localhost:1",
                                     "--pokeclip.chzzk.reconnect-first-delay=30s",
                                     "--pokeclip.chzzk.reconnect-max-delay=30s",
                                     "--spring.datasource.url=jdbc:postgresql://localhost:1/pokeclip",
                                     // Hikari 기본 30초를 최소값으로 — 죽은 포트는 즉시
                                     // 거부되지만, 환경에 따라 타임아웃까지 매달리면
                                     // 이 테스트가 30초 경주가 된다.
                                     "--spring.datasource.hikari.connection-timeout=250")) {
            // health가 응답한다 — DOWN이어도 (죽은 chzzk 포트라 수집 실패 상태가 정상).
            assertThat(context.getBean(CollectorHealth.class).health()).isNotNull();
            // 수집 배선이 실제로 돌았다 — RECONNECTING은 러너가 돌지 않고서는 안 나온다.
            assertThat(context.getBean(CollectionStatus.class).state())
                    .as("DB가 죽었다고 수집까지 안 시작하면 채팅이 통째로 사라진다")
                    .isEqualTo(CollectionStatus.State.RECONNECTING);
            // 적재 배선도 올라왔다 — 스케줄러는 틱마다 실패하며 DB 복구를 기다린다.
            assertThat(context.getBean(com.pokeclip.chat.collector.persist.ChatPersister.class)).isNotNull();
            // 마이그레이션이 포기가 아니라 백오프 재시도로 넘어갔다.
            assertThat(Thread.getAllStackTraces().keySet())
                    .as("재시도 스레드가 없으면 DB가 복구돼도 표가 영영 안 생긴다")
                    .anyMatch(t -> "chzzk-migrate-retry".equals(t.getName()));
        }

        // 컨텍스트를 닫으면 재시도도 멈춰야 한다 — 안 멈추면 죽은 커넥션을 향한
        // 재시도가 테스트 JVM이 끝날 때까지 남의 실행 내내 두드린다 (code-review C 보조).
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(3).toNanos();
        while (migrateRetryThreadAlive() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        assertThat(migrateRetryThreadAlive())
                .as("컨텍스트가 닫혔는데 재시도 스레드가 남으면 중단 신호가 없는 것이다")
                .isFalse();
    }

    private static boolean migrateRetryThreadAlive() {
        return Thread.getAllStackTraces().keySet().stream()
                .anyMatch(t -> "chzzk-migrate-retry".equals(t.getName()) && t.isAlive());
    }

    /**
     * code-review 라운드 2 ③. 체크섬 불일치(검증 실패)는 접속 실패와 달리
     * <b>재시도로 절대 안 풀린다</b> — 백오프로 넘기면 "부팅은 성공했는데 표는
     * 영영 안 맞는" 조용한 실패가 된다. 연결 장애가 아니므로 fail-fast다.
     */
    @Test
    void 마이그레이션_검증_실패는_재시도하지_않고_부팅을_죽인다() {
        // 이력 테이블의 체크섬을 비틀어 validate 실패를 재현한다. 컨테이너 DB는
        // 이 JVM의 모든 컨텍스트가 공유하므로 반드시 되돌린다.
        jdbc.update("UPDATE flyway_schema_history_chat SET checksum = checksum + 1 "
                + "WHERE version = '301'");
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                    new org.springframework.boot.builder.SpringApplicationBuilder(CollectorApplication.class)
                            .web(org.springframework.boot.WebApplicationType.NONE)
                            .profiles("test")
                            .run("--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                                    "--spring.datasource.username=" + POSTGRES.getUsername(),
                                    "--spring.datasource.password=" + POSTGRES.getPassword()))
                    .as("검증 실패가 백오프 재시도로 넘어가면 부팅이 살아 실패가 숨는다")
                    // "아무 예외"로는 부팅 실패의 원인이 검증인지 못 가른다 — 뿌리까지 본다.
                    .hasRootCauseInstanceOf(org.flywaydb.core.api.exception.FlywayValidateException.class);
        } finally {
            jdbc.update("UPDATE flyway_schema_history_chat SET checksum = checksum - 1 "
                    + "WHERE version = '301'");
        }
    }

    /**
     * code-review 라운드 3 ⑴. 재시도 <b>도중</b> 드러난 영구 실패(검증·SQL 실행)는
     * 부팅이 이미 성공한 뒤라 던질 곳이 없다 — 컨텍스트를 닫아 프로세스를 내리고
     * 재시작이 부팅 fail-fast 경로를 타게 해야 한다. 로그만 남기고 살아 있으면
     * "부팅은 성공했는데 표는 영영 안 맞는" 조용한 실패다.
     */
    @Test
    void 재시도_중_영구_실패는_컨텍스트를_닫는다() throws Exception {
        java.util.concurrent.atomic.AtomicBoolean exited = new java.util.concurrent.atomic.AtomicBoolean();
        var strategy = new CollectorApplication.RetryingMigrationStrategy(
                new com.pokeclip.chat.collector.reconnect.ReconnectPolicy(
                        java.time.Duration.ofMillis(50), java.time.Duration.ofMillis(100)),
                () -> exited.set(true));   // 실제 System.exit은 테스트 JVM을 죽인다
        try (var fake = new org.springframework.context.support.GenericApplicationContext()) {
            fake.refresh();
            strategy.setApplicationContext(fake);
            java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();

            strategy.migrateWithRetry(() -> {
                if (calls.incrementAndGet() == 1) {
                    // 접속 실패류 — 백오프로. 죽은 포트의 실측 모양(08001 → ConnectException)이다.
                    throw new org.flywaydb.core.internal.exception.FlywaySqlException("connect refused",
                            new java.sql.SQLException("Connection refused", "08001",
                                    new java.net.ConnectException("Connection refused")));
                }
                throw new org.flywaydb.core.api.exception.FlywayValidateException(
                        new org.flywaydb.core.api.ErrorDetails(
                                org.flywaydb.core.api.CoreErrorCode.VALIDATE_ERROR, "checksum"),
                        "checksum mismatch");                              // 재시도에서 검증 실패
            });

            long deadline = System.nanoTime() + java.time.Duration.ofSeconds(3).toNanos();
            while (fake.isActive() && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertThat(calls.get()).as("재시도가 한 번도 안 돌았다면 이 검사는 부팅 갈래를 본 것이다")
                    .isGreaterThanOrEqualTo(2);
            assertThat(fake.isActive())
                    .as("재시도로 안 풀리는 실패에서 컨텍스트가 살아 있으면 실패가 숨는다")
                    .isFalse();
            assertThat(exited)
                    .as("정상 종료(exit 0)로 내리면 restart: on-failure가 재시작하지 않는다")
                    .isTrue();
        }
    }

    /**
     * PR #53 P1 ①. 재시도 판정이 「죽일 것」 허용 목록(검증·SQL 실행 실패)이면
     * <b>접속은 됐는데 권한이 없거나 이력 표를 못 만드는</b> 영구 오류가 목록 밖이라
     * 무한 재시도로 샌다 — 부팅은 성공하고 표는 영영 안 생긴다. 판정을 뒤집어
     * 「재시도할 것」(연결 장애)만 허용하고 나머지는 전부 fail-fast여야 한다.
     * 실측(2026-08-15): 권한 없음은 {@code FlywaySqlScriptException → PSQLException
     * SQLState=42501}로 온다 — 그 모양을 가짜로 만든다.
     */
    @Test
    void 접속은_됐는데_권한이_없는_실패는_재시도하지_않고_부팅을_죽인다() {
        var strategy = new CollectorApplication.RetryingMigrationStrategy(
                new com.pokeclip.chat.collector.reconnect.ReconnectPolicy(
                        java.time.Duration.ofMillis(50), java.time.Duration.ofMillis(100)),
                () -> { });
        var permissionDenied = new org.flywaydb.core.internal.exception.FlywaySqlException(
                "permission denied", new java.sql.SQLException("permission denied for schema", "42501"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        strategy.migrateWithRetry(() -> { throw permissionDenied; }))
                .as("권한 없음이 백오프로 넘어가면 부팅은 살고 표는 영영 안 생긴다")
                .isSameAs(permissionDenied);
        assertThat(migrateRetryThreadAlive())
                .as("fail-fast인데 재시도 스레드가 떠 있으면 두 경로가 동시에 도는 것이다")
                .isFalse();
    }

    @Test
    void 부팅하면_러너가_실제로_돌고_health가_같은_상태를_읽는다() {
        // RECONNECTING은 러너가 돌지 않고서는 나올 수 없는 값이다. 초기값은 DISABLED다.
        // <b>STOPPED가 아니다</b> — 연결 거부는 다시 걸면 풀릴 수 있는 사유라
        // 영구 정지로 찍으면 재연결이 붙어도 영영 못 올라온다.
        assertThat(status.state())
                .as("DISABLED면 러너가 한 번도 안 돈 것이다 — 초기값과 구분되지 않는다")
                .isEqualTo(CollectionStatus.State.RECONNECTING);
        assertThat(status.reason()).isEqualTo(StopReason.SESSION_AUTH_FAILED);

        // health가 러너와 다른 인스턴스를 보면 여기서 갈린다.
        assertThat(health.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.health().getDetails()).containsEntry("reason", "SESSION_AUTH_FAILED");
    }
}
