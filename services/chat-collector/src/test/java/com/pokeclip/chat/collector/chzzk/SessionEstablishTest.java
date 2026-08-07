package com.pokeclip.chat.collector.chzzk;

import com.pokeclip.chat.collector.StopReason;
import com.pokeclip.chat.collector.fake.FakeChzzkBehavior;
import com.pokeclip.chat.collector.fake.FakeChzzkTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@FakeChzzkTest
class SessionEstablishTest {

    @LocalServerPort int port;
    @Autowired FakeChzzkBehavior behavior;

    private ChatSession session;

    @AfterEach
    void tearDown() {
        if (session != null) session.close();
        behavior.reset();
    }

    @Test
    void 다섯_단계를_통과하면_핸드셰이크와_소켓을_돌려준다() {
        session = newSession();

        ChatSession.Established established = session.open(Duration.ofSeconds(5));

        assertThat(established.handshake().pingInterval()).isEqualTo(Duration.ofMillis(1000));
        assertThat(established.socket()).isNotNull();
    }

    /**
     * T13. connected가 안 오면 무한 대기가 된다 — ping은 아직 시작 전이라
     * 실패 조건 둘 다 안 걸리고, health도 DOWN이 아니고, 로그도 안 나온다.
     */
    @Test
    void connected가_안_오면_시한에서_끊고_어느_단계인지_남긴다() {
        behavior.sendConnected = false;
        session = newSession();

        Duration deadline = Duration.ofSeconds(3);
        long startedAt = System.nanoTime();

        assertThatThrownBy(() -> session.open(deadline))
                .isInstanceOf(SessionEstablishException.class)
                .extracting("stage", "reason")
                .containsExactly(EstablishStage.WAITING_CONNECTED, StopReason.ESTABLISH_TIMEOUT);

        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        // 양성 대조 — <b>실제로 기다렸는가</b>를 잰다.
        //
        // WAITING_CONNECTED는 첫 대기 단계라 "connected를 기다리다 시한이 났다"와
        // "①②가 시한을 다 써서 대기를 시작조차 못 했다"가 같은 값으로 나온다.
        // await()가 remaining <= 0이면 래치를 아예 안 보고 던지기 때문이다.
        // 그러면 sendConnected = false가 무의미해지는데 기대한 stage는 그대로 나오니
        // 영원히 초록이다. 형제(WAITING_SUBSCRIBED)는 그 값 자체가 ①~④ 통과의
        // 증거라 자기 전제를 스스로 검증하지만, 이쪽은 못 한다.
        //
        // "WS가 붙었나"로는 못 잡는다 — EngineIoSocket.open()이 join()으로 블로킹해
        // 시한과 무관하게 ②를 끝내므로, 시한을 1ns로 줘도 접속 흔적은 남는다.
        // 실제로 그렇게 확인했다(0.06초 만에 끝나면서 통과했다).
        assertThat(elapsed)
                .as("대기에 시간을 안 썼다면 connected를 기다린 적이 없다. "
                        + "①②가 시한을 삼킨 것이고 sendConnected=false는 검사되지 않았다")
                .isGreaterThan(deadline.dividedBy(2));
    }

    /**
     * ⑤도 시한에 걸려야 한다. 구독 REST는 200인데 subscribed 프레임이 안 오는
     * 상태가 실제로 있고(연결은 살아 있는데 채팅만 안 온다), 그때 어느 단계에서
     * 멈췄는지를 남기는 것이 EstablishStage의 존재 이유다.
     */
    @Test
    void subscribed가_안_오면_시한에서_끊고_어느_단계인지_남긴다() {
        behavior.sendSubscribed = false;
        session = newSession();

        // 시한은 넉넉해야 한다. 이 테스트가 겨누는 것은 "시한이 났다"가 아니라
        // "⑤에서 났다"인데, 시한이 빡빡하면 ①②③이 밀릴 때 WAITING_CONNECTED에서
        // 먼저 걸려 엉뚱한 단계를 검사하게 된다. 1초로 뒀다가 실제로 그렇게 됐다.
        assertThatThrownBy(() -> session.open(Duration.ofSeconds(3)))
                .isInstanceOf(SessionEstablishException.class)
                .extracting("stage", "reason")
                .containsExactly(EstablishStage.WAITING_SUBSCRIBED, StopReason.ESTABLISH_TIMEOUT);
    }

    /** T9. 만료 토큰은 @NotBlank를 통과한다. 부팅은 성공하고 여기서만 걸린다. */
    @Test
    void 세션_발급이_401이면_이유를_남기고_재시도하지_않는다() {
        behavior.authStatus = 401;
        session = newSession();

        assertThatThrownBy(() -> session.open(Duration.ofSeconds(5)))
                .isInstanceOf(SessionEstablishException.class)
                .extracting("stage", "reason")
                .containsExactly(EstablishStage.AUTH, StopReason.SESSION_AUTH_FAILED);

        // 재시도가 있으면 가짜 서버가 여러 번 받는다.
        assertThat(behavior.authCallCount())
                .as("조용한 재시도 루프를 만들지 않는다. 재연결은 POK-86이다")
                .isEqualTo(1);
    }

    /** POK-86이 이 덩어리를 통째로 다시 부른다. 한 번만 도는 코드면 안 된다. */
    @Test
    void 같은_객체로_두_번_수립할_수_있다() {
        session = newSession();

        ChatSession.Established first = session.open(Duration.ofSeconds(5));
        session.close();
        ChatSession.Established second = session.open(Duration.ofSeconds(5));

        assertThat(first.socket()).isNotSameAs(second.socket());
        // 소켓만 갈아 끼운 것과 절차를 처음부터 다시 탄 것은 다르다. 세션 URL은
        // 재사용이 안 되므로 ①부터 다시 타야 하고, 그 증거는 REST 호출 횟수다.
        assertThat(behavior.authCallCount())
                .as("①부터 다시 타지 않았다면 재진입이 아니라 소켓만 바꾼 것이다")
                .isEqualTo(2);
    }

    /**
     * 싱크는 WS 수신 콜백 안에서 불린다. 예외가 밖으로 나가면 onError로 가
     * <b>그 한 건 때문에 방송 전체 수신이 멈춘다.</b> 디코더는 null을 주도록
     * 방어했지만 싱크에는 방어가 없었다 — 태스크 9가 붙일 진짜 싱크가 던지는
     * 순간 실체가 된다.
     */
    @Test
    void 싱크가_던져도_수신이_멈추지_않는다() throws Exception {
        session = newSession();
        AtomicInteger seen = new AtomicInteger();
        session.onFrame(frame -> {
            seen.incrementAndGet();
            throw new IllegalStateException("싱크가 터졌다");
        });

        // connected가 EVENT라 이미 싱크를 한 번 지난다. 거기서 예외가 새면
        // 소켓이 죽어 ④·⑤가 통째로 실패하므로 open() 자체가 못 돌아온다.
        session.open(Duration.ofSeconds(5));

        behavior.emitChat("{\"content\":\"x\",\"messageTime\":1}");
        behavior.emitChat("{\"content\":\"y\",\"messageTime\":2}");

        // 첫 예외가 수신을 멈췄다면 뒤이은 채팅이 한 건도 안 온다.
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (seen.get() < 4 && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(seen.get())
                .as("connected·subscribed·채팅 둘 — 넷이 다 싱크를 지나야 수신이 산 것이다")
                .isGreaterThanOrEqualTo(4);
        assertThat(session.sinkFailureCount())
                .as("삼켰으면 세야 한다. 안 세면 수신은 사는데 처리가 죽은 것을 못 본다")
                .isEqualTo(seen.get());
    }

    private ChatSession newSession() {
        String base = "http://localhost:" + port;
        return new ChatSession(new ChzzkSessionClient(RestClient.create(), base, "test-token"));
    }
}
