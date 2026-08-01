package com.pokeclip.core.support;

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
    protected static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:17");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
