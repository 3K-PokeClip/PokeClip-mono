package com.pokeclip.clip.support;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 🔴 <b>{@code RANDOM_PORT} 톰캣이 잡은 포트도 남이 가로챌 수 없다.</b>
 * {@code application-test.yml}의 {@code server.address: 127.0.0.1}이 그것을 만든다 —
 * <b>그 줄을 지우면 이 시험만 빨간불이 된다.</b>
 *
 * <p>기본값(주소를 안 정함)은 <b>와일드카드</b>이고, 와일드카드로 잡은 임시 포트는 자리를
 * 못 지킨다: 커널이 IPv6 와일드카드와 IPv4 루프백을 <b>주소 계열이 달라</b> 충돌로 보지
 * 않으므로, 뒤늦게 뜬 아무 프로세스나 {@code 127.0.0.1:같은포트}를 잡을 수 있고 잡으면
 * <b>더 구체적인 주소</b>라 {@code localhost} 트래픽을 통째로 가져간다. 그러면 시험은
 * 「연결은 됐는데 우리 서버가 아니었다」로 깨지고 <b>원인이 우리 코드처럼 보인다.</b>
 *
 * <p>{@code clip/CLAUDE.md}에 적힌 {@code StripeHeadOfLineTest}의 1회성 실패
 * ({@code HTTP/1.1 header parser received no bytes} — 단언이 아니라 연결 수립 실패)가
 * 정확히 그 모양이다. <b>같은 뿌리를 {@link FakeAuth}도 밟고 있었고</b>(전수 16회 중 2회
 * 빨간불), 거기는 {@link FakeAuthPortTest}가 지킨다.
 *
 * <p><b>실측(2026-08-26)</b> — 이 줄이 없을 때 톰캣 포트 가로채기가 <b>성공</b>했고,
 * 넣은 뒤에는 {@code BindException}이다. 세션 다섯이 한 기계에서 도는 중이라 임시 포트
 * 대역에 남의 루프백 서버가 계속 생긴다.
 *
 * <p>🔴 <b>대조는 {@link FakeAuthPortTest#대조_와일드카드로_띄우면_실제로_가로채진다()}가 진다.</b>
 * 「막힌다」만 재면 <b>가로채기가 애초에 안 되는 환경</b>에서 이 시험이 아무것도 안 지키면서
 * 초록이 된다 — 그쪽 갈래가 「옛 모양으로 띄우면 실제로 가로채진다」를 같이 재므로,
 * <b>그것이 빨간불이면 이 시험의 초록도 무효다.</b> 기전이 같아(임시 포트를 와일드카드에
 * 바인딩) 대조를 두 번 두지 않는다 — 스프링 컨텍스트를 하나 더 띄우는 값이 없다.
 *
 * <p>{@code HealthEndpointTest}와 <b>같은 컨텍스트 설정</b>이라 컨텍스트를 새로 안 띄운다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RandomPortBindTest extends IntegrationTestSupport {

    private final int port;

    RandomPortBindTest(@LocalServerPort int port) {
        this.port = port;
    }

    @Test
    void 톰캣이_잡은_포트를_남이_가로채지_못한다() {
        assertThatThrownBy(() -> HttpServer.create(
                new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 0))
                .as("톰캣이 와일드카드에 붙어 있다 — 남이 127.0.0.1:%d를 잡아 요청을 가져갈 수 있다", port)
                .isInstanceOf(BindException.class);
    }
}
