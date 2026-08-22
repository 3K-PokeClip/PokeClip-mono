package com.pokeclip.clip.web;

import com.pokeclip.clip.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CORS 배선이 실제로 걸려 있는지 못박는다. auth의 같은 이름 파일에서 옮겨 왔다.
 *
 * <p><b>왜 필요한가.</b> {@code SecurityConfig}의 {@code .cors(Customizer.withDefaults())} 한 줄이
 * 없으면 {@code CorsConfigurationSource} 빈이 있어도 적용되지 않는다. 그러면 2번의 {@code web/}에서
 * 오는 <b>모든 브라우저 호출이 죽는데 서버는 멀쩡히 뜨고 시험도 전부 초록</b>이다 —
 * 그 줄을 지우고 전수를 돌려 초록인 것을 확인했다(인가 감사 1차 중대 #1). 이 파일이 그 그물이다.
 *
 * <p>preflight는 컨트롤러에 닿기 전에 CORS 필터가 처리하므로 방송 행이나 카드가 없어도 된다.
 */
@AutoConfigureMockMvc
class CorsTest extends IntegrationTestSupport {

    private static final String ALLOWED = "http://localhost:3000";

    private final MockMvc mockMvc;

    CorsTest(MockMvc mockMvc) {
        this.mockMvc = mockMvc;
    }

    @Test
    void 허용된_출처의_preflight는_통과한다() throws Exception {
        mockMvc.perform(options("/api/clip/jump-cards/1/claim")
                        .header("Origin", ALLOWED)
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED));
    }

    @Test
    void 허용되지_않은_출처의_preflight는_막힌다() throws Exception {
        mockMvc.perform(options("/api/clip/jump-cards/1/claim")
                        .header("Origin", "https://evil.example.com")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden());
    }

    /** 놓기·되돌리기가 DELETE다. 허용 메서드에 없으면 브라우저에서 그 둘이 안 된다(auth의 연동 해제와 같은 자리). */
    @Test
    void DELETE_preflight가_통과한다() throws Exception {
        mockMvc.perform(options("/api/clip/jump-cards/1/claim")
                        .header("Origin", ALLOWED)
                        .header("Access-Control-Request-Method", "DELETE")
                        .header("Access-Control-Request-Headers", "Authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Methods", containsString("DELETE")));
    }

    /** SSE 연결이 실제로 타는 preflight다 — 실시간 통로도 같은 배선을 쓴다. */
    @Test
    void Authorization_헤더_preflight가_통과한다() throws Exception {
        mockMvc.perform(options("/api/clip/broadcasts/s-1/events")
                        .header("Origin", ALLOWED)
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "Authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Headers", containsString("Authorization")));
    }

    /**
     * 와일드카드를 부팅에서 막는 근거가 "allowCredentials=false라 명세상 *가 허용된다"는 것이다.
     * 그 전제가 조용히 뒤집히면 근거가 무너지므로 못박는다. 켜지는 순간 쿠키가 실려 오고,
     * CSRF를 끈 근거("쿠키를 안 쓴다")도 같이 무너진다.
     *
     * <p>clip의 {@code RequiredPropertiesTest} javadoc이 이 전제를 <b>문장으로만</b> 들고 있었다.
     *
     * <p><b>앞의 두 단언이 없으면 이 시험은 아무것도 안 잰다.</b> CORS 배선을 통째로 지우면
     * 어떤 CORS 헤더도 안 나가므로 {@code doesNotExist}가 저절로 참이 된다 — 실제로 배선을
     * 지웠을 때 이 갈래만 초록으로 남았다. 그래서 "CORS가 돌았고, 그런데도 자격증명은
     * 안 붙었다"를 재도록 좁혔다(async-test-reality 문항 2).
     */
    @Test
    void 자격증명은_허용하지_않는다() throws Exception {
        mockMvc.perform(options("/api/clip/jump-cards/1/claim")
                        .header("Origin", ALLOWED)
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED))
                .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
    }
}
