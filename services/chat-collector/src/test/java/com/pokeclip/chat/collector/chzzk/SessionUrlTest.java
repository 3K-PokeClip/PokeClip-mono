package com.pokeclip.chat.collector.chzzk;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SessionUrlTest {

    /**
     * 치지직은 https URL을 준다. java.net.http.WebSocket은 ws/wss만 받는다.
     * socket.io-client가 내부에서 하던 변환을 우리가 직접 진다.
     */
    @Test
    void https를_wss로_바꾸고_소켓IO_경로와_EIO를_붙인다() {
        var uri = SessionUrl.toWebSocketUri("https://ssio08.nchat.naver.com:443?auth=TOKEN");

        assertThat(uri.getScheme()).isEqualTo("wss");
        assertThat(uri.getHost()).isEqualTo("ssio08.nchat.naver.com");
        assertThat(uri.getPath()).isEqualTo("/socket.io/");
        assertThat(uri.getQuery()).contains("auth=TOKEN").contains("EIO=3")
                .contains("transport=websocket");
    }

    /**
     * {@code URI.getQuery()}는 퍼센트 이스케이프를 <b>디코딩해서</b> 준다.
     * 그걸 인코딩 없이 다시 붙이면 서버가 받는 토큰이 우리가 받은 것과 달라진다.
     *
     * <p>지금 치지직 토큰은 순수 영숫자라 실서버에서 드러나지 않는다 —
     * {@code LiveProbeTest}가 이 변환 그대로 실제 인증을 통과했다. 그래서 이건
     * 버그가 아니라 하드닝이고, <b>이 테스트가 없으면 다른 테스트도 가짜 서버도
     * 영숫자만 쓰기 때문에 되돌아가도 아무도 모른다.</b> 문자셋이 넓어지는 날의
     * 증상은 인증만 조용히 실패하는 것이다.
     */
    @Test
    void 퍼센트로_인코딩된_토큰을_디코딩하지_않는다() {
        var uri = SessionUrl.toWebSocketUri("https://ssio08.nchat.naver.com:443?auth=A%2BB%3DC%26D");

        assertThat(uri.getRawQuery())
                .isEqualTo("auth=A%2BB%3DC%26D&EIO=3&transport=websocket");
    }

    /** 가짜 서버는 http로 뜬다. 같은 규칙이 걸려야 테스트가 실물과 같은 길을 탄다. */
    @Test
    void http는_ws로_바꾼다() {
        assertThat(SessionUrl.toWebSocketUri("http://localhost:1/socket.io/?auth=T").getScheme())
                .isEqualTo("ws");
    }
}
