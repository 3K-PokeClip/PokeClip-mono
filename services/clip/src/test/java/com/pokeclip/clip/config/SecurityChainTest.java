package com.pokeclip.clip.config;

import com.pokeclip.clip.support.IntegrationTestSupport;
import com.pokeclip.clip.support.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 체인 둘이 각자 자기 경로만 가져가는지 본다. clip의 첫 시큐리티다.
 *
 * <p>아직 카드 문이 하나도 없으므로 <b>존재하지 않는 경로</b>로 잰다 — 인증이 먼저
 * 걸리면 401, 인증을 지나가면 404다. 401만 재면 "토큰이 틀려서 401"과 "체인이 전부를
 * 막아서 401"을 구분할 수 없어서, 갈래마다 404 짝을 둔다(async-test-reality 문항 5).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SecurityChainTest extends IntegrationTestSupport {

    /** application-test.yml의 pokeclip.internal-api.token과 같은 값이어야 한다. */
    private static final String INTERNAL_TOKEN = "test-only-internal-token-32bytes-long!!";

    private final int port;

    SecurityChainTest(@LocalServerPort int port) {
        this.port = port;
    }

    @Test
    void 토큰_없이_사람용_경로를_부르면_401이다() {
        assertThat(get("/api/clip/nothing", Map.of()).statusCode()).isEqualTo(401);
    }

    @Test
    void 유효한_토큰이면_인증을_지나_404까지_간다() {
        assertThat(get("/api/clip/nothing", bearer(TestTokens.access("7"))).statusCode()).isEqualTo(404);
    }

    @Test
    void 서명이_틀리면_401이다() {
        assertThat(get("/api/clip/nothing", bearer(TestTokens.tampered(TestTokens.access("7")))).statusCode())
                .isEqualTo(401);
    }

    @Test
    void 만료된_토큰이면_401이다() {
        // clock skew 기본 허용치가 60초다. 그 밖으로 확실히 밀어야 실제로 만료를 잰다.
        assertThat(get("/api/clip/nothing", bearer(TestTokens.access("7", Instant.now().minusSeconds(600))))
                .statusCode()).isEqualTo(401);
    }

    /**
     * exp가 없으면 Nimbus 기본값이 통과다 — 그러면 영원히 안 죽는 토큰이 생긴다.
     * JwtConfig의 setAllowEmptyExpiryClaim(false)가 막는 유일한 갈래이고,
     * 이 시험이 없으면 그 줄을 지워도 전수가 초록이다(결함 주입으로 확인함).
     */
    @Test
    void exp가_없는_토큰은_401이다() {
        assertThat(get("/api/clip/nothing", bearer(TestTokens.accessWithoutExpiry("1001"))).statusCode())
                .isEqualTo(401);
    }

    @Test
    void 내부_경로는_내부_토큰이_없으면_401이다() {
        assertThat(get("/internal/nothing", Map.of()).statusCode()).isEqualTo(401);
    }

    @Test
    void 내부_경로에_사람_토큰을_내면_401이다() {
        assertThat(get("/internal/nothing", bearer(TestTokens.access("7"))).statusCode()).isEqualTo(401);
    }

    @Test
    void 내부_토큰이_맞으면_인증을_지나_404까지_간다() {
        assertThat(get("/internal/nothing", Map.of("X-Internal-Token", INTERNAL_TOKEN)).statusCode())
                .isEqualTo(404);
    }

    // 없는 경로의 404가 401로 둔갑하면 프론트가 "토큰 만료"로 오진해 재로그인 루프에 든다.
    // 그 갈래는 위 `유효한_토큰이면_인증을_지나_404까지_간다`가 isEqualTo(404)로 이미 정확히 잰다 —
    // 여기에 isNotEqualTo(401)을 더 두면 500도 통과시키는 더 약한 중복이 된다(문항 2·5).
    @Test
    void health는_토큰_없이_열린다() {
        assertThat(get("/actuator/health", Map.of()).statusCode()).isEqualTo(200);
    }

    private HttpResponse<String> get(String path, Map<String, String> headers) {
        HttpRequest.Builder builder = HttpRequest
                .newBuilder(URI.create("http://localhost:" + port + path))
                .GET();
        headers.forEach(builder::header);
        try {
            return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, String> bearer(String token) {
        return Map.of("Authorization", "Bearer " + token);
    }
}
