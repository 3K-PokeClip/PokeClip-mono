package com.pokeclip.auth.api;

import com.pokeclip.auth.profile.ProfileTestSupport;
import com.pokeclip.auth.token.TokenService;
import com.pokeclip.auth.user.User;
import com.pokeclip.auth.user.UserRepository;
import com.pokeclip.auth.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

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

    /**
     * 올린 사진에도 같은 규칙이 걸린다. 재로그인이 구글 사진을 다시 채워 넣으면 <b>올린 사진이
     * 밀려나는 것이 아니라 둘이 동시에 있게 되고</b>, 어느 쪽을 보여줄지가 그날의 우연이 된다.
     *
     * <p>표에만 만든다 — 이 컨텍스트는 사진이 꺼져 있어 창구로는 못 올린다. 재는 것은 창고가 아니라
     * {@code findOrCreate}가 표를 덮는지 여부다.
     */
    @Test
    void 올려둔_사진은_구글이_다른_사진을_보내와도_안_덮인다() {
        User created = newUser();
        String sub = created.getGoogleSub();
        String key = "profile-photos/" + created.getId();
        User loaded = userRepository.findById(created.getId()).orElseThrow();
        loaded.attachPhoto(key, Instant.now());
        userRepository.save(loaded);

        User again = userService.findOrCreate(sub, created.getEmail(), "구글이름바뀜",
                "https://lh3.googleusercontent.com/other");

        assertThat(again.getProfilePhotoKey()).as("올린 사진을 가리키는 칸이 그대로여야 한다").isEqualTo(key);
        assertThat(again.getProfileImageUrl())
                .as("구글 주소가 다시 채워지면 사진 둘이 공존해 어느 쪽이 보일지가 우연이 된다")
                .isNull();
    }
}
