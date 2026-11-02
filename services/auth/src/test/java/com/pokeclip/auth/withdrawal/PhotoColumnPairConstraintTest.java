package com.pokeclip.auth.withdrawal;

import com.pokeclip.auth.support.IntegrationTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 사진 칸 둘이 반쪽만 차는 것을 <b>DB가</b> 막는지 잰다(V111 {@code ck_users_photo_columns_paired}).
 *
 * <p><b>왜 DB에서 재나</b>: 앱 코드로는 반쪽 상태를 만들 수 없다 — {@code attachPhoto}가 둘을 함께
 * 채우고 {@code withdraw}가 함께 비운다. 그래서 이 제약은 <b>앞으로 그 규칙을 모르는 코드</b>를
 * 막으려고 있는 것이고, 재려면 {@code JdbcTemplate}으로 엔티티를 건너뛰어야 한다.
 * 그 반쪽 상태가 실제로 무엇을 깨는지는 POK-207 감사가 재현했다 — {@code /api/auth/me}가
 * 500이 되거나(주소 조립 NPE) 사진이 조용히 사라진다.
 */
class PhotoColumnPairConstraintTest extends IntegrationTestSupport {

    private static final String SUB = "sub-photo-pair-check";

    private final JdbcTemplate jdbc;

    PhotoColumnPairConstraintTest(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 이 클래스가 심은 행만 골라 거둔다. 남기면 다른 클래스의 {@code userRepository.deleteAll()}이
     * 엉뚱한 곳에서 터진다 — SchemaMigrationTest가 같은 이유로 자기 행을 직접 거둔다.
     */
    @BeforeEach
    @AfterEach
    void 심은_행을_거둔다() {
        jdbc.update("DELETE FROM users WHERE google_sub = ?", SUB);
    }

    @Test
    void 파일_이름만_채우면_거부된다() {
        Long userId = 회원을_심는다();

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE users SET profile_photo_key = ? WHERE id = ?",
                "profile-photos/" + userId + "/0", userId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 사진_시각만_채우면_거부된다() {
        Long userId = 회원을_심는다();

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE users SET profile_photo_updated_at = now() WHERE id = ?", userId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** 짝을 맞추면 통과한다 — 제약이 정상 저장까지 막고 있으면 이 갈래가 빨간불이 된다. */
    @Test
    void 둘_다_채우면_저장된다() {
        Long userId = 회원을_심는다();

        assertThatCode(() -> jdbc.update(
                "UPDATE users SET profile_photo_key = ?, profile_photo_updated_at = now() WHERE id = ?",
                "profile-photos/" + userId + "/0", userId))
                .doesNotThrowAnyException();

        Integer paired = jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE id = ? "
                        + "AND profile_photo_key IS NOT NULL AND profile_photo_updated_at IS NOT NULL",
                Integer.class, userId);
        assertThat(paired).isEqualTo(1);
    }

    /** 둘 다 비우는 것도 짝이다 — 탈퇴가 실제로 하는 일이 이것이다. */
    @Test
    void 둘_다_비우면_저장된다() {
        Long userId = 회원을_심는다();
        jdbc.update("UPDATE users SET profile_photo_key = ?, profile_photo_updated_at = now() WHERE id = ?",
                "profile-photos/" + userId + "/0", userId);

        assertThatCode(() -> jdbc.update(
                "UPDATE users SET profile_photo_key = NULL, profile_photo_updated_at = NULL WHERE id = ?",
                userId))
                .doesNotThrowAnyException();
    }

    /**
     * 행을 여기서 직접 심는다. {@code SELECT ... LIMIT 1}로 남의 행을 빌리면 users가 비었을 때
     * UPDATE가 0행으로 조용히 끝나 <b>예외가 안 나고 거짓 실패</b>한다 — SchemaMigrationTest가
     * 같은 함정에 주석을 달아 뒀다.
     */
    private Long 회원을_심는다() {
        return jdbc.queryForObject(
                "INSERT INTO users (google_sub, email, name, created_at, updated_at) "
                        + "VALUES (?, ?, '사진짝검증', now(), now()) RETURNING id",
                Long.class, SUB, UUID.randomUUID() + "@example.com");
    }
}
