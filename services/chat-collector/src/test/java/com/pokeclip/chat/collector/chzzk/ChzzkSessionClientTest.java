package com.pokeclip.chat.collector.chzzk;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 세션 키를 싣는 REST 넷(채팅·후원 구독과 반납). 가짜 치지직은 루프백 JDK HttpServer이고 받은 원문 쿼리를 그대로 적는다
 * ({@code getQuery()}로 읽으면 디코딩돼 인코딩 결함이 안 보인다).
 */
class ChzzkSessionClientTest {

    private HttpServer server;
    private final List<String> seen = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            seen.add(exchange.getRequestURI().getPath() + "?" + exchange.getRequestURI().getRawQuery());
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private ChzzkSessionClient client() {
        return new ChzzkSessionClient(RestClient.builder().requestFactory(new JdkClientHttpRequestFactory()).build(),
                "http://127.0.0.1:" + server.getAddress().getPort(), "user-token");
    }

    @Test
    void 세션_키의_더하기_빗금_등호를_인코딩해_네_요청에_싣는다() {
        // 치지직 문서는 세션 키 모양을 약속하지 않는다(지금 실측은 UUID). +를 그대로 이어 붙이면 받는 쪽이 공백으로 읽는다.
        // 라이브 목록 커서가 바로 그렇게 400을 받았다(POK-325). 구독이 400이면 재연결이 끝없이 돌고 채팅 수집이 멈춘다.
        String key = "ab+cd/ef==";
        ChzzkSessionClient client = client();

        client.subscribeChat(key);
        client.subscribeDonation(key);
        client.unsubscribeDonationQuietly(key);
        client.unsubscribeChatQuietly(key);

        assertThat(seen).containsExactly(
                "/open/v1/sessions/events/subscribe/chat?sessionKey=ab%2Bcd%2Fef%3D%3D",
                "/open/v1/sessions/events/subscribe/donation?sessionKey=ab%2Bcd%2Fef%3D%3D",
                "/open/v1/sessions/events/unsubscribe/donation?sessionKey=ab%2Bcd%2Fef%3D%3D",
                "/open/v1/sessions/events/unsubscribe/chat?sessionKey=ab%2Bcd%2Fef%3D%3D");
    }

    @Test
    void 지금_실측_모양인_UUID_키는_바이트가_그대로다() {
        String key = "8ffaea86-1c2d-4e5f-9a0b-1234567890ab";

        client().subscribeChat(key);

        assertThat(seen).containsExactly("/open/v1/sessions/events/subscribe/chat?sessionKey=" + key);
    }
}
