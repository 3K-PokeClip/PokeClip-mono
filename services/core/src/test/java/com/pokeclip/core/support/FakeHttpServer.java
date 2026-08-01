package com.pokeclip.core.support;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
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
            HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
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
