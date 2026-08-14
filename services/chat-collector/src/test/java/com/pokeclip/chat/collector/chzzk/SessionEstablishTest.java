package com.pokeclip.chat.collector.chzzk;

import com.pokeclip.chat.collector.StopReason;
import com.pokeclip.chat.collector.fake.FakeChzzkBehavior;
import com.pokeclip.chat.collector.fake.FakeChzzkTest;
import com.pokeclip.chat.collector.support.IntegrationTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@FakeChzzkTest
class SessionEstablishTest extends IntegrationTestSupport {

    /**
     * 아무도 안 듣는 포트. 루프백이라 즉시 거부가 돌아와 <b>결정적으로</b> 실패한다.
     * 시한 초과 쪽은 라우팅되지 않는 주소가 있어야 만들어져 느리고 환경을 탄다.
     */
    private static final int DEAD_PORT = 1;

    /** 중단 신호가 없는 호출. 중단은 아래 한 테스트만 켠다. */
    private static final BooleanSupplier NO_ABORT = () -> false;

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

        ChatSession.Established established = session.open(Duration.ofSeconds(5), NO_ABORT);

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

        assertThatThrownBy(() -> session.open(deadline, NO_ABORT))
                .isInstanceOf(SessionEstablishException.class)
                .extracting("stage", "reason")
                .containsExactly(EstablishStage.WAITING_CONNECTED, StopReason.ESTABLISH_TIMEOUT);

        // 양성 대조 — <b>실제로 기다렸는가</b>를 잰다.
        //
        // WAITING_CONNECTED는 첫 대기 단계라 "connected를 기다리다 시한이 났다"와
        // "①②가 시한을 다 써서 대기를 시작조차 못 했다"가 같은 값으로 나온다.
        // await()가 remaining <= 0이면 래치를 아예 안 보고 던지기 때문이다.
        // 그러면 sendConnected = false가 무의미해지는데 기대한 stage는 그대로 나오니
        // 영원히 초록이다. 형제(WAITING_SUBSCRIBED)는 그 값 자체가 ①~④ 통과의
        // 증거라 자기 전제를 스스로 검증하지만, 이쪽은 못 한다.
        //
        // 자는 <b>②가 끝난 시점</b>에 둔다. 테스트가 자기 시계로 재면 ①②가 느린
        // 경우와 ③에서 기다린 경우가 같은 값으로 나와, 막으려던 구멍이 그대로 남는다.
        //
        // "WS가 붙었나"로는 못 잡는다 — ①②는 같은 JVM 루프백이라 어떤 현실적인
        // 시한에서도 끝나고, 접속 흔적은 ③에서 기다렸든 안 기다렸든 남는다.
        Duration waitedAfterConnect = behavior.sinceConnectionEstablished();

        assertThat(waitedAfterConnect)
                .as("②가 끝난 뒤로 시한의 절반도 안 흘렀다면 connected를 기다린 적이 없다. "
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
        assertThatThrownBy(() -> session.open(Duration.ofSeconds(3), NO_ABORT))
                .isInstanceOf(SessionEstablishException.class)
                .extracting("stage", "reason")
                .containsExactly(EstablishStage.WAITING_SUBSCRIBED, StopReason.ESTABLISH_TIMEOUT);
    }

    /**
     * <b>중단 신호가 서면 시한을 다 안 쓰고 끊는다.</b>
     *
     * <p>이게 없으면 멈추려는 쪽이 둘 중 하나를 골라야 한다 — 수립 시한(운영 15초)만큼
     * 기다려 종료 예산을 넘기거나, 짧게 기다리고 뒷정리 중인 스레드를 인터럽트해
     * 급사 경로를 만들거나. 급사면 서버가 세션을 놓아주는 데 10초~4분 42초가 걸리고
     * 상한이 3개라 금방 못 붙게 된다.
     *
     * <p><b>단언을 시한과의 비율로 쓴다.</b> 신호가 안 보이면 시한을 통째로 쓰므로
     * 절반이 그 둘을 자릿수로 가른다 — 조각이 100ms라 실제 값은 그보다 훨씬 작다.
     */
    /**
     * <b>중단 신호가 이미 서 있으면 세션 발급조차 하지 않는다.</b>
     *
     * <p>①은 나가는 순간 접속 2초 + 읽기 5초를 통째로 쓸 수 있고, 그 사이에
     * {@code stop()}은 2초만 기다리고 지나간다. 그 뒤에 ②가 여는 소켓은 정리 가드가
     * 이미 소모돼 아무도 안 닫는다 — 서버 쪽 자리가 죽은 전송을 알아챌 때까지
     * 10초~4분 42초 남고 상한은 3개다.
     *
     * <p><b>발급 횟수가 이 검사의 본체다.</b> 예외 모양만 보면 "①을 타고 나서 ②에서
     * 걸렸다"와 구분되지 않는다.
     */
    @Test
    void 중단_신호가_이미_서_있으면_세션_발급조차_안_한다() {
        session = newSession();

        assertThatThrownBy(() -> session.open(Duration.ofSeconds(3), () -> true))
                .isInstanceOf(SessionEstablishException.class)
                .hasMessageContaining("aborted")
                .extracting("stage", "reason")
                .containsExactly(EstablishStage.AUTH, StopReason.ESTABLISH_TIMEOUT);

        assertThat(behavior.authCallCount())
                .as("멈추라고 한 뒤에 나간 REST는 시한만큼 매달리고, 그 뒤 여는 소켓은 아무도 안 닫는다")
                .isZero();
    }

    @Test
    void 중단_신호가_서면_수립_시한을_다_안_쓰고_끊는다() {
        behavior.sendConnected = false;         // ③이 영영 안 온다
        session = newSession();

        Duration deadline = Duration.ofSeconds(3);
        long began = System.nanoTime();

        // <b>②가 끝난 뒤에 신호가 선다.</b> 처음부터 세워 두면 ① 앞에서 걸려
        // 이 테스트가 ③의 중단을 한 번도 안 지난다 — 그쪽은 위 형제가 본다.
        assertThatThrownBy(() -> session.open(deadline,
                () -> !behavior.sinceConnectionEstablished().isZero()))
                .isInstanceOf(SessionEstablishException.class)
                // 사유는 시한 초과와 같은 값이다 — 재시도 판단이 이걸로 안 갈리고,
                // 새 값을 만들면 9b의 재시도 분류표에 "실제로는 안 오는 값"이 한 줄 는다.
                // 대신 detail로 가른다. 로그에서 "우리가 멈춘 것"과 "서버가 늦은 것"은
                // 다른 사건이고, 재연결이 반복 실패할 때 그 구분이 첫 단서다.
                .hasMessageContaining("aborted")
                .extracting("stage", "reason")
                .containsExactly(EstablishStage.WAITING_CONNECTED, StopReason.ESTABLISH_TIMEOUT);

        assertThat(Duration.ofNanos(System.nanoTime() - began))
                .as("중단 신호를 안 보면 수립이 시한을 통째로 쓰고, 그만큼 종료가 매달린다")
                .isLessThan(deadline.dividedBy(2));
    }

    /**
     * T9. 만료 토큰은 @NotBlank를 통과한다. 부팅은 성공하고 여기서만 걸린다.
     *
     * <p>401은 재시도해도 영원히 안 풀린다. 5xx와 같은 사유로 묶으면 둘 중 하나가 틀린다.
     */
    @Test
    void 세션_발급이_401이면_거부로_분류한다() {
        behavior.authStatus = 401;
        session = newSession();

        assertThatThrownBy(() -> session.open(Duration.ofSeconds(5), NO_ABORT))
                .isInstanceOf(SessionEstablishException.class)
                .extracting("stage", "reason")
                .containsExactly(EstablishStage.AUTH, StopReason.SESSION_AUTH_REJECTED);

        // 재시도가 있으면 가짜 서버가 여러 번 받는다.
        assertThat(behavior.authCallCount())
                .as("ChatSession 안에는 재시도 루프가 없다. 루프는 러너가 갖는다")
                .isEqualTo(1);
    }

    /**
     * 403도 거부다. 이 줄이 없으면 조건에서 403을 지워도 전 테스트가 초록이다
     * (변이로 확인했다). 그러면 Scope 부족·동의 철회가 일시 실패로 분류되어
     * 러너가 영원히 재시도한다 — 이 태스크가 막으려는 바로 그 모양이다.
     */
    @Test
    void 세션_발급이_403이면_거부로_분류한다() {
        behavior.authStatus = 403;
        session = newSession();

        assertThatThrownBy(() -> session.open(Duration.ofSeconds(5), NO_ABORT))
                .isInstanceOf(SessionEstablishException.class)
                .extracting("stage", "reason")
                .containsExactly(EstablishStage.AUTH, StopReason.SESSION_AUTH_REJECTED);
    }

    /** 5xx는 서버가 잠깐 아픈 것이다. 거부와 같은 사유로 묶으면 영구 정지한다. */
    @Test
    void 세션_발급이_500이면_거부와_다른_사유로_분류한다() {
        behavior.authStatus = 500;
        session = newSession();

        assertThatThrownBy(() -> session.open(Duration.ofSeconds(5), NO_ABORT))
                .isInstanceOf(SessionEstablishException.class)
                .extracting("stage", "reason")
                .containsExactly(EstablishStage.AUTH, StopReason.SESSION_AUTH_FAILED);
    }

    /**
     * <b>구독 401은 발급 401과 같은 규칙을 받는다 — 재시도해도 영원히 안 풀린다.</b>
     *
     * <p>발급이 200인데 구독만 거부되는 상태가 실제로 있다: 토큰은 살아 있고
     * 채팅 Scope나 동의만 빠진 경우다. 그걸 {@code SUBSCRIBE_FAILED}로 뭉치면
     * 재시도 가능으로 분류되어 <b>못 쓰는 토큰으로 세션 발급부터 영원히 돈다.</b>
     */
    @Test
    void 구독이_401이면_거부로_분류한다() {
        behavior.subscribeStatus = 401;
        session = newSession();

        assertThatThrownBy(() -> session.open(Duration.ofSeconds(5), NO_ABORT))
                .isInstanceOf(SessionEstablishException.class)
                // 상태 코드를 잃지 않는다. 재시도 판단은 사유가 지지만, 401인지 403인지는
                // 사람이 "토큰이 죽었나 Scope가 빠졌나"를 좁히는 첫 단서다.
                .hasMessageContaining("status=401")
                .extracting("stage", "reason")
                .containsExactly(EstablishStage.SUBSCRIBE, StopReason.SUBSCRIBE_REJECTED);
    }

    /**
     * 403도 거부다. 이 줄이 없으면 조건에서 403을 지워도 전체가 초록이고,
     * 그러면 Scope 부족이 일시 실패로 분류되어 러너가 영원히 재시도한다.
     */
    @Test
    void 구독이_403이면_거부로_분류한다() {
        behavior.subscribeStatus = 403;
        session = newSession();

        assertThatThrownBy(() -> session.open(Duration.ofSeconds(5), NO_ABORT))
                .isInstanceOf(SessionEstablishException.class)
                .hasMessageContaining("status=403")
                .extracting("stage", "reason")
                .containsExactly(EstablishStage.SUBSCRIBE, StopReason.SUBSCRIBE_REJECTED);
    }

    /**
     * 5xx는 서버가 잠깐 아픈 것이다. <b>양성 대조다</b> — 이 줄이 없으면
     * 구독 실패를 통째로 거부로 바꿔 놔도 위 둘이 초록이고, 그때는 5xx 한 번에
     * 영구 정지해 그 방송의 남은 채팅이 전부 사라진다.
     */
    @Test
    void 구독이_500이면_거부와_다른_사유로_분류한다() {
        behavior.subscribeStatus = 500;
        session = newSession();

        assertThatThrownBy(() -> session.open(Duration.ofSeconds(5), NO_ABORT))
                .isInstanceOf(SessionEstablishException.class)
                .extracting("stage", "reason")
                .containsExactly(EstablishStage.SUBSCRIBE, StopReason.SUBSCRIBE_FAILED);
    }

    /**
     * ② 접속 자체가 성립하지 않는 경우.
     *
     * <p>CONNECT 세분화는 진단 전용이라 재시도 판단에는 안 쓴다. <b>그래도 검사가
     * 있어야 한다</b> — 분류가 틀리면 재연결이 반복 실패할 때 로그가 거짓말을 하고,
     * 사람은 그 줄을 믿고 엉뚱한 곳을 판다. 이 줄이 없으면 {@code CONNECT_REFUSED}
     * 분기를 통째로 지워도 전 테스트가 초록이다(변이로 확인했다).
     */
    @Test
    void 세션_url이_죽은_포트면_접속_거부로_분류한다() {
        behavior.sessionUrlPort = DEAD_PORT;
        session = newSession();

        assertThatThrownBy(() -> session.open(Duration.ofSeconds(5), NO_ABORT))
                .isInstanceOf(SessionEstablishException.class)
                .extracting("stage", "reason")
                .containsExactly(EstablishStage.CONNECT, StopReason.CONNECT_REFUSED);
    }

    /** POK-86이 이 덩어리를 통째로 다시 부른다. 한 번만 도는 코드면 안 된다. */
    @Test
    void 같은_객체로_두_번_수립할_수_있다() {
        session = newSession();

        ChatSession.Established first = session.open(Duration.ofSeconds(5), NO_ABORT);
        session.close();
        ChatSession.Established second = session.open(Duration.ofSeconds(5), NO_ABORT);

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
        session.open(Duration.ofSeconds(5), NO_ABORT);

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
