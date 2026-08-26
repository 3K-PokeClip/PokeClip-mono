package com.pokeclip.auth.profile;

import com.pokeclip.auth.support.PhotoLocalStackFixture;
import com.pokeclip.auth.token.JwtProperties;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 「이 표로는 그림 한 장 말고 아무것도 못 한다」를 <b>규칙이 아니라 구조로</b> 굳힌다.
 *
 * <p>🔴 <b>앞 셋이 재는 것은 「키가 다르다」가 아니라 「형식이 다르다」다.</b> 감사자가 두 서명키를
 * 같은 값으로 놓고 창구를 전부 두들겼는데 <b>하나도 안 뚫렸다</b> — 로그인 토큰은
 * {@code b64.b64.b64} 세 칸이고 사진 표는 {@code 숫자.숫자.숫자.서명} 네 칸이라 서로의 파서가
 * 상대를 아예 못 읽기 때문이다. 그러니 이 셋은 <b>키를 같게 해도 초록이다.</b>
 *
 * <p><b>키 분리는 여기가 아니라 부팅 검증이 지킨다</b>({@code PhotoConfiguration}·
 * {@code PhotoConfigurationTest}). 형식에만 기대면 표 형식을 JWT로 바꾸는 순간 —
 * 가장 흔한 리팩터링이다 — 방어가 통째로 사라지고 사진 표가 로그인 토큰이 된다.
 *
 * <p>마지막 검사(D)만 키를 본다. 그것도 <b>운영 설정이 아니라 규약을 잰다</b> — 비교하는 두 값이
 * 시험 픽스처와 {@code application-test.yml}의 고정 문자열이라 <b>운영 환경변수는 볼 수 없다.</b>
 * 그쪽을 막는 것이 부팅 검증이다.
 */
class PhotoKeySeparationTest extends PhotoTestSupport {

    private final PhotoProperties photoProperties;
    private final JwtProperties jwtProperties;

    PhotoKeySeparationTest(MockMvc mockMvc, UserRepository userRepository, UserService userService,
                           TokenService tokenService, JdbcTemplate jdbc,
                           PhotoProperties photoProperties, JwtProperties jwtProperties) {
        super(mockMvc, userRepository, userService, tokenService, jdbc);
        this.photoProperties = photoProperties;
        this.jwtProperties = jwtProperties;
    }

    @Test
    void 사진_표로_회원_정보를_부르면_401이다() throws Exception {
        User u = newUser();

        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + photoToken(u.getId())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 사진_표로_스트림키를_부르면_401이다() throws Exception {
        User u = newUser();

        mockMvc.perform(get("/api/stream-keys")
                        .header("Authorization", "Bearer " + photoToken(u.getId())))
                .andExpect(status().isUnauthorized());
    }

    /**
     * 반대 방향. <b>같은 사진에 대해 사진 표는 200</b>인 것을 나란히 재지 않으면, 사진이 아예
     * 안 나가는 상태에서도 이 검사가 초록이 된다.
     */
    @Test
    void 로그인_토큰으로는_사진을_못_꺼낸다() throws Exception {
        User u = newUser();
        upload(u);

        String accessToken = bearer(u).substring("Bearer ".length());

        mockMvc.perform(get("/api/profile-photos/" + u.getId() + "?token=" + accessToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/profile-photos/" + u.getId() + "?token=" + photoToken(u.getId())))
                .andExpect(status().isOk());
    }

    @Test
    void 서명키가_로그인_키와_다르다() {
        assertThat(photoProperties.tokenSecret())
                .as("사진 표와 로그인 토큰이 같은 키를 쓰면 「구조로 갈린다」가 규칙으로 내려앉는다")
                .isNotBlank()
                .isNotEqualTo(jwtProperties.secret());
    }

    private static String photoToken(long userId) {
        return PhotoToken.issue(PhotoLocalStackFixture.TOKEN_SECRET, userId, 0, Instant.now());
    }

    private void upload(User u) throws Exception {
        byte[] body = new byte[512];
        System.arraycopy(new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A}, 0, body, 0, 8);
        mockMvc.perform(multipart("/api/auth/me/photo")
                .file(new MockMultipartFile("file", "me.png", "image/png", body))
                .header("Authorization", bearer(u))
                .with(r -> {
                    r.setMethod("PUT");
                    return r;
                })).andExpect(status().isOk());
    }
}
