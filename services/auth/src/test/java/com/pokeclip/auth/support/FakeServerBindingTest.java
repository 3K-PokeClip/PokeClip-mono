package com.pokeclip.auth.support;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>가짜 서버가 루프백에 못박혀 있는가.</b>
 *
 * <p>🔴 주소를 정하지 않으면 IPv6 와일드카드에 붙는데, 그러면 <b>남이 같은 번호의
 * {@code 127.0.0.1}을 잡을 수 있다</b>. {@code ServerSocket}의 기본 {@code reuseAddress}가
 * {@code true}라 <b>아무 프로그램이나 기본 설정 그대로</b> 가로챈다 — 이 기계에서
 * 와일드카드 100/100, 루프백 0/100으로 쟀다.
 *
 * <p>가로채면 더 구체적인 주소라 {@code localhost} 요청을 통째로 가져가고, 시험은
 * 「연결 실패」나 「호출 0회」로 <b>간헐 실패</b>한다 — 다시 돌리면 초록이라 원인을 못 찾는다.
 * POK-174(clip) 세션이 6,000회 중 4회를 실제로 잡았다(MCP 서버·IntelliJ 빌드 서버가 답했다).
 *
 * <p><b>이 검사는 「가로챌 수 있나」를 직접 잰다</b> — 주소 문자열을 보는 것으로는 부족하다.
 * 커널이 그 번호를 예약했는지가 실제로 지키는 것이고, 그것은 붙어 봐야 안다.
 */
class FakeServerBindingTest {

    @Test
    void 가짜_서버들의_포트를_남이_가로챌_수_없다() {
        for (String[] target : new String[][]{
                {"치지직", FakeChzzkServer.class.getSimpleName(), CHZZK_URI},
                {"유튜브", FakeYoutubeServer.class.getSimpleName(), YOUTUBE_URI},
        }) {
            int port = java.net.URI.create(target[2]).getPort();
            assertThat(port).as("%s 포트를 못 읽었다", target[0]).isPositive();
            assertThat(canHijack(port))
                    .as("%s(%s) 포트 %d를 남이 잡을 수 있다 — 루프백에 못박아야 한다",
                            target[0], target[1], port)
                    .isFalse();
        }
    }

    /** 대조 — 와일드카드로 띄우면 실제로 잡힌다. 이것이 초록이면 위 검사가 아무것도 안 재는 것이다. */
    @Test
    void 주소를_안_정하면_실제로_가로채진다() throws Exception {
        try (var wildcard = new ServerSocket()) {
            wildcard.setReuseAddress(true);
            wildcard.bind(new InetSocketAddress(0));   // 지금 고친 자리들이 쓰던 방식
            assertThat(canHijack(wildcard.getLocalPort()))
                    .as("이 기계에서 와일드카드가 안 가로채지면 위 검사는 아무것도 못 지킨다")
                    .isTrue();
        }
    }

    private static final String CHZZK_URI = FakeChzzkServer.start().baseUrl();
    private static final String YOUTUBE_URI = FakeYoutubeServer.start().baseUrl();

    private static boolean canHijack(int port) {
        try (ServerSocket thief = new ServerSocket()) {
            thief.setReuseAddress(true);   // 기본값이다 — 남의 프로그램은 대개 이 상태다
            thief.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
