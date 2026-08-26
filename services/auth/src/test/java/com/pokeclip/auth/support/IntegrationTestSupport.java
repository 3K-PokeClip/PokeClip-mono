package com.pokeclip.auth.support;

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
     * 자기 Hikari 풀을 갖는데, 동시성 테스트가 실제로 겹쳐 돌려면 풀이 커야 한다
     * (application-test.yml에서 30으로 올려 뒀다). 기본 100이면
     * <b>{@code FATAL: sorry, too many clients already}</b>로 컨텍스트 로딩이
     * 무너진다 — 30 × 컨텍스트 수가 100을 넘기 때문이다.
     *
     * <p>300인 이유: 컨텍스트가 8개 안팎이고 8 × 30 = 240이라 여유를 뒀다.
     */
    protected static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:17")
                    .withCommand("postgres", "-c", "max_connections=300");

    /**
     * 가짜 치지직도 static으로 하나만 띄운다. 모든 컨텍스트가 같은 api-base-uri를 받아야
     * 컨텍스트 캐시가 유지된다 — 개별 테스트가 @DynamicPropertySource로 다른 값을 넣으면
     * 컨텍스트가 하나 더 뜬다. 상태는 각 테스트 클래스가 @BeforeEach에서 reset()한다.
     */
    protected static final FakeChzzkServer CHZZK = FakeChzzkServer.start();

    /** 가짜 구글도 같은 이유로 static 하나다. 상태는 각 테스트 클래스가 {@code @BeforeEach}에서 reset()한다. */
    protected static final FakeYoutubeServer YOUTUBE = FakeYoutubeServer.start();

    static {
        POSTGRES.start();
        seedNonEmptySchema();
    }

    /**
     * 다른 서버(chat-collector)가 먼저 마이그레이션한 공유 DB를 재현한다 — public이
     * 비어 있지 않은데 auth의 이력 테이블(flyway_schema_history_auth)은 없는 상태.
     * 공유 DB에서는 어느 서버가 먼저 뜰지 정해져 있지 않으므로 auth도 이 상황을
     * 만나고, application.yml의 baseline-on-migrate가 없으면 "Found non-empty
     * schema(s) ... but no schema history table"로 죽는다(chat-collector 쪽 실물 부팅
     * 실측 2026-08-15, PR #56). 여기 심어 두면 모든 스프링 테스트가 이 경로를 지나므로
     * baseline 설정을 지우는 회귀가 즉시 전체 빨강이 된다 — chat-collector와 같은 방식.
     */
    private static void seedNonEmptySchema() {
        try (java.sql.Connection connection = java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             java.sql.Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE chat_collector_placeholder_for_baseline (id int)");
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("공유 DB 재현용 더미 표 생성 실패", e);
        }
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("pokeclip.chzzk.api-base-uri", CHZZK::baseUrl);
        // 유튜브는 주소가 셋이다 — 토큰·철회는 별개 키, 채널 목록만 api-base-uri 아래에 붙는다.
        registry.add("pokeclip.youtube.token-uri", YOUTUBE::tokenUri);
        registry.add("pokeclip.youtube.revoke-uri", YOUTUBE::revokeUri);
        registry.add("pokeclip.youtube.api-base-uri", YOUTUBE::baseUrl);
    }
}
