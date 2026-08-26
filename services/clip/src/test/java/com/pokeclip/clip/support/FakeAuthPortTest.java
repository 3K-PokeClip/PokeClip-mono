package com.pokeclip.clip.support;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 🔴 <b>{@link FakeAuth}가 잡은 포트를 남이 가로챌 수 없다.</b>
 *
 * <p>이 시험이 없으면 {@code callCount()} 양성 대조를 쓰는 시험 열 자리가 <b>간헐로</b> 깨진다 —
 * 실패 모양이 「요청이 안 나갔다」라 원인이 우리 코드에 있는 것처럼 보인다.
 */
class FakeAuthPortTest {

    /**
     * <b>와일드카드로 잡으면 자리를 못 지킨다.</b> {@code new InetSocketAddress(0)}은 IPv6
     * 와일드카드({@code [::]})에 바인딩되고, 그것은 <b>같은 포트의 IPv4 루프백 바인딩을 막지
     * 않는다</b>(주소 계열이 달라 커널이 충돌로 보지 않는다). 그러면 뒤늦게 붙은 쪽이
     * <b>더 구체적인 주소</b>라 {@code localhost} 트래픽을 통째로 가져가고, 우리 핸들러는
     * 한 번도 안 돈다 — {@code callCount()}가 0이 되고 클라이언트는 그 남의 서버가 주는
     * 404·401·타임아웃을 받는다.
     *
     * <p>이 기계에서 실제로 그 일이 났다(2026-08-26 실측): 임시 포트를 3,000번 잡아 요청을
     * 보내니 넷이 남의 서버로 갔다 — MCP 서버의 {@code 401 …restricted mode} · IntelliJ 빌드
     * 서버(57972)의 읽기 시한 · 정체 모를 404·401. 세션 다섯이 한 기계에서 도는 중이라
     * 임시 포트 대역에 남의 루프백 서버가 계속 생긴다.
     *
     * <p><b>가로채기 시도를 그대로 재현한다.</b> 고친 코드는 {@code BindException}으로 막고,
     * 안 고친 코드는 바인딩이 성공해 아래 요청이 그 서버로 간다.
     */
    @Test
    void 가짜_auth가_잡은_포트를_남이_가로채지_못한다() throws IOException {
        try (FakeAuth auth = FakeAuth.start()) {
            auth.respondWith(200, "{\"relation\":\"OWNER\"}");
            int port = Integer.parseInt(auth.baseUrl().substring(auth.baseUrl().lastIndexOf(':') + 1));

            HttpServer 가로챈_서버 = null;
            try {
                가로챈_서버 = HttpServer.create(
                        new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 0);
                가로챈_서버.createContext("/", exchange -> {
                    byte[] body = "{\"relation\":\"NONE\"}".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(body);
                    }
                });
                가로챈_서버.start();
            } catch (IOException expected) {
                // 자리를 지켰다 — 이것이 고친 뒤의 정상이다.
            }

            try {
                String body = RestClient.builder()
                        .requestFactory(new JdkClientHttpRequestFactory())
                        .build()
                        .post()
                        .uri(auth.baseUrl() + "/internal/editor-delegations/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"userId\":42,\"streamerUserId\":7}")
                        .retrieve()
                        .body(String.class);

                assertThat(auth.callCount())
                        .as("요청이 가짜 auth가 아니라 남의 서버로 갔다")
                        .isEqualTo(1);
                assertThat(body)
                        .as("응답을 남의 서버가 만들었다")
                        .isEqualTo("{\"relation\":\"OWNER\"}");
            } finally {
                if (가로챈_서버 != null) {
                    가로챈_서버.stop(0);
                }
            }
        }
    }

    /**
     * 주소가 <b>바인딩한 그 주소</b>여야 한다. {@code localhost}는 {@code 127.0.0.1}과
     * {@code ::1} 둘로 풀리므로, 루프백 IPv4에만 바인딩해 놓고 이름으로 부르면
     * 해석 순서가 바뀌는 환경에서 못 닿는다.
     */
    @Test
    void 주소가_실제로_바인딩한_주소를_가리킨다() {
        try (FakeAuth auth = FakeAuth.start()) {
            assertThat(auth.baseUrl()).startsWith("http://127.0.0.1:");
        }
    }

    /**
     * 🔴 <b>대조 갈래 — 이것이 없으면 위 시험이 「아무것도 안 지키면서 초록」이 될 수 있다.</b>
     *
     * <p>위 시험은 「가로채기가 <b>막힌다</b>」를 잰다. 그런데 <b>가로채기가 애초에 안 되는
     * 환경</b>(다른 OS·커널 설정·도둑 소켓 옵션)에서는 무엇을 바인딩하든 막히므로, 우리가
     * 루프백을 쓰든 와일드카드로 되돌리든 초록이다. 그러면 회귀가 와도 안 잡힌다 —
     * 이 세션이 내내 밟은 <b>「0이 두 뜻」</b>과 같은 계열이다.
     *
     * <p>그래서 <b>옛 모양(와일드카드)으로 띄우면 실제로 가로채진다</b>는 것을 같이 재 둔다.
     * <b>이 갈래가 빨간불이면 위 시험은 무효다</b> — 고쳐진 것이 아니라 잴 수 없는 환경이라는
     * 뜻이므로, 위 시험의 초록을 근거로 쓰지 말고 원인을 다시 봐라.
     *
     * <p><b>도둑의 조건이 결론을 뒤집는다</b>(이웃 세션 POK-207 실측). 도둑 소켓의
     * {@code SO_REUSEADDR}가 꺼져 있으면 <b>와일드카드에서도 가로채기가 0</b>이 되어
     * 「결함이 사라진 것처럼」 보인다. 이 기계 실측(2026-08-26, N=200):
     *
     * <pre>
     * 서버=wildcard 도둑=HttpServer(이 시험이 쓰는 것)  200/200
     * 서버=wildcard 도둑=ServerSocket reuse=true       200/200
     * 서버=wildcard 도둑=ServerSocket reuse=false        0/200   ← 거짓 음성
     * 서버=loopback 도둑=셋 다                            0/200
     * </pre>
     *
     * <p>그래서 <b>기본값이 켜져 있다는 것도 함께 못박는다</b> — 남의 프로그램은 대개 켠 상태이고
     * 그것이 현실 조건이다. {@code HttpServer}는 {@code ServerSocketChannel}을 쓰고 그 기본값도
     * 켜짐이라, 이 시험의 도둑은 <b>현실 조건과 같다</b>.
     */
    @Test
    void 대조_와일드카드로_띄우면_실제로_가로채진다() throws IOException {
        assertThat(new ServerSocket().getReuseAddress())
                .as("도둑 소켓의 SO_REUSEADDR 기본값이 꺼졌다 — 이 환경에서는 가로채기가 안 되고, "
                        + "그러면 위 시험들이 아무것도 안 지키면서 초록이 된다")
                .isTrue();

        HttpServer 옛_모양 = HttpServer.create(new InetSocketAddress(0), 0);
        옛_모양.createContext("/", exchange -> exchange.sendResponseHeaders(200, -1));
        옛_모양.start();
        try {
            int port = 옛_모양.getAddress().getPort();
            HttpServer 도둑 = HttpServer.create(
                    new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 0);
            도둑.stop(0);
        } finally {
            옛_모양.stop(0);
        }
    }
}
