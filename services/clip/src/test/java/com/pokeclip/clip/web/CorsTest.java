package com.pokeclip.clip.web;

import com.pokeclip.clip.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
     * 🔴 <b>clip은 자격증명을 허용한다 — 영상 출입증(POK-122)이 CloudFront 서명 쿠키를 {@code Set-Cookie}로
     * 주고, 앱이 다른 오리진에서 {@code credentials: 'include'}로 불러야 브라우저가 그 쿠키를 받기
     * 때문이다.</b> 전에는 「허용하지 않는다」를 못박았고 그 근거가 「쿠키를 안 쓴다 = CSRF를 꺼도 된다」였다.
     *
     * <p>그 근거는 <b>여전히 참</b>이다 — 쿠키를 <i>내보내기만</i> 하고 <i>받아서 판정하지는</i> 않는다.
     * 그래서 이 시험이 재는 것은 둘이다: ① CORS가 돌았고 자격증명이 켜졌다 ② <b>쿠키만 싣고
     * Authorization이 없는 요청은 401</b>이다. ②가 무너지는 날이 CSRF를 다시 켜야 하는 날이다.
     *
     * <p>와일드카드 출처를 부팅에서 막는 근거는 이제 더 강해졌다 — 자격증명이 켜지면 명세가
     * {@code *}를 금지하므로 {@code CorsProperties} 생성자의 그 검사가 있어야 부팅 뒤 브라우저에서
     * 조용히 막히는 일이 없다.
     *
     * <p><b>①의 앞 두 단언이 없으면 이 시험은 아무것도 안 잰다</b> — CORS 배선을 통째로 지우면
     * 어떤 CORS 헤더도 안 나가 뒤 단언만 남는다(async-test-reality 문항 2).
     */
    @Test
    void 자격증명을_허용하되_쿠키는_인증_재료가_아니다() throws Exception {
        mockMvc.perform(options("/api/clip/broadcasts/s-1/playback-access")
                        .header("Origin", ALLOWED)
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));

        // 쿠키만 실린 요청 — 출입증 쿠키든 아무 쿠키든 clip은 그것으로 사람을 판정하지 않는다.
        mockMvc.perform(get("/api/clip/broadcasts").param("state", "live")
                        .header("Origin", ALLOWED)
                        .header("Cookie", "CloudFront-Key-Pair-Id=K1; CloudFront-Signature=x; CloudFront-Policy=y"))
                .andExpect(status().isUnauthorized());
    }
}
