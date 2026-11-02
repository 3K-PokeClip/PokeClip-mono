package com.pokeclip.auth.config;

import com.pokeclip.auth.support.RealServerTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 기본(`none`)에서는 프록시 헤더를 안 믿는다. 이 검사가 없으면 「헤더를 무조건 믿는」 회귀가 초록이다 —
 * 아무나 헤더를 위조해 IP당 한도를 통째로 우회한다(카드 POK-91 완료 조건 2).
 *
 * <p>진짜 톰캣이어야 한다 — MockMvc는 서블릿 컨테이너를 안 띄워 {@code RemoteIpValve}가 있어도 안 돈다.
 * 소켓은 {@code server.address=127.0.0.1}이라 항상 루프백이다.
 */
class ForwardedHeadersDisabledTest extends RealServerTestSupport {

    static final String EXCHANGE = "/api/stream-keys/pairing-codes/exchange";

    private final TestRestTemplate rest;
    private final JdbcTemplate jdbc;

    ForwardedHeadersDisabledTest(TestRestTemplate rest, JdbcTemplate jdbc) {
        this.rest = rest;
        this.jdbc = jdbc;
    }

    /** 루프백 해시 행을 남기면 같은 소켓으로 교환하는 다른 실서버 시험이 429를 맞는다. */
    @BeforeEach
    @AfterEach
    void clear() {
        jdbc.update("DELETE FROM pairing_exchange_attempts");
    }

    @Test
    void 헤더를_실어도_소켓_IP_하나로_센다() {
        for (int i = 0; i < 5; i++) {
            assertThat(exchange(rest, "203.0.113.10").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        assertThat(exchange(rest, "203.0.113.11").getStatusCode())
                .as("다른 IP를 헤더에 적었는데 새 한도를 받았다 — 헤더를 믿고 있다")
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    static ResponseEntity<String> exchange(TestRestTemplate rest, String forwardedFor) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Forwarded-For", forwardedFor);
        return rest.postForEntity(EXCHANGE, new HttpEntity<>("{\"code\":\"ZZZZ-ZZZZ\"}", headers), String.class);
    }
}
