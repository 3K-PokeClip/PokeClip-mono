package com.pokeclip.auth.withdrawal;

import com.pokeclip.auth.AuthException;
import com.pokeclip.auth.profile.ProfilePhotoService;
import com.pokeclip.auth.support.PhotoLocalStackFixture;
import com.pokeclip.auth.token.TokenService;
import com.pokeclip.auth.user.User;
import com.pokeclip.auth.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <b>탈퇴 정리가 끝난 뒤에 도착한 사진 업로드</b>를 잰다(PR #148 codex C2).
 *
 * <p>감사가 이 상태를 재현했다 — 탈퇴 → 정리 완료 → 창고 비었음을 확인한 뒤 업로드가 도착하자
 * <b>파일이 다시 생기고 표의 사진 칸이 다시 채워졌으며 그 주소가 밖에서 200</b>이었다.
 * {@code WithdrawalCleanupTest.탈퇴_전에_받아_둔_사진_주소는_404가_된다}가 세운 불변식이
 * 이 경로에서 깨진다.
 *
 * <p>🔴 <b>표만 재면 절반이다.</b> 사진은 표 밖에도 남는다 — 표를 막아도 {@code storage.put}이
 * 이미 끝났으면 <b>주인 없는 개인 파일</b>이 창고에 남고, 「자리 둘을 다음 업로드가 덮어쓴다」는
 * 회수 장치는 <b>탈퇴자에게 영영 안 돈다</b>(다음 업로드가 없다). 그래서 둘을 나란히 센다.
 *
 * <p>창구가 아니라 서비스를 직접 부른다 — 창구로 부르면 입구 필터가 막아 가드가 없어도 초록이다.
 */
class WithdrawnWriteGuardPhotoTest extends WithdrawalTestSupport {

    private static final byte[] PNG_MAGIC = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

    private final ProfilePhotoService photoService;
    private final WithdrawalCleanupExecutor cleanup;

    WithdrawnWriteGuardPhotoTest(MockMvc mockMvc, UserService userService, TokenService tokenService,
                                 JdbcTemplate jdbc, ProfilePhotoService photoService,
                                 WithdrawalCleanupExecutor cleanup) {
        super(mockMvc, userService, tokenService, jdbc);
        this.photoService = photoService;
        this.cleanup = cleanup;
    }

    @DynamicPropertySource
    static void photoProperties(DynamicPropertyRegistry registry) {
        PhotoLocalStackFixture.register(registry);
    }

    @Test
    void 탈퇴_뒤_도착한_사진_업로드는_표를_안_바꾼다() throws Exception {
        User user = newUser();
        photoService.upload(user.getId(), png("before.png"));
        assertThat(photoKey(user)).as("전제: 올려 둔 사진이 있다").isNotNull();

        withdraw(user);
        assertThat(photoKey(user)).as("전제: 탈퇴가 표의 사진 칸을 비웠다").isNull();

        assertThatThrownBy(() -> photoService.upload(user.getId(), png("after.png")))
                .isInstanceOf(AuthException.class);

        assertThat(photoKey(user))
                .as("🔴 탈퇴한 계정의 사진 칸이 다시 채워졌다 — 그 주소는 밖에서 200이 된다")
                .isNull();
    }

    /**
     * 🔴 <b>표가 안 채워졌다고 파기가 끝난 것이 아니다.</b> 창고에 파일이 남으면 표에서는 안 보이는데
     * 개인 사진이 그대로 있는 상태다 — 「지웠다」가 거짓이 되는 자리가 바로 여기다.
     */
    @Test
    void 탈퇴_뒤_도착한_사진_업로드는_창고에도_안_남긴다() throws Exception {
        User user = newUser();
        photoService.upload(user.getId(), png("before.png"));

        withdraw(user);
        assertThat(PhotoLocalStackFixture.downloadAnyPhoto(user.getId()))
                .as("전제: 정리가 창고를 비웠다 — 안 비었으면 아래가 아무것도 안 잰다")
                .isEmpty();

        assertThatThrownBy(() -> photoService.upload(user.getId(), png("after.png")))
                .isInstanceOf(AuthException.class);

        assertThat(PhotoLocalStackFixture.downloadAnyPhoto(user.getId()))
                .as("🔴 탈퇴한 계정의 사진 파일이 창고에 다시 생겼다 — 아무도 안 가리키므로 조용하다")
                .isEmpty();
    }

    // ── 도구 ────────────────────────────────────────────────────────────

    private void withdraw(User user) throws Exception {
        mockMvc.perform(delete("/api/auth/me").header("Authorization", bearer(user)))
                .andExpect(status().isNoContent());
        assertThat(cleanup.awaitIdle(Duration.ofSeconds(20)))
                .as("정리 잡이 시한 안에 안 끝났다 — 아래 단언이 「아직 안 지웠다」를 보는 것이 된다")
                .isTrue();
    }

    private String photoKey(User user) {
        return jdbc.queryForObject(
                "SELECT profile_photo_key FROM users WHERE id = ?", String.class, user.getId());
    }

    private static MockMultipartFile png(String filename) {
        byte[] body = new byte[64];
        System.arraycopy(PNG_MAGIC, 0, body, 0, PNG_MAGIC.length);
        return new MockMultipartFile("file", filename, "image/png", body);
    }
}
