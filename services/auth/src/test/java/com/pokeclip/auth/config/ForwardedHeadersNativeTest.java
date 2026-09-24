package com.pokeclip.auth.config;

import com.pokeclip.auth.support.RealServerTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import static com.pokeclip.auth.config.ForwardedHeadersDisabledTest.exchange;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code native}를 켜면 톰캣 {@code RemoteIpValve}가 신뢰 대역(기본: 사설망·루프백)에서 온 요청의
 * {@code X-Forwarded-For}를 채택한다. 소켓이 루프백이라 대역 안이다.
 *
 * <p>가정 둘을 여기서 잰다(PRD) — ① Boot가 {@code strategy=native}만으로 Valve를 기본 헤더 이름과 함께 단다
 * ② 기본 대역에 루프백이 든다. 어느 쪽이 틀리면 첫 검사가 429가 아니라 404로 끝난다.
 *
 * <p>별도 컨텍스트다(RANDOM_PORT + 이 프로퍼티). 컨텍스트 수를 하나 더한다 — {@code IntegrationTestSupport} 주석 참조.
 */
@TestPropertySource(properties = "server.forward-headers-strategy=native")
class ForwardedHeadersNativeTest extends RealServerTestSupport {

    private final TestRestTemplate rest;
    private final JdbcTemplate jdbc;

    ForwardedHeadersNativeTest(TestRestTemplate rest, JdbcTemplate jdbc) {
        this.rest = rest;
        this.jdbc = jdbc;
    }

    @BeforeEach
    @AfterEach
    void clear() {
        jdbc.update("DELETE FROM pairing_exchange_attempts");
    }

    @Test
    void 같은_소켓이라도_헤더의_IP마다_한도를_따로_쓴다() {
        for (int i = 0; i < 5; i++) {
            assertThat(exchange(rest, "203.0.113.10").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
        assertThat(exchange(rest, "203.0.113.10").getStatusCode())
                .as("헤더의 IP로 6번째면 429여야 한다 — Valve가 안 달렸거나 루프백이 대역 밖이다")
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        assertThat(exchange(rest, "203.0.113.11").getStatusCode())
                .as("다른 IP는 자기 한도를 새로 받는다")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    /**
     * 프록시 뒤에서 클라이언트가 헤더를 위조해도 안 먹힌다. 로드밸런서는 자기가 본 IP를 <b>오른쪽에</b> 붙이고
     * Valve는 오른쪽부터 읽어 처음 만나는 대역 밖 값을 채택한다 — 왼쪽의 위조값은 버려진다.
     */
    @Test
    void 왼쪽에_적힌_위조_IP는_안_믿는다() {
        for (int i = 0; i < 5; i++) {
            exchange(rest, "9.9.9.9");
        }

        assertThat(exchange(rest, "9.9.9.9, 203.0.113.12").getStatusCode())
                .as("오른쪽 203.0.113.12로 세어야 첫 시도(404)다. 429면 왼쪽 9.9.9.9를 믿은 것이다")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
