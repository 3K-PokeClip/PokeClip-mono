package com.pokeclip.auth.profile;

import com.pokeclip.auth.AuthException;
import com.pokeclip.auth.support.PhotoLocalStackFixture;
import com.pokeclip.auth.token.TokenService;
import com.pokeclip.auth.user.User;
import com.pokeclip.auth.user.UserRepository;
import com.pokeclip.auth.user.UserService;
import com.pokeclip.auth.withdrawal.WithdrawalCleanupExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 🔴 <b>탈퇴가 창고 호출 「도중에」 커밋되는 갈래</b>를 결정적으로 재현한다(PR #148 codex C2).
 *
 * <p>이 창이 넷 중 가장 넓다 — {@code storage.put}이 외부 HTTP라 <b>최대 8초</b>다
 * ({@code PhotoStorage} javadoc). 그동안 탈퇴가 커밋되면 그 뒤에 표 갱신이 도착한다.
 *
 * <p><b>왜 별도 클래스인가</b> — {@code WithdrawnWriteGuardPhotoTest}는 「탈퇴가 이미 끝난 뒤에
 * 도착한 업로드」를 재고, 그것은 창고 호출 <b>앞</b>의 관문({@code PhotoAttacher.currentVersion})에서
 * 걸린다. 그 관문만 있으면 <b>여기 이 갈래는 그대로 뚫린다</b> — 앞에서 볼 때는 살아있었기 때문이다.
 * <b>표 갱신 직전의 관문({@code attach})을 재는 것은 이 클래스뿐이다.</b>
 *
 * <p>창고를 <b>진짜로</b> 부르되 그 안에서 탈퇴를 커밋시킨다({@code PhotoReplacementFailureTest}가
 * 표 갱신만 실패시킨 것과 같은 수법). 시간에 안 기대므로 기계가 바빠도 결과가 안 흔들린다.
 */
class PhotoUploadDuringWithdrawalTest extends PhotoTestSupport {

    private static final byte[] PNG_MAGIC = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

    private final PhotoStorage storage;
    private final PhotoProperties properties;
    private final PhotoAttacher attacher;
    private final JdbcTemplate jdbcTemplate;
    private final WithdrawalCleanupExecutor cleanup;

    PhotoUploadDuringWithdrawalTest(MockMvc mockMvc, UserRepository userRepository, UserService userService,
                                    TokenService tokenService, JdbcTemplate jdbc,
                                    PhotoStorage storage, PhotoProperties properties,
                                    PhotoAttacher attacher, WithdrawalCleanupExecutor cleanup) {
        super(mockMvc, userRepository, userService, tokenService, jdbc);
        this.storage = storage;
        this.properties = properties;
        this.attacher = attacher;
        this.jdbcTemplate = jdbc;
        this.cleanup = cleanup;
    }

    @Test
    void 창고에_쓰는_동안_탈퇴가_커밋되면_표를_안_바꾼다() {
        User user = newUser();

        assertThatThrownBy(() -> serviceThatWithdrawsMidUpload(user.getId())
                .upload(user.getId(), png("during.png")))
                .as("🔴 탈퇴 표시를 넘어 표 갱신이 끝났다 — 응답만 성공이 아니라 표가 실제로 바뀐다")
                .isInstanceOf(AuthException.class);

        assertThat(photoKey(user))
                .as("🔴 탈퇴한 계정의 사진 칸이 채워졌다 — 정리는 이미 지나갔으므로 아무도 안 지운다")
                .isNull();
    }

    /**
     * 🔴 <b>같은 창인데 순서가 한 칸 다르다 — 창고 쓰기가 정리보다 <u>늦게</u> 끝난다</b>
     * (PR #149 codex P1, 재현함).
     *
     * <p>위 시험은 표만 본다. 그 갈래에서도 파일은 남는데 <b>탈퇴 정리가 아직 안 지나갔으면</b>
     * 정리가 곧 와서 그 파일을 함께 지운다({@code PhotoStorage.deleteAll}이 자리 둘을 다 지운다).
     * 그래서 조용히 넘어간다.
     *
     * <p><b>정리가 먼저 끝나 버리면 그 회수 장치가 통째로 사라진다.</b> 탈퇴자에게는
     * 「다음 업로드가 반대 자리를 덮어쓴다」도 안 돈다(다음 업로드가 없다) — 즉 <b>아무도
     * 안 가리키는 개인 사진이 창고에 영구히 남는다.</b> 응답은 401이고 표는 비어 있어
     * <b>표를 아무리 뒤져도 「지웠다」로 보인다.</b>
     *
     * <p>정리를 <b>진짜로</b> 돌린다 — 창구로 탈퇴시키고 {@code awaitIdle}로 전용 스레드가
     * 끝나기를 기다린 뒤에 {@code storage.put}을 흘려보낸다. 시간에 안 기대므로 결정적이다.
     * (전용 스레드라 안 기다리면 정리가 나중에 와서 우리 파일까지 지워 <b>고치기 전에도 초록</b>이 된다.)
     */
    @Test
    void 창고에_쓴_것이_정리보다_늦게_끝나면_그_파일을_지운다() {
        User user = newUser();
        new ProfilePhotoService(storage, attacher, properties)
                .upload(user.getId(), png("before.png"));

        AtomicReference<Optional<byte[]>> 정리직후 = new AtomicReference<>();

        assertThatThrownBy(() -> serviceThatCleansUpBeforePut(user, 정리직후)
                .upload(user.getId(), png("late.png")))
                .as("전제: 탈퇴 뒤에 도착한 표 갱신은 거절돼야 한다")
                .isInstanceOf(AuthException.class);

        assertThat(정리직후.get())
                .as("전제: 정리가 창고를 비운 뒤에 put이 끝나야 한다 — 안 비었으면 아래가 아무것도 안 잰다")
                .isEmpty();

        assertThat(PhotoLocalStackFixture.downloadAnyPhoto(user.getId()))
                .as("🔴 정리가 지나간 뒤 업로드가 쓴 사진이 창고에 남았다 — 아무도 안 가리키므로 조용하다")
                .isEmpty();
    }

    /**
     * 창고 쓰기 <b>앞</b>에서 탈퇴를 끝까지(정리 스레드까지) 돌린다. 그러면 {@code storage.put}은
     * {@code deleteAll} <b>뒤에</b> 끝나므로, 지워진 자리에 파일이 하나 다시 생긴다.
     *
     * <p>여기는 {@code deleted_at}을 손으로 찍지 않고 <b>창구를 부른다</b> — 재려는 것이
     * 「가드가 막나」가 아니라 「정리와 창고 쓰기의 <b>순서</b>」라서, 정리 잡이 실제로 돌아야 한다.
     */
    private ProfilePhotoService serviceThatCleansUpBeforePut(User user,
                                                             AtomicReference<Optional<byte[]>> 정리직후) {
        PhotoStorage lateStorage = new PhotoStorage() {
            @Override
            public void put(long id, long version, byte[] bytes, ImageType type) {
                withdrawAndAwaitCleanup(user);
                정리직후.set(PhotoLocalStackFixture.downloadAnyPhoto(id));
                storage.put(id, version, bytes, type);
            }

            @Override
            public Optional<StoredPhoto> get(long id, long version) {
                return storage.get(id, version);
            }

            @Override
            public void deleteAll(long id) {
                storage.deleteAll(id);
            }
        };
        return new ProfilePhotoService(lateStorage, attacher, properties);
    }

    /** 창구로 탈퇴시키고 <b>전용 스레드의 정리가 끝날 때까지</b> 기다린다. */
    private void withdrawAndAwaitCleanup(User user) {
        try {
            mockMvc.perform(delete("/api/auth/me").header("Authorization", bearer(user)))
                    .andExpect(status().isNoContent());
        } catch (Exception e) {
            throw new IllegalStateException("탈퇴 창구 호출 실패", e);
        }
        assertThat(cleanup.awaitIdle(Duration.ofSeconds(20)))
                .as("정리 잡이 시한 안에 안 끝났다 — 아래가 「아직 안 지웠다」를 보는 것이 된다")
                .isTrue();
    }

    /**
     * 창고 호출이 <b>진짜로 일어난 뒤</b> 탈퇴를 커밋시킨다. 여기서 익명화까지 흉내낼 필요는 없다 —
     * 가드가 보는 것은 {@code deleted_at} 하나이고, 그것이 탈퇴의 유일한 근거다(V111).
     */
    private ProfilePhotoService serviceThatWithdrawsMidUpload(long userId) {
        PhotoStorage withdrawingStorage = new PhotoStorage() {
            @Override
            public void put(long id, long version, byte[] bytes, ImageType type) {
                storage.put(id, version, bytes, type);
                jdbcTemplate.update("UPDATE users SET deleted_at = ? WHERE id = ?",
                        Timestamp.from(Instant.now()), userId);
            }

            @Override
            public Optional<StoredPhoto> get(long id, long version) {
                return storage.get(id, version);
            }

            @Override
            public void deleteAll(long id) {
                storage.deleteAll(id);
            }
        };
        return new ProfilePhotoService(withdrawingStorage, attacher, properties);
    }

    private String photoKey(User user) {
        return jdbcTemplate.queryForObject(
                "SELECT profile_photo_key FROM users WHERE id = ?", String.class, user.getId());
    }

    private static MockMultipartFile png(String filename) {
        byte[] body = new byte[64];
        System.arraycopy(PNG_MAGIC, 0, body, 0, PNG_MAGIC.length);
        return new MockMultipartFile("file", filename, "image/png", body);
    }
}
