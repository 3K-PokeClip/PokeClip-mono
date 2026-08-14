package com.pokeclip.chat.collector.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.testcontainers.postgresql.PostgreSQLContainer;

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

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
