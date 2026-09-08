package com.pokeclip.auth.retention;

import com.pokeclip.auth.user.User;
import com.pokeclip.auth.user.UserRepository;
import com.pokeclip.auth.user.UserService;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 틱을 <b>실물 배선으로</b> 돌린다. mock 단위 검사로는 못 보는 것 하나 — <b>스케줄러가 트랜잭션 최상단인가.</b>
 * {@code tick()}에 {@code @Transactional}이 붙으면 첫 표의 예외가 트랜잭션을 rollback-only로 만들어, 틱이
 * 그 예외를 catch해도 뒤 표의 삭제가 커밋 시점에 통째로 사라진다. mock은 프록시를 안 타므로 그때도 초록이다.
 * ({@code YoutubeRevocationCheckSchedulerIntegrationTest}와 같은 이유·같은 모양.)
 *
 * <p>첫 표(시도 기록)를 문장 단위 트리거로 막는다 — DELETE가 0행이어도 BEFORE 문장 트리거는 돈다.
 * 시험이 끝나면 반드시 떼어낸다(컨테이너는 JVM 안에서 공유다).
 *
 * <p>{@code @TestPropertySource}로 스케줄러를 켠 별도 컨텍스트다(테스트 프로파일 기본은 꺼짐).
 * 🔴 주기를 <b>1시간</b>으로 준다 — 이 컨텍스트는 스프링 캐시에 남아 JVM이 사는 동안 진짜 틱이 등록돼 있다. 10분이면 전체 실행이
 * 길어지는 날(지금 1분 40초, 시험이 여섯 배로 늘거나 느린 CI) 다른 청소 시험이 행을 심고 지우는 몇 ms 사이에 틱이 끼어
 * {@code deleted}가 0이 된다(plan-critic 발견 E). 유튜브 점검 컨텍스트가 1시간으로 같은 안전 논리를 쓴다. 배선 시험은
 * 등록 여부만 보므로 값은 무관하다.
 */
@TestPropertySource(properties = {"pokeclip.retention.enabled=true", "pokeclip.retention.interval=PT1H"})
class RetentionCleanupSchedulerIntegrationTest extends RetentionTestSupport {

    private final RetentionCleanupScheduler scheduler;
    private final UserRepository userRepository;
    private final JdbcTemplate jdbc;
    private final ApplicationContext context;

    RetentionCleanupSchedulerIntegrationTest(RetentionCleanupScheduler scheduler, UserService userService,
                                             UserRepository userRepository, JdbcTemplate jdbc,
                                             ApplicationContext context) {
        super(userService);
        this.scheduler = scheduler;
        this.userRepository = userRepository;
        this.jdbc = jdbc;
        this.context = context;
    }

    @BeforeEach
    void clear() {
        jdbc.update("DELETE FROM refresh_tokens");
        jdbc.update("DELETE FROM pairing_exchange_attempts");
        userRepository.deleteAll();
    }

    @AfterEach
    void unblockAndClear() {
        jdbc.execute("DROP TRIGGER IF EXISTS retention_test_block ON pairing_exchange_attempts");
        jdbc.execute("DROP FUNCTION IF EXISTS retention_test_fail()");
        clear();
    }

    @Test
    void 첫_표의_삭제가_터져도_셋째_표의_삭제가_커밋된다() {
        User user = newUser();
        jdbc.update("INSERT INTO refresh_tokens (user_id, token_hash, expires_at, revoked_at, created_at) "
                + "VALUES (?, 'stale', now() + interval '1 day', now() - interval '15 days', now() - interval '20 days')",
                user.getId());
        jdbc.execute("CREATE FUNCTION retention_test_fail() RETURNS trigger AS $$ "
                + "BEGIN RAISE EXCEPTION 'retention-test'; END $$ LANGUAGE plpgsql");
        jdbc.execute("CREATE TRIGGER retention_test_block BEFORE DELETE ON pairing_exchange_attempts "
                + "FOR EACH STATEMENT EXECUTE FUNCTION retention_test_fail()");

        try (LogCaptor logs = new LogCaptor()) {
            scheduler.tick();

            assertThat(logs.messages())
                    .anyMatch(m -> m.startsWith("auth.retention.failed table=pairing_exchange_attempts"))
                    .contains("auth.retention.cleaned table=refresh_tokens deleted=1 rounds=1 capped=false");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_tokens", Integer.class))
                .as("앞 표의 예외가 뒤 표의 삭제를 되돌렸다 — 틱이 트랜잭션 최상단이 아니다")
                .isZero();
    }

    /**
     * 부팅 검증 빈({@link RetentionKeepForCheck})이 <b>실제 컨텍스트에</b> 있나. {@code @Component}를 떼도 752건이
     * 전부 초록이었다(리뷰 라운드 2 재현 R2) — 그 클래스를 참조하는 곳이 {@code RetentionKeepForCheckTest}뿐인데
     * ContextRunner는 클래스를 손으로 등록하므로 배선과 무관하게 돈다.
     *
     * <p>여기에 두는 이유: 이 클래스가 이미 {@code enabled=true}인 컨텍스트다(컨텍스트 추가 0).
     * {@code RetentionCleanupSchedulerWiringTest}는 {@code enabled=false}라 <b>빈이 없는 것이 정답</b>이어서 못 쓴다.
     */
    @Test
    void 부팅_검증_빈이_실제_컨텍스트에_배선돼_있다() {
        assertThat(context.getBeanNamesForType(RetentionKeepForCheck.class))
                .as("보관 ≥ 수명 검사가 컨텍스트에 없다 — 운영에서 그 관계가 깨져도 부팅이 그냥 된다")
                .hasSize(1);
    }
}
