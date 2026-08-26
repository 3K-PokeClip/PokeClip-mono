package com.pokeclip.auth.profile;

import com.pokeclip.auth.support.PhotoLocalStackFixture;
import com.pokeclip.auth.token.TokenService;
import com.pokeclip.auth.user.User;
import com.pokeclip.auth.user.UserRepository;
import com.pokeclip.auth.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 사진 올리기 종단 — 진짜 가짜 창고(LocalStack)에 실제로 넣고 꺼낸다.
 *
 * <p>🔴 <b>「2MB 초과 → 413」은 여기 없다.</b> MockMvc는 이미 파싱된 요청을 넣어 DispatcherServlet이
 * 재파싱을 건너뛰므로 {@code spring.servlet.multipart.max-file-size}가 아예 안 걸린다 —
 * 여기에 쓰면 상한을 지워도 초록이다. {@code ProfilePhotoSizeLimitTest}가 진짜 톰캣으로 잰다.
 */
class ProfilePhotoUploadTest extends PhotoTestSupport {

    private final PhotoStorage storage;

    ProfilePhotoUploadTest(MockMvc mockMvc, UserRepository userRepository, UserService userService,
                           TokenService tokenService, JdbcTemplate jdbc, PhotoStorage storage) {
        super(mockMvc, userRepository, userService, tokenService, jdbc);
        this.storage = storage;
    }

    private static MockMultipartFile png(String filename) {
        byte[] body = new byte[512];
        System.arraycopy(new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A}, 0, body, 0, 8);
        return new MockMultipartFile("file", filename, "image/png", body);
    }

    @Test
    void 사진을_올리면_창고에_들어가고_표가_가리킨다() throws Exception {
        User u = newUser();
        assertThat(u.getProfileImageUrl()).as("가입 때 받은 구글 주소").isNotBlank();

        upload(u, png("me.png"));
        // 응답의 profileImageUrl은 여기서 단언하지 않는다. 태스크 7 전까지 그 칸은 null이다.

        User reloaded = userRepository.findById(u.getId()).orElseThrow();
        assertThat(reloaded.getProfilePhotoKey()).isEqualTo("profile-photos/" + u.getId());
        assertThat(reloaded.getProfilePhotoUpdatedAt()).isNotNull();
        assertThat(reloaded.getProfileImageUrl())
                .as("올린 순간 구글 주소를 비운다 — 되돌리기가 비목표라 영영 안 읽힌다")
                .isNull();
        assertThat(PhotoLocalStackFixture.downloadPhoto(u.getId()))
                .as("창고에 실제로 들어갔는가 — 우리 코드를 안 거치고 확인한다")
                .get().asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.BYTE_ARRAY)
                .hasSize(512);
    }

    /**
     * 🔴 <b>사진 경로의 {@code updated_at}이 아무 데도 안 재어져 있었다</b>(최종 감사).
     * {@code User.attachPhoto}의 {@code this.updatedAt = now;}를 지워도 587건이 전부 초록이었다 —
     * 그 칸을 단언하는 시험이 {@code UpdateNameTest} 하나뿐이라 <b>이름 경로만 재고 있었다.</b>
     * PRD 성공 기준 8번(「{@code updated_at}이 실제로 갱신된다」)이 반만 닫혀 있던 자리다.
     *
     * <p>앞뒤 모두 표에서 읽는다 — 만들 때의 값은 나노초까지 있고 표는 마이크로초로 자르므로,
     * 한쪽만 메모리 값으로 비교하면 「안 움직였는데 참」이 나올 수 있다.
     */
    @Test
    void 사진을_올려도_수정일시가_갱신된다() throws Exception {
        User u = newUser();
        Instant before = userRepository.findById(u.getId()).orElseThrow().getUpdatedAt();

        upload(u, png("me.png"));

        assertThat(userRepository.findById(u.getId()).orElseThrow().getUpdatedAt())
                .as("이름 수정과 같은 규칙이다 — 사진도 프로필 변경이다")
                .isAfter(before);
    }

    @Test
    void 다시_올리면_같은_이름을_덮어쓴다() throws Exception {
        User u = newUser();
        upload(u, png("first.png"));
        String keyAfterFirst = userRepository.findById(u.getId()).orElseThrow().getProfilePhotoKey();
        upload(u, png("second.png"));
        String keyAfterSecond = userRepository.findById(u.getId()).orElseThrow().getProfilePhotoKey();

        assertThat(keyAfterFirst).as("첫 업로드가 표에 아무것도 안 남기면 둘 다 null이라 아래 비교가 저절로 참이 된다").isNotNull();
        assertThat(keyAfterSecond)
                .as("이름이 같아야 주인 없는 파일이 생길 수가 없다 — 탈퇴(POK-171)가 지울 것도 하나다")
                .isEqualTo(keyAfterFirst);
        assertThat(PhotoLocalStackFixture.downloadPhoto(u.getId())).isPresent();
    }

    @Test
    void 그림이_아니면_거부한다() throws Exception {
        User u = newUser();
        // 이름표는 image/png인데 내용은 실행 파일이다
        var fake = new MockMultipartFile("file", "evil.png", "image/png", new byte[]{0x4D, 0x5A, 0x00, 0x00});
        mockMvc.perform(multipart("/api/auth/me/photo").file(fake)
                        .header("Authorization", bearer(u))
                        .with(r -> {
                            r.setMethod("PUT");
                            return r;
                        }))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.reason").value("PHOTO_NOT_AN_IMAGE"));

        assertThat(PhotoLocalStackFixture.downloadPhoto(u.getId()))
                .as("거부한 것이 창고에 남으면 안 된다 — 판정보다 저장이 먼저면 그렇게 된다")
                .isEmpty();
        assertThat(userRepository.findById(u.getId()).orElseThrow().getProfilePhotoKey()).isNull();
    }

    @Test
    void 토큰이_없으면_거부한다() throws Exception {
        mockMvc.perform(multipart("/api/auth/me/photo").file(png("me.png"))
                        .with(r -> {
                            r.setMethod("PUT");
                            return r;
                        }))
                .andExpect(status().isUnauthorized());
    }

    /**
     * 꺼낼 때 붙는 형식은 <b>우리가 판정해 넣어 둔 값</b>이다 — 올린 쪽이 밝힌 이름표가 아니다.
     * 태스크 6이 이 값을 그대로 응답 헤더에 싣는다.
     */
    @Test
    void 꺼내면_우리가_판정한_형식이_붙어_있다() throws Exception {
        User u = newUser();
        upload(u, png("me.png"));

        StoredPhoto stored = storage.get(u.getId()).orElseThrow();
        assertThat(stored.contentType()).isEqualTo("image/png");
        assertThat(stored.bytes()).hasSize(512);
    }

    /** 없는 사진은 빈손이다 — 창고가 던지는 NoSuchKey를 그대로 흘리면 태스크 6이 500을 낸다. */
    @Test
    void 없는_사진을_꺼내면_빈손이다() {
        assertThat(storage.get(999_999_999L)).isEmpty();
    }

    /** 탈퇴(POK-171)가 부를 자리다. 지운 뒤에는 빈손이어야 한다. */
    @Test
    void 지우면_창고에서_사라진다() throws Exception {
        User u = newUser();
        upload(u, png("me.png"));

        storage.delete(u.getId());

        assertThat(PhotoLocalStackFixture.downloadPhoto(u.getId())).isEmpty();
    }

    private void upload(User u, MockMultipartFile file) throws Exception {
        mockMvc.perform(multipart("/api/auth/me/photo").file(file)
                .header("Authorization", bearer(u))
                .with(r -> {
                    r.setMethod("PUT");
                    return r;
                })).andExpect(status().isOk());
    }
}
