package com.pokeclip.core.auth.config;

import com.pokeclip.core.support.IntegrationTestSupport;
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
     */
    @Test
    void 자격증명은_허용하지_않는다() throws Exception {
        mockMvc.perform(options("/api/auth/google")
                        .header("Origin", "http://localhost:3000")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
    }
}
