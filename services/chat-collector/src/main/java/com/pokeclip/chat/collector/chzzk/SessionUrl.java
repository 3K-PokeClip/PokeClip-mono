package com.pokeclip.chat.collector.chzzk;

import java.net.URI;

/**
 * 치지직이 주는 https URL을 java.net.http.WebSocket이 받는 wss URI로 바꾼다.
 * socket.io-client가 내부에서 하던 일이라 라이브러리를 안 쓰는 이상 우리가 진다.
 *
 * <p><b>경로를 붙이는 것이 핵심이다.</b> 치지직이 주는 url은
 * {@code https://ssioNN.nchat.naver.com:443?auth=…}로 경로가 비어 있고,
 * 소켓 엔드포인트는 {@code /socket.io/} 아래에 있다. 안 붙이면
 * WebSocketHandshakeException으로 떨어진다 — 추측이 아니라 실측이다
 * (태스크 1 시도 #1이 정확히 이걸로 실패했다, {@code _workspace/01_probe.md}).
 */
public final class SessionUrl {

    private static final String PATH = "/socket.io/";
    private static final String ENGINE_IO_QUERY = "EIO=3&transport=websocket";

    private SessionUrl() { }

    public static URI toWebSocketUri(String url) {
        URI given = URI.create(url);
        String scheme = switch (given.getScheme()) {
            case "https", "wss" -> "wss";
            case "http", "ws" -> "ws";
            default -> throw new IllegalArgumentException("알 수 없는 스킴: " + given.getScheme());
        };
        // getQuery()가 아니라 getRawQuery()다. 전자는 퍼센트 이스케이프를 디코딩해서
        // 주는데, 그걸 다시 인코딩하지 않고 붙이면 서버가 받는 토큰이 달라진다.
        // 지금 치지직 토큰은 순수 영숫자라 실서버에서 안 드러나지만, 문자셋이
        // 넓어지는 날의 증상은 인증만 조용히 실패하는 것이다.
        String rawQuery = given.getRawQuery();
        String query = rawQuery == null || rawQuery.isBlank()
                ? ENGINE_IO_QUERY
                : rawQuery + "&" + ENGINE_IO_QUERY;
        String path = given.getPath() == null || given.getPath().isBlank() ? PATH : given.getPath();

        return URI.create(scheme + "://" + given.getAuthority() + path + "?" + query);
    }
}
