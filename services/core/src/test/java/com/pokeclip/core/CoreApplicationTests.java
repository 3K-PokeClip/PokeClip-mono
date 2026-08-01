package com.pokeclip.core;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
class CoreApplicationTests {

    /**
     * 팀 docker-compose와 같은 이미지를 쓴다. 테스트에서만 다른 메이저 버전을 쓰면
     * 로컬·CI는 통과하는데 실제 DB에서만 깨지는 차이를 못 잡는다.
     *
     * <p>컨테이너를 static 블록에서 한 번만 띄우고 JVM 종료까지 재사용한다.
     * {@code @Testcontainers}/{@code @Container}는 클래스마다 컨테이너를 멈추는데,
     * Spring은 ApplicationContext를 캐시해 재사용하므로 두 번째 테스트 클래스가
     * 죽은 커넥션을 잡는다.
     *
     * <p>Testcontainers 2.x에서 이 클래스는 비제네릭이다 — {@code <?>}를 붙이면
     * 컴파일되지 않는다.
     */
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Test
    void 컨텍스트가_뜬다() {
    }
}
