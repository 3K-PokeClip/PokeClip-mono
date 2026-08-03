package com.pokeclip.auth.api;

import com.pokeclip.auth.support.RealServerTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MockMvc로는 잡을 수 없는 것만 여기서 본다.
 *
 * <p>서블릿 컨테이너는 400·404·405를 만들 때 요청을 {@code /error}로 ERROR
 * 디스패치하는데, MockMvc에는 그 단계가 없다. 그래서 시큐리티 필터가 ERROR
 * 디스패치에 개입해 상태 코드를 401로 바꿔치기해도 MockMvc 테스트는 초록불이다.
 * 실제 톰캣을 띄워야만 드러난다.
 */
class ErrorDispatchTest extends RealServerTestSupport {

    private final TestRestTemplate restTemplate;

    ErrorDispatchTest(TestRestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Test
    void 본문_검증_실패는_401이_아니라_400이다() {
        ResponseEntity<String> response = postJson("/api/auth/google", """
                {"code":""}
                """);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void 깨진_JSON도_401이_아니라_400이다() {
        ResponseEntity<String> response = postJson("/api/auth/google", "{\"code\":");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void 빈_refresh_토큰도_401이_아니라_400이다() {
        ResponseEntity<String> response = postJson("/api/auth/refresh", """
                {"refreshToken":""}
                """);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /**
     * 없는 경로는 401이 맞다. 400·405와 성격이 다르다 — 저쪽은 요청이 실제
     * 엔드포인트에 닿았는데 본문·메서드가 틀린 것이고, 이쪽은 엔드포인트 자체가
     * 없다. {@code anyRequest().authenticated()}가 먼저 걸리므로 인증되지 않은
     * 사람에게는 경로의 존재 여부를 알려주지 않는다. 404를 주면 경로 열거가 된다.
     */
    @Test
    void 없는_경로는_토큰이_없으면_존재_여부를_알려주지_않는다() {
        ResponseEntity<String> response =
                restTemplate.getForEntity("/api/auth/does-not-exist", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void 허용되지_않은_메서드는_405다() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/auth/logout", HttpMethod.GET, HttpEntity.EMPTY, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
    }

    /** 인증이 필요한 곳은 여전히 401이어야 한다. /error를 열어도 이건 안 뚫린다. */
    @Test
    void 보호된_API는_토큰이_없으면_그대로_401이다() {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/auth/me", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private ResponseEntity<String> postJson(String path, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.postForEntity(path, new HttpEntity<>(body, headers), String.class);
    }
}
