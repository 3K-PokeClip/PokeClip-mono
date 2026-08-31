package com.pokeclip.auth.withdrawal;

import com.pokeclip.auth.support.IntegrationTestSupport;
import com.pokeclip.auth.user.User;
import com.pokeclip.auth.user.UserRepository;
import com.pokeclip.auth.user.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code User.withdraw}가 회원 행을 알아볼 수 없게 바꾸는지 잰다.
 *
 * <p><b>통합 테스트인 이유</b>: {@code User.of}가 패키지 전용이라 이 패키지에서는 못 부르고,
 * 무엇보다 익명화한 값이 <b>회원 번호로 갈린다</b>는 것이 이 메서드의 핵심인데 번호는 DB가 준다.
 * 순수 단위 테스트로 만들려면 번호를 리플렉션으로 심어야 하고, 그러면 <b>실제로 쓰이는 번호가 아닌
 * 값</b>을 재게 된다.
 */
class UserWithdrawTest extends IntegrationTestSupport {

    private static final Instant NOW = Instant.parse("2026-08-31T12:00:00Z");

    private final UserService userService;
    private final UserRepository userRepository;
    private final JdbcTemplate jdbc;

    UserWithdrawTest(UserService userService, UserRepository userRepository, JdbcTemplate jdbc) {
        this.userService = userService;
        this.userRepository = userRepository;
        this.jdbc = jdbc;
    }

    /** 이 클래스가 심은 회원 번호. 아래 거두기가 이것으로만 지운다. */
    private final List<Long> 심은_회원 = new ArrayList<>();

    /**
     * 이 클래스가 심은 행만 골라 거둔다. {@code userRepository.deleteAll()}을 쓰지 않는 이유는
     * 그것이 <b>다른 클래스가 남긴 행까지</b> 지우려 들어 자식 표의 외래키에 걸리기 때문이다
     * (services/CLAUDE.md 「자식 테이블 행을 남기는 테스트」).
     *
     * <p>🔴 <b>이름이 아니라 번호로 지운다.</b> 처음에는 {@code google_sub LIKE 'sub-withdraw-%'}로
     * 지웠는데, {@code withdraw}가 바로 그 칸을 {@code withdrawn:<번호>}로 바꾸므로
     * <b>탈퇴시킨 행은 하나도 안 걸렸다.</b> 고정 문자열 주입(회원 번호를 안 섞는 결함)을 넣었을 때
     * 남은 행이 다음 시험의 유일 제약에 걸려 드러났다 — 번호는 안 바뀌므로 이 함정이 없다.
     */
    @AfterEach
    void 심은_행을_거둔다() {
        for (Long id : 심은_회원) {
            jdbc.update("DELETE FROM users WHERE id = ?", id);
        }
        심은_회원.clear();
    }

    @Test
    void 탈퇴하면_이메일에_옛_주소가_안_남는다() {
        User user = newUser("golden-retriever@example.com");

        user.withdraw(NOW);

        assertThat(user.getEmail())
                .doesNotContain("golden-retriever")
                .doesNotContain("example.com")
                .contains(String.valueOf(user.getId()));
    }

    @Test
    void 탈퇴하면_이름이_바뀐다() {
        User user = newUser("name-check@example.com");
        String before = user.getName();

        user.withdraw(NOW);

        assertThat(user.getName()).isNotEqualTo(before).isEqualTo("탈퇴한 사용자");
    }

    @Test
    void 탈퇴하면_구글_식별자가_바뀐다() {
        User user = newUser("sub-check@example.com");
        String before = user.getGoogleSub();

        user.withdraw(NOW);

        assertThat(user.getGoogleSub())
                .isNotEqualTo(before)
                .contains(String.valueOf(user.getId()));
    }

    @Test
    void 탈퇴하면_구글_사진_주소가_빈다() {
        User user = newUser("google-photo@example.com");
        assertThat(user.getProfileImageUrl()).isNotNull();

        user.withdraw(NOW);

        assertThat(user.getProfileImageUrl()).isNull();
    }

    /**
     * 사진 칸 둘은 한 문장에서 함께 빈다. 하나만 비우면 V111의 CHECK 제약이 저장을 거부하므로
     * <b>메모리 값뿐 아니라 표까지 왕복해서</b> 잰다 — 엔티티만 보면 「저장은 되나」를 못 본다.
     */
    @Test
    void 탈퇴하면_사진_칸_둘이_함께_빈다() {
        User user = newUser("photo-pair@example.com");
        user.attachPhoto("profile-photos/" + user.getId() + "/0", NOW.minusSeconds(60));
        userRepository.saveAndFlush(user);

        user.withdraw(NOW);
        userRepository.saveAndFlush(user);

        assertThat(user.getProfilePhotoKey()).isNull();
        assertThat(user.getProfilePhotoUpdatedAt()).isNull();
        assertThat(user.hasUploadedPhoto()).isFalse();

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT profile_photo_key, profile_photo_updated_at FROM users WHERE id = ?", user.getId());
        assertThat(row.get("profile_photo_key")).isNull();
        assertThat(row.get("profile_photo_updated_at")).isNull();
    }

    @Test
    void 탈퇴하면_탈퇴_시각이_찍힌다() {
        User user = newUser("stamp@example.com");
        assertThat(user.isWithdrawn()).isFalse();

        user.withdraw(NOW);
        userRepository.saveAndFlush(user);

        assertThat(user.getDeletedAt()).isEqualTo(NOW);
        assertThat(user.isWithdrawn()).isTrue();
        assertThat(user.getUpdatedAt()).isEqualTo(NOW);

        Instant persisted = jdbc.queryForObject(
                "SELECT deleted_at FROM users WHERE id = ?", Instant.class, user.getId());
        assertThat(persisted).isEqualTo(NOW);
    }

    /**
     * 고정 문자열을 쓰면 두 번째 탈퇴자의 저장이 유일 제약에 걸린다 — {@code google_sub}는 V101이,
     * {@code email}은 V108이 유일로 묶었다. <b>값이 다르다는 것만 재면 부족하다</b>: 실제로 둘 다
     * 커밋되는 것까지 봐야 「저장이 거부되지 않는다」가 재어진다.
     */
    @Test
    void 회원_번호가_다르면_바뀐_이메일도_구글_식별자도_다르다() {
        User first = newUser("first@example.com");
        User second = newUser("second@example.com");
        assertThat(first.getId()).isNotEqualTo(second.getId());

        first.withdraw(NOW);
        second.withdraw(NOW);
        userRepository.saveAndFlush(first);
        userRepository.saveAndFlush(second);

        assertThat(first.getEmail()).isNotEqualTo(second.getEmail());
        assertThat(first.getGoogleSub()).isNotEqualTo(second.getGoogleSub());

        Integer stored = jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE id IN (?, ?) AND deleted_at IS NOT NULL",
                Integer.class, first.getId(), second.getId());
        assertThat(stored).isEqualTo(2);
    }

    /**
     * 태스크 3의 차단 필터가 <b>인증이 필요한 모든 요청</b>에서 부를 조회다. 계약이 셋인데
     * 셋 다 「빈손」과 「값」의 구분에 걸려 있어 여기서 못박는다 — 특히 <b>없는 회원 번호가
     * 예외가 아니라 빈손</b>이라는 것. 필터는 그것을 「막지 않음」으로 다룬다.
     */
    @Test
    void 살아있는_회원의_탈퇴_시각은_빈손이다() {
        User user = newUser("alive@example.com");

        assertThat(userRepository.findDeletedAtById(user.getId())).isEmpty();
    }

    @Test
    void 탈퇴한_회원의_탈퇴_시각을_돌려준다() {
        User user = newUser("withdrawn-read@example.com");
        user.withdraw(NOW);
        userRepository.saveAndFlush(user);

        assertThat(userRepository.findDeletedAtById(user.getId())).contains(NOW);
    }

    /** 없는 번호는 예외가 아니라 빈손이다 — 살아있는 회원과 같은 결과가 된다. */
    @Test
    void 없는_회원_번호는_예외가_아니라_빈손이다() {
        assertThat(userRepository.findDeletedAtById(-1L)).isEmpty();
    }

    /**
     * 이메일에도 유일 제약이 있어(V108) 주소를 흩지 않으면 다른 테스트와 부딪힌다.
     * 구글 사진 주소를 채워 두는 것은 「비웠다」와 「원래 비어 있었다」를 가르기 위해서다.
     */
    private User newUser(String emailHint) {
        String unique = UUID.randomUUID().toString();
        User user = userService.findOrCreate(
                "sub-withdraw-" + unique,
                unique + "-" + emailHint,
                "김태현",
                "https://lh3.googleusercontent.com/" + unique);
        심은_회원.add(user.getId());
        return user;
    }
}
