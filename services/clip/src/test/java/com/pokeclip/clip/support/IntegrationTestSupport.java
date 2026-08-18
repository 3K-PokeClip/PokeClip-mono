package com.pokeclip.clip.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 컨테이너를 static 블록에서 한 번만 띄우고 JVM 종료까지 재사용한다.
 * Spring이 ApplicationContext를 캐시해 재사용하므로, 클래스마다 컨테이너를
 * 멈추면 두 번째 테스트 클래스가 죽은 커넥션을 잡는다.
 *
 * autowireMode = ALL이라 하위 테스트가 생성자 파라미터로 빈을 받는다.
 * 이 프로젝트는 필드 주입을 커밋 훅으로 막는다 — .githooks/pre-commit 참고.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
public abstract class IntegrationTestSupport {

    // Testcontainers 2.x에서 이 클래스는 비제네릭이다. 1.x의 self-type
    // 파라미터(PostgreSQLContainer<SELF>)는 새 org.testcontainers.postgresql
    // 패키지에서 사라졌다 — <?>를 붙이면 컴파일되지 않는다.
    /**
     * max_connections를 기본값 100에서 올린다. 스프링 컨텍스트가 여러 개고 각자
     * 자기 Hikari 풀(기본 10)을 갖는다. 컨텍스트 수 × 풀 크기가 100을 넘으면
     * {@code FATAL: sorry, too many clients already}로 컨텍스트 로딩이 무너진다
     * — auth에서 실측한 실패다. 300은 auth·chat-collector와 같은 여유값이다.
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
     * 자기 이력 테이블(flyway_schema_history_clip)은 없는 상태. 운영 전제
     * (DB 공유 + auth 선배포)에서 clip의 첫 부팅이 만나는 상황이 정확히 이것이고,
     * application.yml의 baseline-on-migrate가 없으면 "Found non-empty schema(s)
     * ... but no schema history table"로 죽는다. 여기 심어 두면 모든 스프링
     * 테스트가 이 시나리오를 지나가므로 baseline 설정을 지우는 회귀가 즉시
     * 전체 빨강이 된다.
     */
    private static void seedNonEmptySchema() {
        try (java.sql.Connection connection = java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             java.sql.Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE auth_placeholder_for_baseline (id int)");
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("baseline 시나리오 시드 실패", e);
        }
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
