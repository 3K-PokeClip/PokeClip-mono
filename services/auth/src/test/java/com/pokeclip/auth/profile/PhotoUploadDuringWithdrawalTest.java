package com.pokeclip.auth.profile;

import com.pokeclip.auth.AuthException;
import com.pokeclip.auth.token.TokenService;
import com.pokeclip.auth.user.User;
import com.pokeclip.auth.user.UserRepository;
import com.pokeclip.auth.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    PhotoUploadDuringWithdrawalTest(MockMvc mockMvc, UserRepository userRepository, UserService userService,
                                    TokenService tokenService, JdbcTemplate jdbc,
                                    PhotoStorage storage, PhotoProperties properties,
                                    PhotoAttacher attacher) {
        super(mockMvc, userRepository, userService, tokenService, jdbc);
        this.storage = storage;
        this.properties = properties;
        this.attacher = attacher;
        this.jdbcTemplate = jdbc;
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
