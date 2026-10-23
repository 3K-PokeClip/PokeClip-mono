package com.pokeclip.db.support;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 중계기가 표식이 실린 요청의 응답을 <b>한 바이트도</b> 안 넘기는지 잰다(POK-426).
 *
 * <p>CI에서 세 서버의 {@code DatasourceTimeoutTest}가 간헐로 「SQLState가 빈 문자열」로 깨졌다(2.3초, 시한 10초 전).
 * 중계기가 요청을 서버로 <b>먼저 보내고 나서</b> 표식을 찾아 막힘을 켰기 때문이다. 그 사이 서버가 답하면 반대쪽
 * 스레드가 막힘을 보기 전에 응답을 넘겨 질의가 그냥 성공했다(예외가 없으니 SQLState도 없다).
 *
 * <p>그 틈을 넓혀 결정적으로 만든다: 표식을 길게, 그 앞을 표식과 거의 같은 글자로 채워 표식 찾기 자체를 느리게 한다.
 * 가짜 서버는 표식이 실린 요청을 끝까지 받자마자 답한다(앞부분만 받고 답하면 표식 전 응답이라 중계기가 넘기는 것이 맞는데
 * 시험이 그것을 샌 것으로 센다, PR #369 codex). 막힘을 보내기 전에 켜면 틈이 없어 늘 초록이다.
 */
class StallingTcpProxyTest {

    private static final int ROUNDS = 20;

    @Test
    void 표식이_실린_요청의_응답은_한_바이트도_안_넘긴다() throws Exception {
        String marker = "a".repeat(999) + "b";
        byte[] request = ("a".repeat(3_000) + marker).getBytes(StandardCharsets.US_ASCII);

        try (ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            Thread replier = new Thread(() -> answerOnceReceived(server, request.length), "fake-db");
            replier.setDaemon(true);
            replier.start();

            int leaked = 0;
            for (int round = 0; round < ROUNDS; round++) {
                try (StallingTcpProxy proxy = new StallingTcpProxy("127.0.0.1", server.getLocalPort());
                     Socket client = new Socket(InetAddress.getLoopbackAddress(), proxy.port())) {
                    proxy.stallResponsesAfter(marker);
                    client.setSoTimeout(300);
                    OutputStream out = client.getOutputStream();
                    out.write(request);
                    out.flush();
                    try {
                        if (client.getInputStream().read() >= 0) {
                            leaked++;
                        }
                    } catch (SocketTimeoutException expected) {
                        // 막혔다: 연결은 살아 있고 응답만 안 온다
                    }
                }
            }

            // 표식을 아예 못 알아봐도 응답이 넘어가 여기서 빨갛다(막힘 시각은 따로 안 본다: 늦게 켜지는 것이 결함이라
            // 응답을 읽은 순간에는 아직 비어 있다).
            assertThat(leaked).as("%d번 중 표식 뒤 응답이 새어 나간 횟수", ROUNDS).isZero();
        }
    }

    /**
     * 표식이 두 번의 읽기에 걸쳐 와도 알아본다. 한 조각 안에서만 찾으면 TCP가 요청을 나눠 줄 때 표식을 못 봐 응답이 그대로 넘어간다
     * (부하 중 루프백도 큰 요청을 나눠 준다). 여기서는 표식 가운데에서 끊어 두 번 보내 그 상황을 결정적으로 만든다.
     */
    @Test
    void 표식이_두_조각에_걸쳐_와도_응답을_막는다() throws Exception {
        String marker = "pok426_split_marker";
        byte[] first = "SELECT 'pok426_spl".getBytes(StandardCharsets.US_ASCII);
        byte[] second = "it_marker'".getBytes(StandardCharsets.US_ASCII);

        try (ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            Thread replier = new Thread(() -> answerAfterBoth(server, first.length + second.length), "fake-db");
            replier.setDaemon(true);
            replier.start();

            try (StallingTcpProxy proxy = new StallingTcpProxy("127.0.0.1", server.getLocalPort());
                 Socket client = new Socket(InetAddress.getLoopbackAddress(), proxy.port())) {
                proxy.stallResponsesAfter(marker);
                client.setSoTimeout(500);
                OutputStream out = client.getOutputStream();
                out.write(first);
                out.flush();
                Thread.sleep(200);   // 중계기가 첫 조각을 따로 읽게 한다
                out.write(second);
                out.flush();

                boolean leaked;
                try {
                    leaked = client.getInputStream().read() >= 0;
                } catch (SocketTimeoutException expected) {
                    leaked = false;
                }
                assertThat(leaked).as("두 조각에 걸친 표식 뒤 응답이 새어 나갔다").isFalse();
                assertThat(proxy.stalledAt()).as("표식을 알아봤다").isNotNull();
            }
        }
    }

    /** 요청을 다 받은 뒤에 답한다(표식이 두 조각에 걸친 시험용). */
    private static void answerAfterBoth(ServerSocket server, int total) {
        try (Socket s = server.accept(); InputStream in = s.getInputStream(); OutputStream out = s.getOutputStream()) {
            byte[] buf = new byte[8192];
            int seen = 0;
            int n;
            while (seen < total && (n = in.read(buf)) >= 0) {
                seen += n;
            }
            out.write('Z');
            out.flush();
            in.read(buf);   // 중계기가 닫을 때까지 붙어 있는다
        } catch (Exception ignored) {
            // 중계기가 닫았다
        }
    }

    /** 연결마다 요청을 {@code total}바이트 다 받자마자 답한다. 진짜 DB가 빠르게 답하는 경우를 흉내 낸다. */
    private static void answerOnceReceived(ServerSocket server, int total) {
        while (!server.isClosed()) {
            try {
                Socket s = server.accept();
                Thread t = new Thread(() -> {
                    try (s; InputStream in = s.getInputStream(); OutputStream out = s.getOutputStream()) {
                        byte[] buf = new byte[8192];
                        boolean answered = false;
                        int seen = 0;
                        int n;
                        while ((n = in.read(buf)) >= 0) {
                            seen += n;
                            if (!answered && seen >= total) {
                                out.write('Z');
                                out.flush();
                                answered = true;
                            }
                        }
                    } catch (Exception ignored) {
                        // 중계기가 닫았다
                    }
                });
                t.setDaemon(true);
                t.start();
            } catch (Exception e) {
                return;
            }
        }
    }
}
