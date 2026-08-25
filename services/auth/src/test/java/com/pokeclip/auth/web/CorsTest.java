package com.pokeclip.auth.web;

import com.pokeclip.auth.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class CorsTest extends IntegrationTestSupport {

    private final MockMvc mockMvc;

    CorsTest(MockMvc mockMvc) {
        this.mockMvc = mockMvc;
    }

    /** 치지직 연동 해제가 DELETE다(POK-93). 허용 메서드에 없으면 브라우저 preflight가 403으로 막혀 화면에서 해제가 안 된다. */
    @Test
    void DELETE_preflight가_통과한다() throws Exception {
        mockMvc.perform(options("/api/chzzk-link")
                        .header("Origin", "http://localhost:3000")
                        .header("Access-Control-Request-Method", "DELETE")
                        .header("Access-Control-Request-Headers", "Authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"))
                .andExpect(header().string("Access-Control-Allow-Methods", org.hamcrest.Matchers.containsString("DELETE")));
    }

    /**
     * 회원정보 수정이 PATCH다(POK-207 {@code PATCH /api/auth/me}). 허용 메서드에 없으면 브라우저
     * preflight가 403으로 막혀 화면에서 이름 저장이 안 된다 — DELETE와 같은 자리다.
     */
    @Test
    void PATCH_preflight가_통과한다() throws Exception {
        mockMvc.perform(options("/api/auth/me")
                        .header("Origin", "http://localhost:3000")
                        .header("Access-Control-Request-Method", "PATCH")
                        .header("Access-Control-Request-Headers", "Authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"))
                .andExpect(header().string("Access-Control-Allow-Methods", org.hamcrest.Matchers.containsString("PATCH")));
    }

    @Test
    void 허용된_출처의_preflight는_통과한다() throws Exception {
        mockMvc.perform(options("/api/auth/google")
                        .header("Origin", "http://localhost:3000")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"));
    }

    @Test
    void 허용되지_않은_출처의_preflight는_막힌다() throws Exception {
        mockMvc.perform(options("/api/auth/google")
                        .header("Origin", "https://evil.example.com")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden());
    }

    /** 프론트가 /api/auth/me를 부를 때 실제로 타는 preflight다. */
    @Test
    void Authorization_헤더_preflight가_통과한다() throws Exception {
        mockMvc.perform(options("/api/auth/me")
                        .header("Origin", "http://localhost:3000")
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "Authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Headers",
                        org.hamcrest.Matchers.containsString("Authorization")));
    }

    /**
     * 와일드카드를 부팅에서 막는 근거가 "allowCredentials=false라 명세상 *가 허용된다"는
     * 것이다. 그 전제가 조용히 뒤집히면 근거가 무너지므로 못박는다. 켜지는 순간
     * 쿠키가 실려 오고, CSRF를 끈 근거("쿠키를 안 쓴다")도 같이 무너진다.
     *
     * <p><b>앞의 두 단언이 없으면 이 시험은 아무것도 안 잰다.</b> SecurityConfig의
     * {@code .cors(Customizer.withDefaults())} 한 줄을 지우면 어떤 CORS 헤더도 안 나가므로
     * {@code doesNotExist}가 저절로 참이 된다 — 실제로 그 줄을 지우고 돌렸더니 이 파일의 다섯
     * 갈래가 빨개지는 동안 <b>이 갈래만 초록으로 남았다</b>(POK-207 실측). 그래서
     * "CORS가 돌았고, 그런데도 자격증명은 안 붙었다"를 재도록 좁혔다.
     *
     * <p>clip의 같은 이름 시험이 먼저 이 자리를 겪고 고쳤는데 auth만 남아 있었다.
     */
    @Test
    void 자격증명은_허용하지_않는다() throws Exception {
        mockMvc.perform(options("/api/auth/google")
                        .header("Origin", "http://localhost:3000")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
    }
}
