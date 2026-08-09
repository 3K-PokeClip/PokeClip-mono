package com.pokeclip.chat.collector.engineio;

import com.pokeclip.chat.collector.fake.FakeChzzkBehavior;
import com.pokeclip.chat.collector.fake.FakeChzzkTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@FakeChzzkTest
class EngineIoSocketTest {

    /** 수립 예산. 이 파일은 접속 자체를 보므로 넉넉히 준다. */
    private static final java.time.Duration BUDGET = java.time.Duration.ofSeconds(5);

    /** 중단 신호가 없는 호출. 중단은 EstablishCutCleanupTest가 본다. */
    private static final java.util.function.BooleanSupplier NO_ABORT = () -> false;

    @LocalServerPort int port;
    @Autowired FakeChzzkBehavior behavior;

    private EngineIoSocket socket;

    @AfterEach
    void tearDown() {
        if (socket != null) socket.close();
        behavior.reset();
    }

    /** 함정 4. auth는 핸드셰이크에서 이미 소비됐다. */
    @Test
    void 루트_네임스페이스에_CONNECT를_보내지_않는다() throws Exception {
        CountDownLatch connected = openAndWaitForSystem();
        assertThat(connected.await(5, TimeUnit.SECONDS)).isTrue();

        socket.sendPing();
        Thread.sleep(200);

        // isNotEmpty가 먼저다. 프레임이 한 건도 안 갔으면 doesNotContain은
        // 찾을 것이 없어 자동으로 참이 되고, 함정 4를 지키는 유일한 파수꾼이
        // 아무것도 안 보면서 초록불이 된다.
        assertThat(behavior.receivedFrames())
                .as("40을 보내면 전송은 안 끊기고 disconnect 이벤트만 뜨는 상태가 된다")
                .isNotEmpty()
                .doesNotContain("40");
    }

    /** 함정 1. EIO=3이 아니면 서버가 거부한다. */
    @Test
    void 접속_쿼리에_EIO_3이_들어간다() throws Exception {
        CountDownLatch connected = openAndWaitForSystem();
        assertThat(connected.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(behavior.handshakeQuery()).contains("EIO=3").contains("transport=websocket");
    }

    @Test
    void 우리가_보내는_프레임은_ping_하나뿐이다() throws Exception {
        CountDownLatch connected = openAndWaitForSystem();
        assertThat(connected.await(5, TimeUnit.SECONDS)).isTrue();

        socket.sendPing();
        socket.sendPing();
        Thread.sleep(200);

        assertThat(behavior.receivedFrames()).isNotEmpty().containsOnly("2");
    }

    /** 죽은 소켓에 ping을 쏘면 <b>연결이 죽었다</b>고 말해야 한다. 이 갈래가 재연결을 부른다. */
    @Test
    void 닫힌_소켓에_ping을_쏘면_연결_죽음으로_분류한다() {
        EngineIoSocket closed = EngineIoSocket.open(uri(), frame -> { }, () -> { }, BUDGET, NO_ABORT);
        closed.close();

        assertThatThrownBy(closed::sendPing)
                .isInstanceOf(PingFailure.class)
                // 이름("cause")으로 뽑으면 Throwable.getCause()가 먼저 걸려 감싼 예외가
                // 나온다. 열거값을 보려면 접근자를 직접 불러야 한다.
                .extracting(e -> ((PingFailure) e).cause())
                .isEqualTo(PingFailure.Cause.CONNECTION_DEAD);
    }

    /**
     * <b>분류기를 실제로 지나는 검사다.</b> JDK가 동시 송신 위반을 실패한 future로
     * 주므로, cause를 안 풀면 MISUSE 갈래가 영영 안 잡힌다. 실제 동시 송신은
     * {@code sendLock}이 막아 만들 수 없어 예외 모양으로 직접 먹인다.
     */
    @Test
    void 실패한_future로_온_동시_송신_위반을_우리_잘못으로_분류한다() {
        Exception fromGet = new ExecutionException(new IllegalStateException("Send pending"));

        assertThat(EngineIoSocket.classify(fromGet))
                .as("cause를 안 풀면 CONNECTION_DEAD로 새고, 그러면 우리 버그가 재연결에 덮인다")
                .isEqualTo(PingFailure.Cause.MISUSE);
    }

    /** 양성 대조. 진짜 연결 죽음이 MISUSE로 분류되면 재연결이 아예 안 돈다. */
    @Test
    void IO_오류는_연결_죽음으로_분류한다() {
        Exception fromGet = new ExecutionException(new IOException("closed"));

        assertThat(EngineIoSocket.classify(fromGet)).isEqualTo(PingFailure.Cause.CONNECTION_DEAD);
    }

    @Test
    void 서버가_끊으면_onClosed가_불린다() throws Exception {
        behavior.pingIntervalMillis = 200;
        behavior.pingTimeoutMillis = 480;

        CountDownLatch closed = new CountDownLatch(1);
        socket = EngineIoSocket.open(uri(), frame -> { }, closed::countDown, BUDGET, NO_ABORT);

        assertThat(closed.await(5, TimeUnit.SECONDS))
                .as("서버가 조용히 끊었는데 우리가 모르면 그게 8/1 사고다")
                .isTrue();
    }

    /**
     * {@code HttpClient}는 셀렉터 스레드와 워커 풀을 소유한다. 소켓마다 새로
     * 만들면서 안 닫으면 그 스레드들이 통째로 남는다.
     *
     * <p>지금은 프로세스당 한 번이라 영향이 작지만, {@code ChatSession.open()}은
     * <b>재진입 가능하도록 설계돼 있다</b> — POK-86이 강제 절단 뒤에 그것을 통째로
     * 다시 부른다. 재연결이 붙는 순간 재연결 횟수만큼 스레드가 쌓인다.
     */
    @Test
    void 소켓을_닫으면_HttpClient의_스레드도_같이_닫힌다() throws Exception {
        // 앞 테스트가 남긴 동명 스레드를 내 것으로 세지 않도록 차집합을 쓴다.
        List<Thread> before = liveHttpClientThreads();
        Set<Thread> created = new LinkedHashSet<>();

        for (int i = 0; i < 3; i++) {
            EngineIoSocket opened = EngineIoSocket.open(uri(), frame -> { }, () -> { }, BUDGET, NO_ABORT);
            // 닫기 전에 표본을 뜬다. 닫은 뒤에 뜨면 고친 코드에서는 이미 사라져
            // 있어 "만들어지긴 했나"를 확인할 수 없다.
            liveHttpClientThreads().stream().filter(t -> !before.contains(t)).forEach(created::add);
            opened.close();
        }

        // 양성 대조. 애초에 스레드가 안 생겼다면 "안 남는다"는 자동으로 참이 된다.
        assertThat(created)
                .as("HttpClient가 스레드를 만들지 않았다면 이 테스트는 아무것도 안 보고 있다")
                .isNotEmpty();

        awaitUntil(() -> created.stream().noneMatch(Thread::isAlive));
        assertThat(created.stream().filter(Thread::isAlive).map(Thread::getName).toList())
                .as("재연결이 붙으면 재연결 횟수만큼 이 스레드가 쌓인다")
                .isEmpty();
    }

    /** JDK가 HttpClient의 셀렉터·워커에 붙이는 이름이다. */
    private static List<Thread> liveHttpClientThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.getName().startsWith("HttpClient-"))
                .filter(Thread::isAlive)
                .toList();
    }

    private static void awaitUntil(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
    }

    private CountDownLatch openAndWaitForSystem() {
        CountDownLatch connected = new CountDownLatch(1);
        List<EngineIoFrame> seen = new CopyOnWriteArrayList<>();
        socket = EngineIoSocket.open(uri(), frame -> {
            seen.add(frame);
            if (frame.type() == EngineIoFrame.Type.EVENT) connected.countDown();
        }, () -> { }, BUDGET, NO_ABORT);
        return connected;
    }

    private URI uri() {
        return URI.create("ws://localhost:" + port
                + "/socket.io/?auth=T&EIO=3&transport=websocket");
    }
}
