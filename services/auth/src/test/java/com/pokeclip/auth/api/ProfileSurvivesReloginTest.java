package com.pokeclip.auth.api;

import com.pokeclip.auth.profile.ProfileTestSupport;
import com.pokeclip.auth.token.TokenService;
import com.pokeclip.auth.user.User;
import com.pokeclip.auth.user.UserRepository;
import com.pokeclip.auth.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 회귀 그물. 카드는 「지금은 로그인할 때마다 구글 값을 넣을 수 있는 구조」라 쓰지만 코드는 이미 안 넣는다
 * ({@code UserService.findOrCreate}, POK-53). 이 카드의 몫은 <b>그것을 규칙으로 굳히는 것</b>이다 —
 * 바꿔 둔 이름이 다음 로그인에 덮이면 수정 기능 자체가 무의미해진다.
 */
class ProfileSurvivesReloginTest extends ProfileTestSupport {

    ProfileSurvivesReloginTest(MockMvc mockMvc, UserRepository userRepository, UserService userService,
                               TokenService tokenService, JdbcTemplate jdbc) {
        super(mockMvc, userRepository, userService, tokenService, jdbc);
    }

    @Test
    void 바꿔둔_이름은_구글이_다른_이름을_보내와도_안_덮인다() {
        User created = newUser();
        String sub = created.getGoogleSub();
        String photo = created.getProfileImageUrl();
        userService.updateName(created.getId(), "내가고친이름");

        User again = userService.findOrCreate(sub, created.getEmail(), "구글이름바뀜",
                "https://lh3.googleusercontent.com/other");

        assertThat(again.getName()).isEqualTo("내가고친이름");
        assertThat(again.getProfileImageUrl()).as("사진 칸도 같은 규칙이다").isEqualTo(photo);
    }
}
