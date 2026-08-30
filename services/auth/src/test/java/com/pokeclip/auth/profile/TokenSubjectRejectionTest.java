package com.pokeclip.auth.profile;

import com.pokeclip.auth.AuthException;
import com.pokeclip.auth.AuthFailure;
import com.pokeclip.auth.api.AuthController;
import com.pokeclip.auth.api.dto.UpdateNameRequest;
import com.pokeclip.auth.profile.api.ProfilePhotoController;
import com.pokeclip.auth.token.TokenService;
import com.pokeclip.auth.user.UserRepository;
import com.pokeclip.auth.user.UserService;
import com.pokeclip.auth.withdrawal.api.WithdrawalController;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <b>회원 번호를 토큰에서 꺼내면서 감싼 자리가 셋이다</b> — 이름을 고치는 창구, 사진을 올리는 창구,
 * 그리고 계정을 지우는 창구(POK-171). 셋이 같은 입력을 다르게 다루면 안 된다.
 *
 * <p>🔴 <b>감싼 셋이 전부는 아니다.</b> auth에서 {@code Long.valueOf(jwt.getSubject())}를 하는 자리는
 * <b>열</b>이고 나머지 일곱은 아직 안 감쌌다 — auth/CLAUDE.md 「알려진 구멍」 22에 전수로 적혀 있다.
 * 여기가 초록인 것을 <b>「쌍둥이를 다 맞췄다」로 읽으면 그 일곱이 영영 안 보인다.</b>
 *
 * <p>오늘은 어느 쪽도 도달하지 않는다. 우리 발급기는 {@code sub}에 항상 회원 번호를 넣고,
 * 서명 검증을 통과한 토큰만 여기까지 온다. <b>그래서 더 갈라지기 쉬운 자리다</b> —
 * 아무도 안 밟으니 한쪽만 고쳐져도 시험이 빨간불이 되지 않는다.
 *
 * <p>셋을 <b>한 파일에 나란히</b> 두는 것이 이 검사의 요지다. 사진 창구만 재면 이름 창구가
 * 나중에 조용히 갈라지고, 그 반대도 같다. 새 창구가 이 감싸는 모양을 쓰면 여기에 한 건을 더한다.
 *
 * <p>창구를 직접 부른다 — 서명이 유효하면서 {@code sub}만 숫자가 아닌 토큰은 발급기로 만들 수 없다.
 */
class TokenSubjectRejectionTest extends PhotoTestSupport {

    private final ProfilePhotoController photoController;
    private final AuthController authController;
    private final WithdrawalController withdrawalController;

    TokenSubjectRejectionTest(MockMvc mockMvc, UserRepository userRepository, UserService userService,
                              TokenService tokenService, JdbcTemplate jdbc,
                              ProfilePhotoController photoController, AuthController authController,
                              WithdrawalController withdrawalController) {
        super(mockMvc, userRepository, userService, tokenService, jdbc);
        this.photoController = photoController;
        this.authController = authController;
        this.withdrawalController = withdrawalController;
    }

    private static Jwt subjectOf(String sub) {
        return Jwt.withTokenValue("검증은_이미_끝난_뒤다")
                .header("alg", "HS256")
                .subject(sub)
                .build();
    }

    @Test
    void 사진_창구는_토큰의_주체를_못_읽으면_401로_거절한다() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "me.png", "image/png", new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47});

        assertThatThrownBy(() -> photoController.upload(subjectOf("숫자가-아니다"), file))
                .as("감싸지 않으면 NumberFormatException이 그대로 올라가 500이 된다")
                .isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).failure())
                .isEqualTo(AuthFailure.ACCESS_TOKEN_SUBJECT_INVALID);
    }

    @Test
    void 이름_창구도_같은_사유로_거절한다() {
        assertThatThrownBy(() -> authController.updateName(subjectOf("숫자가-아니다"), new UpdateNameRequest("김태현")))
                .isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).failure())
                .isEqualTo(AuthFailure.ACCESS_TOKEN_SUBJECT_INVALID);
    }

    /**
     * 탈퇴 창구도 같아야 한다. <b>여기가 갈리면 대가가 가장 크다</b> — 지우는 창구가 500을 내면
     * 화면은 「무슨 일이 났는지 모르겠다」만 보고 사용자는 계정이 지워졌는지 아닌지를 알 수 없다.
     */
    @Test
    void 탈퇴_창구도_같은_사유로_거절한다() {
        assertThatThrownBy(() -> withdrawalController.withdraw(subjectOf("숫자가-아니다")))
                .isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).failure())
                .isEqualTo(AuthFailure.ACCESS_TOKEN_SUBJECT_INVALID);
    }

}
