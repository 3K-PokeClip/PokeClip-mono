package com.pokeclip.clip.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
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

    /**
     * 스프링이 보는 auth는 이것 <b>하나뿐</b>이다. JVM에 하나만 두는 이유는 컨텍스트 캐시다 —
     * 클래스마다 서버를 띄우면 {@code base-url} 값이 달라져 캐시 키가 갈리고, 컨텍스트가
     * 열셋에서 서른 몇 개로 늘어 각자 Hikari 풀을 들고 {@code max_connections=300}을 향해 간다.
     *
     * <p>대가는 <b>응답 상태가 JVM 전역</b>이라는 것이다. 그래서 {@link #가짜_auth를_초기화한다()}가
     * 매 시험 앞에서 되돌리고, 초기 상태는 <b>503</b>이다 — 답을 안 건 시험이 200을 받으면
     * 자격 판정을 통째로 안 재면서 초록이 된다.
     */
    protected static final FakeAuth AUTH = FakeAuth.start();

    static {
        POSTGRES.start();
        seedNonEmptySchema();
    }

    /**
     * 🔴 상위 클래스의 {@code @BeforeEach}가 하위보다 <b>먼저</b> 돈다(JUnit 5 규약).
     * 그래서 하위가 {@code @BeforeEach}에서 자기 답을 걸면 그것이 남는다 — 순서가 반대였다면
     * 이 초기화가 하위의 설정을 지웠을 것이다.
     */
    @BeforeEach
    protected void 가짜_auth를_초기화한다() {
        AUTH.reset();
    }

    /**
     * auth가 먼저 마이그레이션한 공유 DB를 재현한다 — public이 비어 있지 않은데
     * 자기 이력 테이블(flyway_schema_history_clip)은 없는 상태. 운영 전제
     * (DB 공유 + auth 선배포)에서 clip의 첫 부팅이 만나는 상황이 정확히 이것이고,
     * application.yml의 baseline-on-migrate가 없으면 "Found non-empty schema(s)
     * ... but no schema history table"로 죽는다. 여기 심어 두면 모든 스프링
     * 테스트가 이 시나리오를 지나가므로 baseline 설정을 지우는 회귀가 즉시
     * 전체 빨강이 된다.
     *
     * <p>여기서 {@code stream_segments}도 함께 심는다. <b>이 표의 소유는 1번(Media)이라
     * clip의 Flyway 마이그레이션에 넣으면 안 된다</b>(ADR-030) — 운영에서는 media가 먼저
     * 만들어 둔 표를 우리가 읽기만 한다. 테스트 DB에는 그 표를 만드는 사람이 없으므로
     * 여기가 그 자리다.
     */
    private static void seedNonEmptySchema() {
        try (java.sql.Connection connection = java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             java.sql.Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE auth_placeholder_for_baseline (id int)");
            // 정본은 media/internal/index/ddl.go (소유 1번). 이 사본이 어긋나는 것은
            // 계약이 막는다 — 컬럼 변경은 3번(우리) 승인 필수라(ADR-030, 계약 4절)
            // 1번이 바꾸면 우리가 먼저 안다. 그때 이 시드도 같이 고친다.
            //
            // stream_segments_local_path_uq 인덱스는 일부러 뺐다. 그것은 1번의 쓰기
            // 멱등용이고 우리는 읽기만 한다 — 시드에 넣으면 「clip도 그 이름에
            // 의존한다」는 거짓 신호가 된다.
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS stream_segments (
                        stream_id        text        NOT NULL,
                        seq              bigint      NOT NULL,
                        start_pts_ms     bigint      NOT NULL,
                        start_wall_utc   timestamptz NOT NULL,
                        duration_ms      int         NOT NULL,
                        s3_key           text        NOT NULL,
                        local_path       text,
                        upload_state     text        NOT NULL DEFAULT 'pending',
                        uploaded_at      timestamptz,
                        bytes            bigint,
                        is_discontinuity boolean     NOT NULL DEFAULT false,
                        PRIMARY KEY (stream_id, seq)
                    )""");
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("baseline 시나리오 시드 실패", e);
        }
    }

    /**
     * 표 넷을 <b>자식부터</b> 비운다 — {@code stream_segments} · {@code jump_cards} ·
     * {@code broadcast_events} · {@code broadcasts}. {@code jump_cards}가
     * {@code broadcasts}의 자식이라 카드를 먼저 안 지우면 방송 삭제가 FK로 죽는데,
     * <b>단독 실행에서는 안 보이고 모듈 전체에서만 터진다</b>(POK-118에서 실제로 밟았다 —
     * 새 시험이 카드를 남기자 같은 패키지의 다른 클래스 셋이 깨졌다).
     *
     * <p>{@code stream_segments}는 <b>이 시드에 한해</b> FK가 없어 순서가 자유롭다
     * (정본 계약은 {@code stream_id}에 {@code broadcasts} FK를 적어 두었고, 시드는 그것을
     * 안 만든다 — 세그먼트 조회 시험이 부모 행을 필요로 하지 않는다). 그래도 맨 앞에 두는 것은
     * 운영 표의 모양과 같은 순서를 유지해, 나중에 시드에 FK가 생겨도 이 헬퍼가 안 깨지게
     * 하려는 것이다.
     *
     * <p>이 헬퍼를 두는 이유는 <b>새 시험 클래스가 그 순서를 몰라도 안 열리게</b> 하는 것이다.
     * 정리가 필요한 클래스는 {@code @BeforeEach}에서 이것을 부른다.
     */
    protected static void 방송과_카드를_비운다(JdbcTemplate jdbc) {
        jdbc.update("DELETE FROM stream_segments");
        jdbc.update("DELETE FROM jump_cards");
        jdbc.update("DELETE FROM broadcast_events");
        jdbc.update("DELETE FROM broadcasts");
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    /**
     * {@code application-test.yml}의 주소(아무도 안 듣는 포트)를 {@link #AUTH}로 덮는다.
     * <b>여기 한 곳에서만</b> 덮는 것이 규칙이다 — 클래스마다 자기 {@code @DynamicPropertySource}를
     * 달면 컨텍스트 캐시가 갈린다.
     */
    @DynamicPropertySource
    static void authClientProperties(DynamicPropertyRegistry registry) {
        registry.add("pokeclip.auth-client.base-url", AUTH::baseUrl);
    }
}
