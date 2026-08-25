package com.pokeclip.chat.detector.support;

import org.junit.jupiter.api.BeforeEach;
import org.slf4j.bridge.SLF4JBridgeHandler;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.function.BooleanSupplier;

/**
 * 컨테이너를 static 블록에서 한 번만 띄우고 JVM 종료까지 재사용한다. Spring이
 * ApplicationContext를 캐시하므로 클래스마다 컨테이너를 멈추면 두 번째 테스트 클래스가
 * 죽은 커넥션을 잡는다(auth·chat-collector 선례).
 *
 * <p><b>{@code @ActiveProfiles("test")}가 필수다.</b> 이것이 없으면 {@code application-test.yml}이
 * 안 실리고, 태스크 4에서 {@code InternalApiProperties}({@code @NotBlank})가 생기는 순간
 * 모든 스프링 시험이 컨텍스트를 못 띄운다(계획 검증 F4). {@code clip}이 같은 이유로 같은 줄을 쓴다.
 */
@ActiveProfiles("test")
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
public abstract class IntegrationTestSupport {

    // Testcontainers 2.x에서 이 클래스는 비제네릭이다. <?>를 붙이면 컴파일되지 않는다.
    /**
     * max_connections를 기본값 100에서 올린다. 스프링 컨텍스트가 여러 개고 각자 자기 Hikari
     * 풀(기본 10)을 갖는다. 컨텍스트 수 × 풀 크기가 100을 넘으면 {@code FATAL: sorry, too many
     * clients already}로 컨텍스트 로딩이 무너진다 — auth에서 실측한 실패다.
     */
    protected static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:17")
                    .withCommand("postgres", "-c", "max_connections=300");

    static {
        POSTGRES.start();
        seedNonEmptySchema();
    }

    /**
     * 남이 먼저 마이그레이션한 공유 DB를 재현한다 — public이 비어 있지 않은데 우리 이력
     * 테이블(flyway_schema_history_chat_detector)은 없는 상태. 운영 전제(DB 공유 + auth 선배포)에서
     * 이 서버의 첫 부팅이 만나는 상황이 정확히 이것이고, application.yml의 baseline-on-migrate가
     * 없으면 "Found non-empty schema(s) ... but no schema history table"로 죽는다.
     * 여기 심어 두면 모든 스프링 테스트가 이 시나리오를 지나가므로, baseline 설정을 지우는
     * 회귀가 즉시 전체 빨강이 된다.
     */
    private static void seedNonEmptySchema() {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE auth_placeholder_for_baseline (id int)");
        } catch (SQLException e) {
            throw new IllegalStateException("공유 DB 재현용 더미 표 생성 실패", e);
        }
    }

    /**
     * 운영 전제(JUL→SLF4J 브릿지가 붙어 있다)를 매 검사 앞에 되살린다. Spring 7의 테스트
     * 컨텍스트 캐시가 컨텍스트를 갈아탈 때 브릿지를 떼고 되돌리지 않는다(chat-collector 실측).
     *
     * <p>{@code final}이다 — 하위 클래스가 같은 시그니처를 정의하면 JUnit 5는 상위
     * 라이프사이클 메서드를 안 부른다(오버라이드로 본다).
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
     * 조건이 참이 되거나 시한이 찰 때까지 20ms 간격으로 기다린다. <b>시한이 차면 조용히
     * 돌아온다</b> — 부른 뒤에 반드시 같은 값을 단언하라. 안 그러면 시한을 지운 자기검사가
     * 초록으로 지나간다.
     */
    protected static void awaitUntil(Duration timeout, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
    }
}
