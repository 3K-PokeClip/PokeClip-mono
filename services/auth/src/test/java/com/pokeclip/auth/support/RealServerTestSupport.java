package com.pokeclip.auth.support;

import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;

/**
 * 실제 톰캣을 띄운다. {@link IntegrationTestSupport}와 나눠 둔 이유는 웹 환경이
 * 달라서다 — MOCK과 RANDOM_PORT는 서로 다른 ApplicationContext라 한 클래스로
 * 합칠 수 없고, 합치면 캐시된 컨텍스트를 공유하지 못해 컨테이너가 두 번 뜬다.
 *
 * <p>PostgreSQL 컨테이너는 {@link IntegrationTestSupport}의 것을 그대로 쓴다.
 * static 필드라 JVM 안에서 하나만 뜬다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("test")
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
public abstract class RealServerTestSupport {

    static {
        IntegrationTestSupport.POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", IntegrationTestSupport.POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", IntegrationTestSupport.POSTGRES::getUsername);
        registry.add("spring.datasource.password", IntegrationTestSupport.POSTGRES::getPassword);
        // 가짜 치지직도 같은 것을 쓴다 — 없으면 test yml의 자리표시(127.0.0.1:1)로 뜬다.
        registry.add("pokeclip.chzzk.api-base-uri", IntegrationTestSupport.CHZZK::baseUrl);
        // IntegrationTestSupport와 쌍둥이 자리다 — 한쪽만 고치면 톰캣을 띄우는 테스트가 자리표시(127.0.0.1:1)로 뜬다.
        registry.add("pokeclip.youtube.token-uri", IntegrationTestSupport.YOUTUBE::tokenUri);
        registry.add("pokeclip.youtube.revoke-uri", IntegrationTestSupport.YOUTUBE::revokeUri);
        registry.add("pokeclip.youtube.api-base-uri", IntegrationTestSupport.YOUTUBE::baseUrl);
    }
}
