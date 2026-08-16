package com.pokeclip.chat.collector.support;

import org.junit.jupiter.api.BeforeEach;
import org.slf4j.bridge.SLF4JBridgeHandler;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.function.BooleanSupplier;

/**
 * 컨테이너를 static 블록에서 한 번만 띄우고 JVM 종료까지 재사용한다.
 * Spring이 ApplicationContext를 캐시해 재사용하므로, 클래스마다 컨테이너를
 * 멈추면 두 번째 테스트 클래스가 죽은 커넥션을 잡는다(auth 선례와 같은 이유).
 *
 * <p>auth의 것과 달리 {@code @SpringBootTest}·{@code @ActiveProfiles} 메타를
 * 일부러 뺐다 — 이 모듈의 하위 클래스들은 이미 각자
 * {@code @FakeChzzkTest}/{@code @SpringBootTest(properties=…)}를 직접 갖고 있어,
 * 겹쳐 두면 어느 쪽이 이기는지를 다음 사람이 다시 조사하게 된다.
 *
 * <p>autowireMode = ALL이라 하위 테스트가 생성자 파라미터로 빈을 받는다.
 * 기존 필드 주입 테스트와 공존한다.
 */
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
public abstract class IntegrationTestSupport {

    // Testcontainers 2.x에서 이 클래스는 비제네릭이다. 1.x의 self-type
    // 파라미터(PostgreSQLContainer<SELF>)는 새 org.testcontainers.postgresql
    // 패키지에서 사라졌다 — <?>를 붙이면 컴파일되지 않는다.
    /**
     * max_connections를 기본값 100에서 올린다. 스프링 컨텍스트가 여러 개고 각자
     * 자기 Hikari 풀(기본 10)을 갖는다. 컨텍스트 수 × 풀 크기가 100을 넘으면
     * <b>{@code FATAL: sorry, too many clients already}</b>로 컨텍스트 로딩이
     * 무너진다 — auth에서 실측한 실패다. 300은 auth와 같은 여유값이다.
     */
    protected static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:17")
                    .withCommand("postgres", "-c", "max_connections=300");

    static {
        POSTGRES.start();
        seedNonEmptySchema();
    }

    /**
     * auth가 먼저 마이그레이션한 공유 DB를 재현한다 — public이 비어 있지 않은데
     * 자기 이력 테이블(flyway_schema_history_chat)은 없는 상태. 운영 전제
     * (DB 공유 + auth 선배포)에서 chat-collector의 첫 부팅이 만나는 상황이
     * 정확히 이것이고, application.yml의 baseline-on-migrate가 없으면
     * "Found non-empty schema(s) ... but no schema history table"로 죽는다
     * (verifier 실물 부팅 실측, 2026-08-15). 여기 심어 두면 모든 스프링 테스트가
     * 이 시나리오를 지나가므로 baseline 설정을 지우는 회귀가 즉시 전체 빨강이 된다.
     */
    private static void seedNonEmptySchema() {
        try (java.sql.Connection connection = java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             java.sql.Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE auth_placeholder_for_baseline (id int)");
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("공유 DB 재현용 더미 표 생성 실패", e);
        }
    }

    /**
     * 운영 전제(JUL→SLF4J 브릿지가 붙어 있다)를 매 검사 앞에 되살린다. Spring 7의 테스트 컨텍스트 캐시는 다른
     * 컨텍스트로 갈아탈 때 이전 컨텍스트를 pause하고(DefaultContextCache.pauseOnContextSwitchIfNecessary), 그러면
     * Boot의 LoggingApplicationListener$Lifecycle.stop()이 LoggingSystem.cleanUp()을 불러 SLF4JBridgeHandler를
     * JUL root에서 떼는데 resume(start)는 되돌리지 않는다(Boot 4.1.0·Spring 7.0.8, 2026-08-16 스택 실측). JUL로 찍는
     * 라이브러리(pgjdbc)의 로그를 재는 검사는 그 뒤 빈손이 된다 — 순서 의존. 운영은 컨텍스트 하나라 이 현상이 없다.
     * {@code spring.test.context.cache.pause=NEVER}는 캐시된 컨텍스트의 SmartLifecycle이 계속 돌아 모듈 전체에 영향이라
     * 안 쓴다. isInstalled()면 아무것도 안 한다.
     *
     * <p>{@code final}이다 — 하위 클래스가 같은 시그니처를 정의하면 JUnit 5는 <b>상위 라이프사이클 메서드를 안 부른다</b>
     * (오버라이드로 본다). 그러면 그 클래스에서만 재설치가 빠지고 원인은 다른 파일에 있다.
     */
    @BeforeEach
    protected final void reinstallJulBridge() {
        if (!SLF4JBridgeHandler.isInstalled()) {
            SLF4JBridgeHandler.install();
        }
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    /**
     * 조건이 참이 되거나 시한이 찰 때까지 20ms 간격으로 기다린다. <b>시한이 차면 조용히 돌아온다</b> — 부른 뒤에
     * 반드시 같은 값을 단언하라. 안 그러면 시한을 지운 자기검사가 초록으로 지나간다(ArchiveOutageTest 헤더).
     * 아카이브 카드에서 네 벌로 복사되던 것을 여기 하나로 모았다 — 그 전 클래스들의 사본은 안 건드렸다.
     */
    protected static void awaitUntil(Duration timeout, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
    }
}
