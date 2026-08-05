package com.pokeclip.chat.collector.engineio;

import com.pokeclip.chat.collector.fake.FakeChzzkBehavior;
import com.pokeclip.chat.collector.fake.FakeChzzkTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@FakeChzzkTest
class EngineIoSocketTest {

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

        assertThat(behavior.receivedFrames())
                .as("40을 보내면 전송은 안 끊기고 disconnect 이벤트만 뜨는 상태가 된다")
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

    @Test
    void 서버가_끊으면_onClosed가_불린다() throws Exception {
        behavior.pingIntervalMillis = 200;
        behavior.pingTimeoutMillis = 480;

        CountDownLatch closed = new CountDownLatch(1);
        socket = EngineIoSocket.open(uri(), frame -> { }, closed::countDown);

        assertThat(closed.await(5, TimeUnit.SECONDS))
                .as("서버가 조용히 끊었는데 우리가 모르면 그게 8/1 사고다")
                .isTrue();
    }

    private CountDownLatch openAndWaitForSystem() {
        CountDownLatch connected = new CountDownLatch(1);
        List<EngineIoFrame> seen = new CopyOnWriteArrayList<>();
        socket = EngineIoSocket.open(uri(), frame -> {
            seen.add(frame);
            if (frame.type() == EngineIoFrame.Type.EVENT) connected.countDown();
        }, () -> { });
        return connected;
    }

    private URI uri() {
        return URI.create("ws://localhost:" + port
                + "/socket.io/?auth=T&EIO=3&transport=websocket");
    }
}
