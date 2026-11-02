package com.pokeclip.auth.profile;

import ch.qos.logback.classic.Level;
import com.pokeclip.auth.token.TokenService;
import com.pokeclip.auth.user.User;
import com.pokeclip.auth.user.UserRepository;
import com.pokeclip.auth.user.UserService;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 창고 이름이 비면 <b>사진만 꺼지고 이름 수정·로그인은 그대로 돈다.</b> 1번의 창고 준비를 안 기다리고
 * 이번 세션을 끝낼 수 있는 근거가 이것이라, 재는 자리가 있어야 한다.
 *
 * <p>값을 명시로 비운다 — application-test.yml이 지금은 비어 있지만 나중에 채워질 수 있고,
 * 그러면 이 검사가 조용히 「켜진 상태」를 재게 된다.
 */
@SpringBootTest(properties = {
        "pokeclip.profile-photo.bucket=",
        "pokeclip.profile-photo.token-secret=",
        "pokeclip.profile-photo.base-url="
})
class ProfilePhotoDisabledTest extends ProfileTestSupport {

    ProfilePhotoDisabledTest(MockMvc mockMvc, UserRepository userRepository, UserService userService,
                             TokenService tokenService, JdbcTemplate jdbc) {
        super(mockMvc, userRepository, userService, tokenService, jdbc);
    }

    @Test
    void 사진_올리기는_503이다() throws Exception {
        User u = newUser();
        byte[] body = new byte[512];
        System.arraycopy(new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A}, 0, body, 0, 8);

        mockMvc.perform(multipart("/api/auth/me/photo")
                        .file(new MockMultipartFile("file", "me.png", "image/png", body))
                        .header("Authorization", bearer(u))
                        .with(r -> {
                            r.setMethod("PUT");
                            return r;
                        }))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.reason").value("PHOTO_STORAGE_DISABLED"));

        assertThat(userRepository.findById(u.getId()).orElseThrow().getProfilePhotoKey())
                .as("창고가 실패했는데 표가 사진을 가리키면 안 된다")
                .isNull();
    }

    /**
     * 🔴 <b>아무나 부를 수 있는 경로다</b>(permitAll). 창고가 꺼진 배포에서는 표 서명키도 빈 문자열이라,
     * 꺼짐 판정 없이 표 검증으로 들어가면 {@code SecretKeySpec}이 던지는
     * {@code IllegalArgumentException}이 그대로 <b>500</b>이 된다 — 인증 없이 500을 무한히 만들 수 있다.
     * 꺼진 상태에서도 답은 사진이 켜졌을 때의 거절과 <b>같은 404</b>여야 한다.
     */
    @Test
    void 사진_꺼내기는_404다() throws Exception {
        // 🔴 표의 <b>모양이 맞아야</b> 서명 계산까지 간다. aaa.bbb.ccc.ddd처럼 숫자가 아닌 칸이
        // 있으면 파싱 단계에서 먼저 거부돼 빈 서명키에 닿지도 않는다 — 그 글자로 재면
        // 꺼짐 판정을 지워도 초록이다(주입해서 확인했다). 만료 시각은 넉넉히 미래로 둔다.
        long farFuture = Instant.now().getEpochSecond() + 86_400;
        mockMvc.perform(get("/api/profile-photos/1?token=1." + farFuture + ".0.c2ln"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/profile-photos/1"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 이름_수정은_그대로_된다() throws Exception {
        User u = newUser();

        mockMvc.perform(patch("/api/auth/me")
                        .header("Authorization", bearer(u))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"새 이름\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("새 이름"));
    }

    @Test
    void 회원_정보의_사진_주소는_구글_주소_그대로다() throws Exception {
        User u = newUser();

        mockMvc.perform(get("/api/auth/me").header("Authorization", bearer(u)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileImageUrl").value(u.getProfileImageUrl()));
    }

    /**
     * 🔴 <b>사진을 이미 올린 회원이 남아 있는 채로 창고가 꺼지면 사진 주소가 {@code null}이 된다.</b>
     * 올릴 때 구글 주소를 비웠기 때문이다(User.attachPhoto). 화면은 이니셜을 그리고
     * <b>에러는 아무 데도 안 난다</b> — 그 상태를 아무도 모르는 것이 문제라 로그 한 줄이 유일한 신호다.
     *
     * <p>위 검사({@code 회원_정보의_사진_주소는_구글_주소_그대로다})는 <b>사진을 안 올린 회원</b>만 재고,
     * 그쪽은 꺼짐 판정을 지워도 초록이다 — 올린 사진이 없으면 어차피 구글 주소가 나가기 때문이다.
     * 꺼짐 판정을 실제로 재는 것은 이 갈래뿐이다.
     *
     * <p><b>데이터는 안 잃는다</b> — 설정을 되돌리면 그대로 복구된다.
     */
    @Test
    void 사진을_올린_회원은_꺼진_동안_주소가_null이고_로그가_남는다() throws Exception {
        User u = newUser();
        attachPhotoDirectly(u);
        String authorization = bearer(u);

        String body;
        try (LogCaptor logs = new LogCaptor()) {
            body = mockMvc.perform(get("/api/auth/me").header("Authorization", authorization))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            assertThat(logs.messages())
                    .as("에러가 아무 데도 안 나는 자리다 — 이 한 줄이 유일한 신호다")
                    .anyMatch(m -> m.equals("auth.profile.photo.unreachable userId=" + u.getId()));
            assertThat(logs.levelOf("auth.profile.photo.unreachable")).isEqualTo(Level.WARN);
        }

        // 🔴 jsonPath(...).doesNotExist()는 키 부재를 재지 못한다 — 값이 null인 키도 통과한다.
        // 여기서 재려는 것은 「키는 있고 값이 null」이라 본문 문자열로 본다.
        assertThat(body)
                .as("칸은 그대로 두고 값만 비운다 — 웹이 박아 둔 칸을 빼면 화면이 깨진다")
                .contains("\"profileImageUrl\":null");

        assertThat(userRepository.findById(u.getId()).orElseThrow().getProfilePhotoKey())
                .as("데이터는 안 잃는다 — 설정을 되돌리면 복구된다")
                .isEqualTo("profile-photos/" + u.getId());
    }

    /**
     * 창고가 켜져 있을 때 올린 상태를 표에만 만든다. 여기서는 올리기가 503이라 창구로는 못 만든다.
     */
    private void attachPhotoDirectly(User u) {
        User loaded = userRepository.findById(u.getId()).orElseThrow();
        loaded.attachPhoto("profile-photos/" + u.getId(), Instant.now());
        userRepository.save(loaded);
    }
}
