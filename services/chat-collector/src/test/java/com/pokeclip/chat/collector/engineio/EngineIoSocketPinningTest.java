package com.pokeclip.chat.collector.engineio;

import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <b>송신 잠금이 가상 스레드를 캐리어에 붙들지 않는지 잰다.</b>
 *
 * <p>Java 21은 {@code synchronized} 안에서 블로킹하면 캐리어를 고정한다. 세션이 100개가
 * 되면 {@code sendPing()}은 방송 내내 상시로, {@code close()}는 종료 때 한꺼번에
 * 이 자리를 지난다 — 캐리어가 CPU 수만큼뿐이라 그만큼 다른 세션이 굶는다.
 *
 * <p><b>가짜 서버로는 이 갈래를 못 만든다(실측).</b> 응답을 삼켜도 {@code sendText}의
 * future는 상대 응답이 아니라 <b>전송 완료</b>로 끝나므로 {@code .get()}이 만료되지
 * 않는다 — 실제 소켓 + {@code answerPong=false}에서 sendPing 112ms·pinned=0이었다.
 * 그래서 <b>송신이 안 끝나는 소켓</b>을 직접 물린다. 실물에서 이 모양이 되는 자리는
 * 반개방 TCP로 송신 버퍼가 차는 때다. 참고로 그 실측에서 {@code close()}는 정상 서버
 * 상대로도 pinned=1이 났다 — 만료까지 안 가도 park 한 번이면 고정된다.
 */
class EngineIoSocketPinningTest {

    /** 계획 검증이 잰 수와 같게 맞춘다(캐리어 10개, N=20이면 소요가 2배였다). */
    private static final int SESSIONS = 20;

    /** 잠금만 재므로 실물 접속이 필요 없다. {@code close()}가 이것을 닫는다. */
    private final HttpClient sharedClient = HttpClient.newHttpClient();

    @AfterEach
    void tearDown() {
        sharedClient.shutdownNow();
    }

    /**
     * 문항 1(세션 하나로도 통과하는가): <b>통과한다.</b> 락 고정은 세션 하나에서도 난다.
     * 그래도 20을 여는 이유는 <b>세는 값이 배수로 커져야 잡음과 갈리기 때문</b>이다 —
     * 1건이면 다른 스레드가 흘린 pinned와 구분이 안 된다.
     * 문항 2(자동으로 참이 되는가): {@code isZero()}는 동작을 안 부르면 그냥 참이다.
     * 그래서 <b>스무 번이 다 PingFailure까지 갔는지</b>를 먼저 단언한다.
     * 문항 3(정말 겹치는가): 소요 시간이 증거다. 순차면 5초×20=100초인데 실측 5.0초다.
     * 문항 5(그 결함에서 빨간불인가): {@code synchronized (sendLock)}으로 되돌리면
     * <b>pinned=21 · 소요 10.0초</b>(확인함 — 캐리어 10개에 20개가 고정돼 시한의 2배를
     * 쓴다. 21건인 것은 {@code get(timeout)}이 한 번 더 park한 갈래가 있어서다).
     */
    @Test
    void ping이_잠금_안에서_캐리어를_고정하지_않는다() throws Exception {
        AtomicInteger failures = new AtomicInteger();
        long pinned = pinnedEventsWhile("sendPing", socket -> {
            try {
                socket.sendPing();
            } catch (PingFailure expected) {
                // 송신이 안 끝나는 소켓이므로 .get(5s)가 만료된다. 여기까지 와야 잰 것이다.
                failures.incrementAndGet();
            }
        });

        assertThat(failures.get())
                .as("스무 갈래가 다 잠금 안의 대기를 지나야 이 검사가 무언가를 잰 것이다")
                .isEqualTo(SESSIONS);
        assertThat(pinned)
                .as("ping은 방송 내내 상시로 이 자리를 지난다 — 여기가 고정되면 세션 수만큼 캐리어가 마른다")
                .isZero();
    }

    /**
     * 문항 1·2·3은 위와 같다. 문항 4(통과시키는 잘못된 결과가 있는가): 소켓이 애초에
     * 닫혀 있어 {@code close()}가 즉시 돌아오면 0이 나온다 — 그래서 스무 번이 다
     * <b>대기를 지나 끝났는지</b>를 세고, 소요 시간을 함께 찍는다.
     * 문항 5: 되돌리면 <b>pinned=20 · 소요 2.1초</b>다(확인함). 세션당 1건인 것은 첫
     * {@code .get(1s)}가 만료되면 catch로 빠져 둘째 {@code .get}이 안 돌기 때문이다 —
     * <b>두 번 기다린다고 읽으면 틀린다.</b>
     */
    @Test
    void 죽은_소켓_스무_개를_동시에_닫아도_캐리어가_안_잠긴다() throws Exception {
        AtomicInteger closed = new AtomicInteger();
        long pinned = pinnedEventsWhile("close", socket -> {
            socket.close();
            closed.incrementAndGet();
        });

        assertThat(closed.get())
                .as("close는 예외를 삼키므로 개수를 세지 않으면 대기를 지났는지 알 수 없다")
                .isEqualTo(SESSIONS);
        assertThat(pinned)
                .as("종료는 세션 수만큼 한꺼번에 몰린다")
                .isZero();
    }

    /**
     * <b>{@code ReentrantLock}은 자동으로 안 풀린다.</b> {@code synchronized}는 블록을
     * 벗어나면 풀리지만 이쪽은 {@code finally}가 없으면 잠긴 채 남고, 그 소켓은 다시는
     * 아무것도 못 보낸다 — 그 스트리머의 채팅이 조용히 끊긴다.
     *
     * <p>문항 4(통과시키는 잘못된 결과): <b>같은 스레드로 재면 항상 통과한다.</b>
     * 재진입 잠금이라 주인이 다시 부르면 그냥 들어간다. 그래서 <b>다른 스레드</b>에서 본다.
     * 문항 5: {@code sendPing}의 {@code finally}를 지우면 뒤 스레드가 영영 못 들어와
     * 빨간불(확인함).
     */
    @Test
    void ping이_실패해도_다음_송신이_잠금을_얻는다() throws Exception {
        EngineIoSocket socket = new EngineIoSocket(sharedClient, new FailingWebSocket());

        assertThatThrownBy(socket::sendPing).isInstanceOf(PingFailure.class);

        assertThat(otherThreadCanSend(socket))
                .as("잠금이 안 풀리면 이 소켓은 영영 다시 못 보낸다")
                .isTrue();
    }

    /**
     * 문항 4·5는 위와 같다. {@code close()}는 예외를 삼키므로 잠금이 남아도 아무 소리가
     * 안 난다 — 다음 {@code close()}(종료는 두 경로에서 들어온다)가 영영 매달린다.
     * {@code close}의 {@code finally}를 지우면 빨간불(확인함).
     */
    @Test
    void close가_지나간_뒤에도_잠금이_남지_않는다() throws Exception {
        EngineIoSocket socket = new EngineIoSocket(sharedClient, new FailingWebSocket());

        socket.close();

        assertThat(otherThreadCanSend(socket))
                .as("뒷정리는 handleClosed와 stop 양쪽에서 들어온다 — 둘째가 매달리면 종료가 멈춘다")
                .isTrue();
    }

    /** 잠금을 쥐지 않은 <b>다른 스레드</b>가 시한 안에 송신에 들어가는가. */
    private static boolean otherThreadCanSend(EngineIoSocket socket) throws InterruptedException {
        CountDownLatch entered = new CountDownLatch(1);
        Thread other = new Thread(() -> {
            try {
                socket.sendPing();
            } catch (PingFailure expected) {
                // 잠금을 얻어 송신까지 갔다는 뜻이다. 실패 자체는 여기서 볼 것이 아니다.
            }
            entered.countDown();
        });
        other.start();
        boolean ok = entered.await(5, TimeUnit.SECONDS);
        other.interrupt();
        return ok;
    }

    /**
     * 가상 스레드 {@value #SESSIONS}개에서 동시에 {@code action}을 돌리고, 그동안 난
     * {@code jdk.VirtualThreadPinned}(threshold 0) 중 <b>스택에 EngineIoSocket이 있는
     * 것만</b> 센다. 다른 스레드가 흘린 고정을 우리 몫으로 세면 고쳐도 빨간불이 남는다.
     */
    private static long pinnedEventsWhile(String label, Consumer<EngineIoSocket> action)
            throws Exception {
        // HttpClient는 셀렉터 스레드를 소유한다. 스무 개를 만들면 재려는 것과 무관한
        // 스레드가 스무 배로 늘어난다 — 잠금만 재므로 하나를 공유한다.
        HttpClient httpClient = HttpClient.newHttpClient();
        List<EngineIoSocket> sockets = new ArrayList<>();
        for (int i = 0; i < SESSIONS; i++) {
            sockets.add(new EngineIoSocket(httpClient, new StalledWebSocket()));
        }

        Recording recording = new Recording();
        recording.enable("jdk.VirtualThreadPinned").withThreshold(Duration.ZERO).withStackTrace();

        CountDownLatch done = new CountDownLatch(SESSIONS);
        recording.start();
        long startedAt = System.nanoTime();
        for (EngineIoSocket socket : sockets) {
            Thread.ofVirtual().start(() -> {
                try {
                    action.accept(socket);
                } finally {
                    done.countDown();
                }
            });
        }
        assertThat(done.await(120, TimeUnit.SECONDS)).as("갈래가 다 끝나야 잰 값이 뜻이 있다").isTrue();
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
        recording.stop();

        Path dump = Files.createTempFile("pinning-" + label, ".jfr");
        try {
            recording.dump(dump);
            recording.close();
            long pinned = countOurPinnedEvents(dump);
            // 소요 시간이 문항 3의 답이다 — 순차로 돌았으면 여기가 세션 수만큼 커진다.
            System.out.println("[pinning] " + label + " sessions=" + SESSIONS
                    + " elapsed=" + elapsedMs + "ms pinned=" + pinned);
            return pinned;
        } finally {
            Files.deleteIfExists(dump);
            httpClient.shutdownNow();
        }
    }

    private static long countOurPinnedEvents(Path dump) throws IOException {
        String target = EngineIoSocket.class.getName();
        try (RecordingFile file = new RecordingFile(dump)) {
            long pinned = 0;
            while (file.hasMoreEvents()) {
                RecordedEvent event = file.readEvent();
                if (!"jdk.VirtualThreadPinned".equals(event.getEventType().getName())) continue;
                if (event.getStackTrace() == null) continue;
                for (RecordedFrame frame : event.getStackTrace().getFrames()) {
                    if (target.equals(frame.getMethod().getType().getName())) {
                        pinned++;
                        break;
                    }
                }
            }
            return pinned;
        }
    }

    /**
     * <b>송신이 영영 안 끝나는 소켓.</b> 반개방 TCP로 송신 버퍼가 찬 상태의 모양이다 —
     * {@code sendText}는 즉시 돌아오고 future만 미완으로 남아, 잠금 안의
     * {@code .get(...)}이 시한을 통째로 쓴다.
     */
    /** 송신이 <b>즉시 실패</b>하는 소켓. 잠금 해제만 볼 때는 기다릴 이유가 없다. */
    private static final class FailingWebSocket extends StalledWebSocket {
        @Override
        public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) {
            return CompletableFuture.failedFuture(new IOException("closed"));
        }

        @Override
        public CompletableFuture<WebSocket> sendClose(int statusCode, String reason) {
            return CompletableFuture.failedFuture(new IOException("closed"));
        }
    }

    private static class StalledWebSocket implements WebSocket {

        private static <T> CompletableFuture<T> never() {
            return new CompletableFuture<>();
        }

        @Override public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) { return never(); }
        @Override public CompletableFuture<WebSocket> sendBinary(ByteBuffer data, boolean last) { return never(); }
        @Override public CompletableFuture<WebSocket> sendPing(ByteBuffer message) { return never(); }
        @Override public CompletableFuture<WebSocket> sendPong(ByteBuffer message) { return never(); }
        @Override public CompletableFuture<WebSocket> sendClose(int statusCode, String reason) { return never(); }
        @Override public void request(long n) { }
        @Override public String getSubprotocol() { return ""; }
        @Override public boolean isOutputClosed() { return false; }
        @Override public boolean isInputClosed() { return false; }
        @Override public void abort() { }
    }
}
