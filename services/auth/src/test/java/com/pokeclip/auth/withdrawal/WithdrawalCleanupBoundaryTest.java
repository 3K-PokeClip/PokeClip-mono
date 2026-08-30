package com.pokeclip.auth.withdrawal;

import ch.qos.logback.classic.Level;
import com.pokeclip.auth.profile.ImageType;
import com.pokeclip.auth.profile.PhotoStorage;
import com.pokeclip.auth.profile.StoredPhoto;
import com.pokeclip.auth.streamkey.StreamKeyService;
import com.pokeclip.auth.streamkey.secret.SecretStore;
import com.pokeclip.auth.token.TokenService;
import com.pokeclip.auth.user.User;
import com.pokeclip.auth.user.UserService;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 정리 잡의 <b>경계와 실패 갈래</b>. 창고를 프로그래밍 가능한 가짜로 바꿔 「느리다·던진다」를 만든다 —
 * 진짜 S3로는 그 둘을 결정적으로 못 만든다.
 *
 * <p>여기서 재는 넷은 전부 <b>「탈퇴 자체는 이미 끝났다」</b>는 한 문장의 다른 면이다.
 * 정리는 커밋 뒤 별도 스레드라, 무엇이 실패하든 <b>표 변경과 204를 되돌리지 않는다.</b>
 */
@Import(WithdrawalCleanupBoundaryTest.FakesConfig.class)
class WithdrawalCleanupBoundaryTest extends WithdrawalTestSupport {

    private final WithdrawalService service;
    private final StreamKeyService streamKeyService;
    private final WithdrawalCleanupExecutor cleanup;
    private final ProgrammableStorage storage;
    private final ProgrammableSecretStore secrets;

    WithdrawalCleanupBoundaryTest(MockMvc mockMvc, UserService userService, TokenService tokenService,
                                  JdbcTemplate jdbc, WithdrawalService service, StreamKeyService streamKeyService,
                                  WithdrawalCleanupExecutor cleanup, ProgrammableStorage storage,
                                  ProgrammableSecretStore secrets) {
        super(mockMvc, userService, tokenService, jdbc);
        this.service = service;
        this.streamKeyService = streamKeyService;
        this.cleanup = cleanup;
        this.storage = storage;
        this.secrets = secrets;
    }

    @BeforeEach
    void 가짜를_되돌린다() {
        storage.reset();
        secrets.reset();
    }

    /**
     * 🔴 <b>창고 호출이 요청 스레드의 트랜잭션 안으로 들어오면 안 된다.</b> 커넥션을 쥔 채 최대 8초를
     * 기다리면 동시 요청이 풀 크기(10)를 넘는 순간 사진과 무관한 로그인·토큰 회전까지 멈춘다
     * (「알려진 구멍」 9·10).
     *
     * <p>단언이 <b>셋</b>인 이유: 「트랜잭션이 안 열려 있다」만 재면 <b>정리 호출 자체가 사라져도 초록</b>이고
     * (호출이 없으면 기록도 없으니 null을 「안 열림」으로 읽는 실수를 막으려 `isFalse()`로 못박는다),
     * 스레드 이름을 같이 봐야 <b>「전용 스레드에서 돈다」</b>가 재어진다 — 창고 호출을 트랜잭션 본문으로
     * 옮기면 이름이 요청 스레드가 되어 여기서 걸린다.
     */
    @Test
    void 창고를_부르는_동안에는_트랜잭션이_열려_있지_않다() throws Exception {
        User user = newUser();

        withdraw(user);

        assertThat(storage.transactionActiveDuringDelete)
                .as("창고 호출이 트랜잭션 안이다 — 커넥션을 쥔 채 최대 8초를 기다리게 된다")
                .isEqualTo(Boolean.FALSE);
        assertThat(storage.threadDuringDelete)
                .as("정리가 전용 스레드가 아닌 곳에서 돌았다")
                .startsWith("withdrawal-cleanup-");
        assertThat(deletedAt(user)).as("정리 경계만 재고 탈퇴가 안 된 것을 놓치면 안 된다").isNotNull();
    }

    /**
     * 창고가 죽어 있어도 <b>탈퇴는 이미 커밋돼 있다</b>. 정리 잡의 예외는 WARN 한 줄로 남고
     * {@code completed}가 안 남는다 — 그 짝의 어긋남이 「사진이 안 지워진 회원」을 가리키는 실마리다.
     */
    @Test
    void 창고가_던져도_탈퇴는_이미_커밋돼_있다() throws Exception {
        User user = newUser();
        storage.failDelete = true;

        try (LogCaptor logs = new LogCaptor()) {
            withdraw(user);

            assertThat(logs.messages()).anyMatch(m -> m.startsWith("auth.withdrawal.cleanup.started userId="));
            assertThat(logs.messages())
                    .as("🔴 창고가 던졌는데 「다 지웠다」가 남았다 — 고아 파일의 주인을 못 찾게 된다")
                    .noneMatch(m -> m.startsWith("auth.withdrawal.cleanup.completed userId=" + user.getId()));
            assertThat(logs.levelOf("auth.withdrawal.cleanup.failed")).isEqualTo(Level.WARN);
        }
        assertThat(deletedAt(user)).as("정리 실패가 탈퇴를 되돌렸다").isNotNull();
    }

    /**
     * 🔴 <b>앞의 삭제가 던져도 뒤의 삭제를 건너뛰지 않는다.</b> 한 try로 묶으면 첫 실패가 나머지를
     * 통째로 건너뛰어 그 자리가 영구히 남는다 — 유튜브 정리에서 봇 리뷰(PR #116)가 잡은 것과 같은 모양이다.
     * 여기서는 비밀값 삭제를 실패시키고 <b>사진이 그래도 지워졌는지</b>를 본다.
     */
    @Test
    void 비밀값_삭제가_실패해도_사진은_지운다() throws Exception {
        User user = newUser();
        streamKeyService.ensureKey(user.getId());
        secrets.failDelete = true;

        try (LogCaptor logs = new LogCaptor()) {
            withdraw(user);

            assertThat(storage.deletedUsers)
                    .as("🔴 비밀값 삭제가 던지자 사진 삭제를 통째로 건너뛰었다")
                    .contains(user.getId());
            assertThat(logs.levelOf("auth.withdrawal.cleanup.failed"))
                    .as("삼키면 안 된다 — 실패했다는 사실이 어디에도 안 남는다")
                    .isEqualTo(Level.WARN);
        }
    }

    /**
     * 🔴 <b>5초가 감수하는 것을 코드로 굳힌다.</b> 창고가 느리면(6~8초) 종료 시한에 잘려
     * <b>사진이 안 지워지는데 표는 이미 바뀌어 있다</b> — 아무도 그 파일을 안 가리키고 파일 이름을
     * 되짚을 근거도 사라진다.
     *
     * <p>그래서 로그를 짝으로 남긴다: {@code started}는 있는데 {@code completed}가 없는 회원 번호 —
     * <b>그것이 남은 파일의 주인이다.</b> {@code shutdown_timeout}은 「그런 일이 있었다」만 말하고
     * 누구인지는 안 알려준다.
     *
     * <p>🔴 <b>스프링이 관리하는 풀을 종료시키지 않는다</b> — 그러면 컨텍스트가 오염돼 뒤 검사가
     * 전부 무너진다. 같은 클래스의 새 인스턴스를 만들어 그 위에서 잰다.
     */
    @Test
    void 느린_창고는_시한에_끊기고_started만_남는다() throws Exception {
        User user = newUser();
        storage.blockDelete = new CountDownLatch(1);
        WithdrawalCleanupExecutor own = new WithdrawalCleanupExecutor();

        try (LogCaptor logs = new LogCaptor()) {
            own.submit(own.new Job(user.getId(), null, () -> service.cleanUp(user.getId(), List.of())));
            assertThat(storage.deleteEntered.await(5, TimeUnit.SECONDS))
                    .as("정리 잡이 창고에 들어가지도 않았다 — 아래는 아무것도 안 잰다").isTrue();

            own.shutdown();   // SHUTDOWN_WAIT(5초) 대기 → 인터럽트 → FORCED_STOP_WAIT(1초)

            assertThat(logs.messages())
                    .anyMatch(m -> m.equals("auth.withdrawal.cleanup.started userId=" + user.getId()));
            assertThat(logs.messages())
                    .as("🔴 시한에 끊겼는데 「다 지웠다」가 남았다")
                    .noneMatch(m -> m.equals("auth.withdrawal.cleanup.completed userId=" + user.getId()));
            assertThat(logs.levelOf("auth.withdrawal.cleanup.shutdown_timeout"))
                    .as("그런 일이 있었다는 신호가 없다")
                    .isEqualTo(Level.WARN);
        } finally {
            storage.blockDelete.countDown();
        }
    }

    // ── 도구 ────────────────────────────────────────────────────────────

    private void withdraw(User user) throws Exception {
        mockMvc.perform(delete("/api/auth/me").header("Authorization", bearer(user)))
                .andExpect(status().isNoContent());
        assertThat(cleanup.awaitIdle(Duration.ofSeconds(20)))
                .as("정리 잡이 시한 안에 안 끝났다").isTrue();
    }

    private Object deletedAt(User user) {
        return jdbc.queryForObject("SELECT deleted_at FROM users WHERE id = ?", Object.class, user.getId());
    }

    @TestConfiguration
    static class FakesConfig {

        @Bean
        @Primary
        ProgrammableStorage programmableStorage() {
            return new ProgrammableStorage();
        }

        @Bean
        @Primary
        ProgrammableSecretStore programmableSecretStore() {
            return new ProgrammableSecretStore();
        }
    }

    /** 창고 자리. 「느리다·던진다」를 결정적으로 만들고, 부를 때의 경계를 기록한다. */
    static class ProgrammableStorage implements PhotoStorage {

        private final List<Long> deletedUsers = new ArrayList<>();
        private Boolean transactionActiveDuringDelete;
        private String threadDuringDelete;
        private boolean failDelete;
        private CountDownLatch deleteEntered = new CountDownLatch(1);
        private CountDownLatch blockDelete;

        void reset() {
            deletedUsers.clear();
            transactionActiveDuringDelete = null;
            threadDuringDelete = null;
            failDelete = false;
            deleteEntered = new CountDownLatch(1);
            blockDelete = null;
        }

        @Override
        public void put(long userId, long version, byte[] bytes, ImageType type) {
        }

        @Override
        public Optional<StoredPhoto> get(long userId, long version) {
            return Optional.empty();
        }

        @Override
        public void deleteAll(long userId) {
            transactionActiveDuringDelete = TransactionSynchronizationManager.isActualTransactionActive();
            threadDuringDelete = Thread.currentThread().getName();
            deletedUsers.add(userId);
            deleteEntered.countDown();
            if (blockDelete != null) {
                try {
                    // 종료 시한(5초)보다 훨씬 길게 — 끊기는 것이 이 갈래에서 재려는 것이다.
                    if (!blockDelete.await(60, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("창고가 안 끊겼다");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("창고 호출이 끊겼다", e);
                }
            }
            if (failDelete) {
                throw new IllegalStateException("창고가 못 답한다");
            }
        }
    }

    /** 비밀 보관소 자리. 진짜 표를 안 쓰고 메모리에 둔다 — 여기서 재는 것은 삭제 실패 갈래뿐이다. */
    static class ProgrammableSecretStore implements SecretStore {

        private final Map<String, String> values = new HashMap<>();
        private boolean failDelete;

        void reset() {
            values.clear();
            failDelete = false;
        }

        @Override
        public void put(String ref, String value) {
            values.put(ref, value);
        }

        @Override
        public Optional<String> get(String ref) {
            return Optional.ofNullable(values.get(ref));
        }

        @Override
        public void delete(String ref) {
            if (failDelete) {
                throw new IllegalStateException("비밀 보관소가 못 답한다");
            }
            values.remove(ref);
        }
    }
}
