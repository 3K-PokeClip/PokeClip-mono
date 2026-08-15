package com.pokeclip.chat.collector.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 진짜 PostgreSQL 앞에 세우는 TCP 중계기. <b>"연결은 살아 있는데 응답이 안 오는"
 * 반개방(half-open) 스톨을 결정적으로 만든다</b> — 서버가 답을 안 주는 것이 아니라
 * 답이 <i>도착하지 않는</i> 상태라, 클라이언트 소켓은 멀쩡히 열려 있고 read만 영영
 * 막힌다. 죽은 포트(즉시 거부)나 닫힌 소켓(EOF)으로는 이 상태를 못 만든다.
 *
 * <p>스톨은 <b>클라이언트→서버 바이트에 표식이 보인 순간부터</b> 건다. 시각을 밖에서
 * 고르면 커넥션 풀의 생존 검사(isValid)가 먼저 스톨을 만나 재는 대상이 바뀐다 —
 * 표식을 INSERT의 SQL 텍스트로 두면 <b>정확히 그 배치의 응답</b>이 막힌다.
 * 스톨 뒤에는 서버→클라이언트 바이트를 읽어서 버린다(연결은 유지).
 */
public final class StallingTcpProxy implements AutoCloseable {

    private final ServerSocket server;
    private final String targetHost;
    private final int targetPort;
    private final AtomicBoolean stalled = new AtomicBoolean();
    private final AtomicReference<byte[]> stallMarker = new AtomicReference<>();
    private final AtomicReference<Instant> stalledAt = new AtomicReference<>();
    private final List<Socket> sockets = new CopyOnWriteArrayList<>();
    private final Thread acceptor;

    public StallingTcpProxy(String targetHost, int targetPort) throws IOException {
        this.targetHost = targetHost;
        this.targetPort = targetPort;
        this.server = new ServerSocket(0);
        this.acceptor = new Thread(this::acceptLoop, "stalling-proxy-accept");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    public int port() {
        return server.getLocalPort();
    }

    /** 이 문자열이 클라이언트→서버 바이트에 나타나면 그때부터 응답을 막는다. */
    public void stallResponsesAfter(String marker) {
        stallMarker.set(marker.getBytes(StandardCharsets.UTF_8));
    }

    /** 스톨을 풀고 다음 연결부터 정상 중계한다. 스톨된 연결은 클라이언트가 끊는다. */
    public void resume() {
        stallMarker.set(null);
        stalled.set(false);
    }

    /** 스톨이 실제로 걸린 시각 — 없으면 null. 시한 측정의 시작점이다. */
    public Instant stalledAt() {
        return stalledAt.get();
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                Socket client = server.accept();
                Socket upstream = new Socket(targetHost, targetPort);
                sockets.add(client);
                sockets.add(upstream);
                pump(client, upstream, true);
                pump(upstream, client, false);
            } catch (IOException e) {
                return;   // 닫혔다
            }
        }
    }

    private void pump(Socket from, Socket to, boolean clientToServer) {
        Thread t = new Thread(() -> {
            byte[] buf = new byte[8192];
            try (InputStream in = from.getInputStream(); OutputStream out = to.getOutputStream()) {
                int n;
                while ((n = in.read(buf)) >= 0) {
                    if (clientToServer) {
                        out.write(buf, 0, n);
                        out.flush();
                        byte[] marker = stallMarker.get();
                        if (marker != null && contains(buf, n, marker)
                                && stalled.compareAndSet(false, true)) {
                            stalledAt.set(Instant.now());
                        }
                    } else if (!stalled.get()) {
                        out.write(buf, 0, n);
                        out.flush();
                    }
                    // 스톨 중인 서버→클라이언트 바이트는 읽어서 버린다 — 연결은 살아 있다.
                }
            } catch (IOException e) {
                // 한쪽이 끊겼다 — 반대쪽도 닫는다
            } finally {
                closeQuietly(from);
                closeQuietly(to);
            }
        }, clientToServer ? "stalling-proxy-c2s" : "stalling-proxy-s2c");
        t.setDaemon(true);
        t.start();
    }

    private static boolean contains(byte[] buf, int len, byte[] marker) {
        outer:
        for (int i = 0; i + marker.length <= len; i++) {
            for (int j = 0; j < marker.length; j++) {
                if (buf[i + j] != marker[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
            // 뒷정리
        }
    }

    @Override
    public void close() throws IOException {
        server.close();
        sockets.forEach(StallingTcpProxy::closeQuietly);
    }
}
