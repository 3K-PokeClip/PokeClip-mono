package com.pokeclip.auth.api;

import com.pokeclip.auth.profile.ProfileTestSupport;
import com.pokeclip.auth.token.TokenService;
import com.pokeclip.auth.user.User;
import com.pokeclip.auth.user.UserRepository;
import com.pokeclip.auth.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 표시 이름 수정 창구. 「저장 버튼이 눌리는가」와 「죽어 있던 updated_at이 살아나는가」를 잰다.
 */
class UpdateNameTest extends ProfileTestSupport {

    UpdateNameTest(MockMvc mockMvc, UserRepository userRepository, UserService userService,
                   TokenService tokenService, JdbcTemplate jdbc) {
        super(mockMvc, userRepository, userService, tokenService, jdbc);
    }

    @Test
    void 이름을_바꾸면_저장되고_수정일시가_움직인다() throws Exception {
        User u = newUser();
        Instant before = u.getUpdatedAt();

        mockMvc.perform(rename(u, "새이름"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("새이름"));

        User reloaded = userRepository.findById(u.getId()).orElseThrow();
        assertThat(reloaded.getName()).isEqualTo("새이름");
        assertThat(reloaded.getUpdatedAt()).as("죽어 있던 칸이 살아난다").isAfter(before);
    }

    /** 가운데 공백까지 접으면 "김 태현"이 "김태현"이 된다 — 자르는 것은 앞뒤뿐이다. */
    @Test
    void 앞뒤_공백은_잘라서_저장한다() throws Exception {
        User u = newUser();

        mockMvc.perform(rename(u, "  가운데 공백은 남긴다  "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("가운데 공백은 남긴다"));

        assertThat(userRepository.findById(u.getId()).orElseThrow().getName())
                .isEqualTo("가운데 공백은 남긴다");
    }

    /**
     * 🔴 <b>{@code String.trim()}은 U+0020 이하만 자른다.</b> 전각 공백(U+3000)·NBSP(U+00A0)·
     * EM SPACE(U+2003)·ZWSP(U+200B)는 그대로 남아 <b>「보이지 않는 이름」이 저장됐다</b>
     * (넷 다 통과하는 것을 실측했다). 화면에서는 이름이 없는 것처럼 보이고,
     * 편집자 목록에서 누가 누군지 말해 주지 못한다.
     *
     * <p>{@code String.strip()}으로도 부족하다 — U+3000·U+2003은 잡지만
     * <b>U+00A0·U+200B는 못 잡는다</b>({@code Character.isWhitespace}가 false다).
     * 공백 분류(SPACE_SEPARATOR)와 형식 문자(FORMAT)까지 봐야 한다.
     */
    @Test
    void 보이지_않는_문자만_있는_이름도_거부한다() throws Exception {
        String[][] cases = {
                {"U+3000 전각 공백", "\u3000"},
                {"U+00A0 NBSP", "\u00A0"},
                {"U+2003 EM SPACE", "\u2003"},
                {"U+200B ZWSP", "\u200B"},
                {"섞어서", "\u3000\u00A0\u200B"},
        };
        for (String[] c : cases) {
            User u = newUser();

            mockMvc.perform(rename(u, c[1]))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.reason").value("NAME_BLANK"));

            assertThat(userRepository.findById(u.getId()).orElseThrow().getName())
                    .as("%s — 거부됐으면 옛 이름이 그대로다", c[0])
                    .isEqualTo("김태현");
        }
    }

    /** 앞뒤의 보이지 않는 문자도 잘라야 한다 — 안 자르면 화면의 이름이 한 칸 밀려 보인다. */
    @Test
    void 앞뒤의_보이지_않는_문자도_잘라서_저장한다() throws Exception {
        User u = newUser();

        mockMvc.perform(rename(u, "\u3000\u00A0김태현\u200B\u3000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("김태현"));

        assertThat(userRepository.findById(u.getId()).orElseThrow().getName()).isEqualTo("김태현");
    }

    @Test
    void 공백만_있는_이름은_거부한다() throws Exception {
        User u = newUser();

        mockMvc.perform(rename(u, "   "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("NAME_BLANK"));

        assertThat(userRepository.findById(u.getId()).orElseThrow().getName())
                .as("거부됐으면 옛 이름이 그대로다").isEqualTo("김태현");
    }

    @Test
    void 서른한자는_거부하고_서른자는_통과한다() throws Exception {
        User u = newUser();

        mockMvc.perform(rename(u, "가".repeat(31)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("NAME_TOO_LONG"));

        mockMvc.perform(rename(u, "가".repeat(30)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("가".repeat(30)));
    }

    /**
     * 표(VARCHAR(255))가 세는 단위와 같아야 「30자 통과 → 저장 거부」가 안 생긴다.
     * 😀는 코드 포인트 1개인데 String.length()로는 2다 — 30개면 코드 포인트 30(통과), UTF-16 60.
     */
    @Test
    void 이모지는_코드포인트로_센다() throws Exception {
        User u = newUser();

        mockMvc.perform(rename(u, "😀".repeat(30)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("😀".repeat(30)));

        mockMvc.perform(rename(u, "😀".repeat(31)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("NAME_TOO_LONG"));
    }

    @Test
    void 토큰이_없으면_거부한다() throws Exception {
        User u = newUser();

        mockMvc.perform(patch("/api/auth/me")
                        .contentType(APPLICATION_JSON).content("{\"name\":\"몰래바꾼이름\"}"))
                .andExpect(status().isUnauthorized());

        assertThat(userRepository.findById(u.getId()).orElseThrow().getName()).isEqualTo("김태현");
    }

    /**
     * 회원 번호를 본문으로 받지 않는다 — 토큰의 주인만 자기 것을 고친다. 모르는 필드는 Jackson이
     * 버리므로 남의 번호를 실어 보내도 아무 일이 없다.
     */
    @Test
    void 본문에_남의_번호를_실어도_자기_것만_바뀐다() throws Exception {
        User me = newUser();
        User other = newUser();

        mockMvc.perform(patch("/api/auth/me").header("Authorization", bearer(me))
                        .contentType(APPLICATION_JSON)
                        .content("{\"name\":\"내이름\",\"userId\":" + other.getId() + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(me.getId()))
                .andExpect(jsonPath("$.name").value("내이름"));

        assertThat(userRepository.findById(other.getId()).orElseThrow().getName())
                .as("남의 이름은 그대로다").isEqualTo("김태현");
    }

    private MockHttpServletRequestBuilder rename(User u, String name) {
        return patch("/api/auth/me").header("Authorization", bearer(u))
                .contentType(APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\"}");
    }
}
