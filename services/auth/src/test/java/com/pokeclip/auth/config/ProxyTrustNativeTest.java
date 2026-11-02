package com.pokeclip.auth.config;

import com.pokeclip.auth.support.RealServerTestSupport;
import com.pokeclip.auth.support.Sha256;
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
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;

import static com.pokeclip.auth.config.ForwardedHeadersDisabledTest.EXCHANGE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 운영 꼴(POK-316)을 진짜 톰캣으로 잰다: {@code native} + 신뢰 대역을 환경변수로 좁힘 + resolve 전용 창구 열림.
 * MockMvc 는 톰캣 밸브를 안 태우므로 여기서만 잴 수 있다. 소켓은 늘 루프백이라 루프백이 ALB 자리다.
 *
 * <p>좁힌 대역은 {@code FORWARD_HEADERS_TRUSTED_PROXIES} 이름으로 준다: yml 의 그 자리를 거쳐야 「환경변수가 실제로
 * 배선된다」를 잰다. 루프백(ALB 자리)과 문서용 대역 198.51.100.0/24(CloudFront 원본 쪽 대역 자리)만 믿는다.
 * Boot 기본 대역에 있던 10.0.0.0/8 은 빠졌다.
 *
 * <p>요청자 IP 는 교환 창구가 남기는 시도 기록의 해시로 읽는다. 별도 컨텍스트다: {@code IntegrationTestSupport} 주석 참조.
 */
@TestPropertySource(properties = {
        "server.forward-headers-strategy=native",
        "FORWARD_HEADERS_TRUSTED_PROXIES=127.0.0.1/32, 198.51.100.0/24",
        "pokeclip.internal-api.stream-key-resolve.token=" + ProxyTrustNativeTest.RESOLVE_TOKEN,
        "pokeclip.internal-api.stream-key-resolve.allowed-cidrs=127.0.0.1/32"
})
class ProxyTrustNativeTest extends RealServerTestSupport {

    static final String RESOLVE_TOKEN = "test-only-proxy-trust-resolve-token-0123456789";

    private static final String CLOUDFRONT_EDGE = "198.51.100.9";

    private static final String UNKNOWN_KEY_BODY =
            "{\"streamid\":\"#!::r=7ZK3M9QW2XJ4NB6TC8VDFG5HRP,m=publish\"}";

    private final TestRestTemplate rest;
    private final JdbcTemplate jdbc;

    ProxyTrustNativeTest(TestRestTemplate rest, JdbcTemplate jdbc) {
        this.rest = rest;
        this.jdbc = jdbc;
    }

    /** 루프백 해시 행을 남기면 같은 소켓으로 교환하는 다른 실서버 시험이 429를 맞는다. */
    @BeforeEach
    @AfterEach
    void clear() {
        jdbc.update("DELETE FROM pairing_exchange_attempts");
    }

    /** CloudFront 가 시청자 주소를, ALB 가 CloudFront 엣지 주소를 오른쪽에 붙인다. 엣지 주소는 믿는 대역이라 건너뛴다. */
    @Test
    void CloudFront를_거친_요청은_시청자_IP로_센다() {
        exchange(Map.of("X-Forwarded-For", "203.0.113.20, " + CLOUDFRONT_EDGE));

        assertThat(recordedIpHashes())
                .as("엣지 주소에서 멈췄다면 한도가 「엣지 노드당」이 된다")
                .containsExactly(Sha256.hex("203.0.113.20"));
    }

    /** 시청자가 왼쪽에 적어 보낸 값은 CloudFront 가 그 오른쪽에 진짜 주소를 붙이므로 버려진다. */
    @Test
    void 시청자가_왼쪽에_적은_위조_IP는_안_믿는다() {
        exchange(Map.of("X-Forwarded-For", "6.6.6.6, 203.0.113.21, " + CLOUDFRONT_EDGE));

        assertThat(recordedIpHashes()).containsExactly(Sha256.hex("203.0.113.21"));
    }

    /**
     * 좁힌 대역이 실제로 밸브에 닿는지 본다. Boot 기본 대역이면 10.0.0.5 를 건너뛰어 203.0.113.22 가 채택된다. 좁힌 대역에는
     * 10.0.0.0/8 이 없어 10.0.0.5 에서 멈춘다: 환경변수가 안 먹으면 이 단언이 깨진다.
     */
    @Test
    void 좁힌_대역_밖의_사설_주소에서_멈춘다() {
        exchange(Map.of("X-Forwarded-For", "203.0.113.22, 10.0.0.5"));

        assertThat(recordedIpHashes()).containsExactly(Sha256.hex("10.0.0.5"));
    }

    /** 믿는 프록시가 https 로 받았다고 알리면 요청이 https 로 읽혀 Spring Security 기본 HSTS 가 붙는다. */
    @Test
    void 믿는_프록시가_Proto_https를_보내면_HSTS가_붙는다() {
        ResponseEntity<String> https = exchange(Map.of("X-Forwarded-Proto", "https"));
        ResponseEntity<String> plain = exchange(Map.of());

        assertThat(https.getHeaders().getFirst("Strict-Transport-Security"))
                .isEqualTo("max-age=31536000 ; includeSubDomains");
        assertThat(plain.getHeaders().containsHeader("Strict-Transport-Security"))
                .as("http 요청에 HSTS 가 붙었다: Proto 를 안 보고 있다")
                .isFalse();
    }

    /** 대조: 전달 머리글이 없으면 출발지(루프백, 허용 대역)와 토큰을 지나 resolve 에 닿는다. */
    @Test
    void 전용_창구는_머리글이_없으면_resolve에_닿는다() {
        assertThat(resolve(RESOLVE_TOKEN, Map.of()).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /** 머리글이 없으면 판정은 그대로다: 출발지를 지나고 토큰이 틀려 401 이다. */
    @Test
    void 전용_창구는_머리글이_없으면_토큰_검사도_그대로다() {
        assertThat(resolve("wrong-" + RESOLVE_TOKEN, Map.of()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /**
     * 허용 주소(루프백)를 X-Forwarded-For 에 적은 경우가 핵심이다. 톰캣 밸브는 소켓(루프백)을 믿는 프록시로 보고 그 값을
     * 채택한 뒤 머리글을 지운다: 표시 밸브가 앞에 없으면 필터에는 「머리글 없는 허용 주소 요청」으로 보여 200 이 된다.
     */
    @Test
    void 전용_창구는_전달_머리글이_있으면_403이다() {
        for (String header : List.of(
                "X-Forwarded-For", "Forwarded", "X-Forwarded-Proto", "X-Forwarded-Host", "X-Forwarded-Port")) {
            String value = switch (header) {
                case "X-Forwarded-For" -> "127.0.0.1";
                case "Forwarded" -> "for=127.0.0.1";
                case "X-Forwarded-Proto" -> "https";
                case "X-Forwarded-Host" -> "auth.internal";
                default -> "443";
            };
            ResponseEntity<String> response = resolve(RESOLVE_TOKEN, Map.of(header, value));

            assertThat(response.getStatusCode()).as("%s가 붙었는데 통과했다", header).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(response.getBody()).as("403 에 본문이 있다").isNull();
        }
    }

    private ResponseEntity<String> exchange(Map<String, String> extraHeaders) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        extraHeaders.forEach(headers::set);
        return rest.postForEntity(EXCHANGE, new HttpEntity<>("{\"code\":\"ZZZZ-ZZZZ\"}", headers), String.class);
    }

    private ResponseEntity<String> resolve(String token, Map<String, String> extraHeaders) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Stream-Key-Resolve-Token", token);
        extraHeaders.forEach(headers::set);
        return rest.postForEntity(
                "/internal/stream-keys/resolve", new HttpEntity<>(UNKNOWN_KEY_BODY, headers), String.class);
    }

    private List<String> recordedIpHashes() {
        return jdbc.queryForList("SELECT client_ip_hash FROM pairing_exchange_attempts", String.class);
    }
}
