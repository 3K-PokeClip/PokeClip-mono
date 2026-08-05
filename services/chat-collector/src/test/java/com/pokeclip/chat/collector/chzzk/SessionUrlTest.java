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

    /** 가짜 서버는 http로 뜬다. 같은 규칙이 걸려야 테스트가 실물과 같은 길을 탄다. */
    @Test
    void http는_ws로_바꾼다() {
        assertThat(SessionUrl.toWebSocketUri("http://localhost:1/socket.io/?auth=T").getScheme())
                .isEqualTo("ws");
    }
}
