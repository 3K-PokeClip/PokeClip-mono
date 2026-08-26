package com.pokeclip.auth.support;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 실제 소켓이 필요한 테스트용 서버.
 *
 * <p>MockRestServiceServer는 소켓을 쓰지 않아 지연을 만들 수 없고,
 * bindTo(builder)가 요청 팩토리를 갈아치워 타임아웃 설정 자체를 무력화한다.
 * 그래서 진짜로 듣는 서버가 필요하다.
 */
public final class FakeHttpServer implements AutoCloseable {

    private final HttpServer server;

    private FakeHttpServer(HttpServer server) {
        this.server = server;
    }

    /** 지정한 시간만큼 끌었다가 응답한다. delay가 0이면 즉시 응답한다. */
    public static FakeHttpServer respondingWith(String path, int status, String body, Duration delay) {
        try {
            // 🔴 <b>주소를 정하지 않으면 IPv6 와일드카드에 붙고, 그러면 남이 같은 번호의
            //    127.0.0.1 을 잡을 수 있다</b> — ServerSocket 의 기본 reuseAddress 가 true 라
            //    아무 프로그램이나 기본 설정 그대로 가로챈다(이 기계에서 100/100 실측).
            //    가로채면 더 구체적인 주소라 localhost 요청을 통째로 가져가고, 시험은
            //    「연결 실패」나 「호출 0회」로 간헐 실패한다 — 재현이 안 돼 원인을 못 찾는다.
            //    루프백에 못박으면 커널이 그 번호를 예약해 창 자체가 없어진다(0/100).
            //    POK-174(clip) 세션이 6,000회 중 4회를 실제로 잡아 알려 왔다.
            HttpServer server = HttpServer.create(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext(path, exchange -> {
                if (!delay.isZero()) {
                    try {
                        Thread.sleep(delay.toMillis());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            });
            server.start();
            return new FakeHttpServer(server);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String url(String path) {
        return "http://localhost:" + server.getAddress().getPort() + path;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
