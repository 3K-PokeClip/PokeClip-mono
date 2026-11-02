package com.pokeclip.auth.profile;

import com.pokeclip.auth.AuthException;
import com.pokeclip.auth.token.TokenService;
import com.pokeclip.auth.user.User;
import com.pokeclip.auth.user.UserRepository;
import com.pokeclip.auth.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 🔴 <b>창고를 부르는 동안 DB 트랜잭션이 열려 있으면 안 된다.</b> 커넥션을 쥔 채 외부 HTTP를
 * 최대 8초 기다리면, 동시 요청이 풀 크기(10)를 넘는 순간 사진과 무관한 로그인·토큰 회전까지 멈춘다
 * (「알려진 구멍」 9·10번 — 풀 10·동시 25에서 21/25 실패·30초 마비 실측).
 *
 * <p><b>풀을 고갈시켜 재지 않고 그 자리에서 직접 묻는다</b>({@code transaction-boundary} 스킬의
 * 「실물로 재는 법」). 고갈 검사는 컨텍스트를 하나 더 띄우고 25스레드를 돌려야 하는데, 여기서 알고
 * 싶은 것은 「경계가 어디인가」 하나뿐이라 가짜 창고에 물어보는 것으로 결정적으로 갈린다.
 *
 * <p>두 단언이 한 쌍이다 — 「트랜잭션이 안 열려 있다」만 재면 <b>표 갱신 자체가 사라져도 초록</b>이
 * 된다. 표가 실제로 갱신됐는지를 같이 봐야 「창고는 밖, 표는 안」이 재어진다.
 */
@Import(ProfilePhotoTransactionBoundaryTest.RecordingStorageConfig.class)
class ProfilePhotoTransactionBoundaryTest extends ProfileTestSupport {

    private final ProfilePhotoService service;
    private final RecordingStorage storage;
    private final JdbcTemplate jdbcTemplate;

    ProfilePhotoTransactionBoundaryTest(MockMvc mockMvc, UserRepository userRepository, UserService userService,
                                        TokenService tokenService, JdbcTemplate jdbc,
                                        ProfilePhotoService service, RecordingStorage storage) {
        super(mockMvc, userRepository, userService, tokenService, jdbc);
        this.service = service;
        this.storage = storage;
        this.jdbcTemplate = jdbc;
    }

    /** 창고 빈은 컨텍스트에 하나라 시험 사이에 상태가 넘어간다 — 매번 새것으로 시작한다. */
    @BeforeEach
    void resetStorage() {
        storage.reset();
    }

    @Test
    void 창고를_부르는_동안에는_트랜잭션이_열려_있지_않다() {
        User u = newUser();

        service.upload(u.getId(), png());

        assertThat(storage.transactionActiveDuringPut)
                .as("창고 호출이 트랜잭션 안이다 — 커넥션을 쥔 채 최대 8초를 기다리게 된다")
                .isFalse();
        assertThat(userRepository.findById(u.getId()).orElseThrow().getProfilePhotoKey())
                .as("표 갱신은 트랜잭션 안에서 실제로 일어나야 한다")
                .isNotBlank();
    }

    /**
     * 🔴 <b>탈퇴로 거절될 때 방금 쓴 파일을 도로 지우는데, 그 삭제도 외부 HTTP다</b>(PR #149 codex P1).
     *
     * <p>거절은 {@code PhotoAttacher.attach}의 트랜잭션 안에서 나는데 <b>보상 삭제를 거기서 부르면</b>
     * 위 문단의 대가를 그대로 치른다 — 롤백 중인 커넥션을 쥔 채 창고를 기다린다.
     * 그래서 삭제는 그 트랜잭션이 <b>끝난 뒤</b>, 트랜잭션이 없는 {@code ProfilePhotoService}에서 한다.
     *
     * <p>창고 쓰기와 표 갱신 <b>사이</b>에 탈퇴가 커밋된 상태를 만든다 — 앞 관문
     * ({@code currentVersion})은 통과하고 뒤 관문({@code attach})만 걸리는 유일한 순서다.
     */
    @Test
    void 탈퇴로_거절되면_방금_쓴_파일을_트랜잭션_밖에서_지운다() {
        User u = newUser();
        storage.duringPut = () -> jdbcTemplate.update("UPDATE users SET deleted_at = ? WHERE id = ?",
                Timestamp.from(Instant.now()), u.getId());

        assertThatThrownBy(() -> service.upload(u.getId(), png()))
                .as("전제: 탈퇴 뒤에 도착한 표 갱신은 거절돼야 한다")
                .isInstanceOf(AuthException.class);

        assertThat(storage.deleteCalls)
                .as("🔴 거절만 하고 방금 쓴 파일을 안 지웠다 — 아무도 안 가리키는 개인 사진이 창고에 남는다")
                .isEqualTo(1);
        assertThat(storage.transactionActiveDuringDelete)
                .as("보상 삭제가 트랜잭션 안이다 — 그것도 외부 HTTP라 커넥션을 쥔 채 기다리게 된다")
                .isFalse();
    }

    private static MockMultipartFile png() {
        byte[] body = new byte[512];
        System.arraycopy(new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A}, 0, body, 0, 8);
        return new MockMultipartFile("file", "me.png", "image/png", body);
    }

    @TestConfiguration
    static class RecordingStorageConfig {

        /** 진짜 창고(꺼져 있어 NONE이다)를 밀어낸다 — 이 검사가 보는 것은 경계뿐이라 S3가 필요 없다. */
        @Bean
        @Primary
        RecordingStorage recordingStorage() {
            return new RecordingStorage();
        }
    }

    static class RecordingStorage implements PhotoStorage {

        private Boolean transactionActiveDuringPut;
        private Boolean transactionActiveDuringDelete;
        private int deleteCalls;
        /** 창고에 쓰는 <b>동안</b> 벌어질 일. 기본은 아무것도 안 한다. */
        private Runnable duringPut = () -> { };

        void reset() {
            transactionActiveDuringPut = null;
            transactionActiveDuringDelete = null;
            deleteCalls = 0;
            duringPut = () -> { };
        }

        @Override
        public void put(long userId, long version, byte[] bytes, ImageType type) {
            transactionActiveDuringPut = TransactionSynchronizationManager.isActualTransactionActive();
            duringPut.run();
        }

        @Override
        public Optional<StoredPhoto> get(long userId, long version) {
            return Optional.empty();
        }

        @Override
        public void deleteAll(long userId) {
            transactionActiveDuringDelete = TransactionSynchronizationManager.isActualTransactionActive();
            deleteCalls++;
        }
    }
}
