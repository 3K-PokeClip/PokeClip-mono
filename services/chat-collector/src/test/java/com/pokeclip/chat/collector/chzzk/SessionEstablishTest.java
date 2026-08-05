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

        assertThatThrownBy(() -> session.open(Duration.ofMillis(500)))
                .isInstanceOf(SessionEstablishException.class)
                .extracting("stage", "reason")
                .containsExactly(EstablishStage.WAITING_CONNECTED, StopReason.ESTABLISH_TIMEOUT);
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
    }

    private ChatSession newSession() {
        String base = "http://localhost:" + port;
        return new ChatSession(new ChzzkSessionClient(RestClient.create(), base, "test-token"));
    }
}
