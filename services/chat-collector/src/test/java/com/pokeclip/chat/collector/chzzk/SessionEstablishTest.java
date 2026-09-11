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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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

    /**
     * 🔴 <b>⑤와 ⑥ 사이의 창을 결정적으로 연다</b>(POK-234 감사 라운드 3 D1).
     *
     * <p>내가 라운드 2에서 「그 창을 여는 방법이 없다」고 판단했던 자리다. 시도했던 셋은
     * 전부 <b>중단 신호를 미리 켜는</b> 방향이라 언제나 ⑤(또는 그 앞)가 먼저 잡았다.
     * 감사가 찾은 네 번째 길은 <b>경합을 이기지 않는다</b> — {@code abort}가 주입된
     * {@link BooleanSupplier}라 <b>언제 참이 되는지가 검사의 것</b>이라는 점을 쓴다.
     *
     * <p>얼개는 이렇다. 가짜 서버의 {@code onSubscribeBeforeResponse}는 subscribed 프레임을
     * 쏜 <b>뒤</b>, ④의 응답을 붙들고 있는 동안 돈다. 그 훅에서 <b>프레임이 클라이언트에
     * 실제로 도착한 것</b>을 싱크로 확인하고 공급자를 무장시키면, ⑤의 래치는 이미 내려가
     * 있어 <b>⑤의 루프가 정확히 한 바퀴만 돈다</b>. 그래서 무장 뒤의 호출 횟수가
     * ⑤에서 1(거짓) · ⑥에서 2(참)로 <b>세어서 갈린다</b> — {@code sleep} 0이고 결정적이다.
     *
     * <p>지키는 것: 여기서 새면 후원 구독 REST가 <b>수립 예산 밖에서</b> 접속 2 + 읽기 5초를
     * 쓰고, 그 사이 {@code releaseAndClose}가 지나가면 방금 선 구독을 아무도 안 반납한다.
     */
    @Test
    void 구독_직후에_중단이_켜지면_후원_구독을_시작하지_않는다() {
        session = newSession();

        CountDownLatch subscribedSeen = new CountDownLatch(1);
        session.onFrame(frame -> {
            if (frame.payload() != null && frame.payload().contains("subscribed")) {
                subscribedSeen.countDown();
            }
        });

        AtomicBoolean armed = new AtomicBoolean();
        AtomicInteger checksAfterArmed = new AtomicInteger();
        behavior.onSubscribeBeforeResponse = () -> {
            try {
                if (!subscribedSeen.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("subscribed 프레임이 클라이언트에 안 왔다");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            armed.set(true);
        };
        // ⑤의 검사=1(거짓) · ⑥의 검사=2(참). 무장 전에는 단락 평가로 세지 않는다.
        BooleanSupplier abortAfterSubscribed =
                () -> armed.get() && checksAfterArmed.incrementAndGet() >= 2;

        assertThatThrownBy(() -> session.open(Duration.ofSeconds(5), abortAfterSubscribed))
                .isInstanceOf(SessionEstablishException.class)
                .extracting("stage")
                .as("⑤가 잡았다면 WAITING_SUBSCRIBED다 — 그러면 ⑥의 가드를 안 지나갔다")
                .isEqualTo(EstablishStage.SUBSCRIBE);
        assertThat(session.donationSubscription())
                .as("⑥이 돌았다면 SUBSCRIBED다")
                .isEqualTo(DonationSubscription.NONE);
    }

    private ChatSession newSession() {
        String base = "http://localhost:" + port;
        return new ChatSession(new ChzzkSessionClient(RestClient.create(), base, "test-token"));
    }

    /** 재시도 주기를 짧게 준다. 운영값 1분을 기다리면 검사가 못 선다. */
    private ChatSession newSession(java.time.Duration donationRetryPeriod) {
        String base = "http://localhost:" + port;
        return new ChatSession(
                new ChzzkSessionClient(RestClient.create(), base, "test-token"), donationRetryPeriod);
    }

    /**
     * 🔴 <b>후원 구독이 일시 실패하면 다시 시도한다</b>(봇 codex P1).
     *
     * <p>그 전에는 재시도가 <b>아예 없었다</b> — 429·5xx·시한 초과가 {@code FAILED}로
     * 기록되고 끝이었다. 유일한 호출이 수립 ⑥이라 <b>채팅 소켓이 건강한 동안에는 아무도
     * 다시 시도하지 않는다.</b> 채팅 소켓이 방송 내내 멀쩡한 것이 정상이므로,
     * 한 번 미끄러지면 그 방송의 후원이 통째로 안 들어온다.
     *
     * <p><b>「구독 호출이 두 번 이상 나갔다」로 잰다</b> — 상태만 보면 재시도가 아니라
     * 수립 한 번으로도 참이 될 수 있다.
     */
    @Test
    void 후원_구독이_일시_실패하면_다시_시도한다() throws Exception {
        behavior.subscribeDonationStatus = 503;
        ChatSession session = newSession(java.time.Duration.ofMillis(60));
        try {
            session.open(java.time.Duration.ofSeconds(5), () -> false);
            assertThat(session.donationSubscription())
                    .as("503은 권한 문제가 아니라 일시 실패다").isEqualTo(DonationSubscription.FAILED);

            behavior.subscribeDonationStatus = 200;
            long 시한 = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
            while (session.donationSubscription() != DonationSubscription.SUBSCRIBED
                    && System.nanoTime() < 시한) {
                Thread.sleep(20);
            }

            assertThat(session.donationSubscription())
                    .as("상대가 나은 뒤에도 FAILED로 굳어 있다 — 재시도가 없다")
                    .isEqualTo(DonationSubscription.SUBSCRIBED);
            assertThat(behavior.subscribeDonationCallCount())
                    .as("구독 호출이 한 번뿐이면 재시도가 안 나간 것이다").isGreaterThan(1);
        } finally {
            session.releaseAndClose();
        }
    }

    /**
     * 🔴 <b>반납이 지나간 뒤에는 재시도가 상태를 되살리지 않는다</b>(봇 claude).
     *
     * <p>CAS 만으로는 못 막는다 — {@code releaseAndClose} 가 {@code donation} 에
     * <b>{@code NONE} 을 쓰므로</b>, {@code NONE} 에서 출발한 재시도의
     * {@code compareAndSet(NONE, got)} 이 <b>성공해 버린다.</b> 그러면 등록부에서 이미
     * 지워진 자리를 알림이 되살려, 닫힌 세션이 창구에 영구히 「구독 중」으로 남고
     * 그 후원 구독은 아무도 반납하지 않는다.
     *
     * <p>구독 응답을 붙들어 둔 채 세션을 닫는다 — 응답이 돌아오는 시점에는
     * 이미 반납이 지나간 뒤다.
     *
     * <p>🔴 <b>이 검사가 못 재는 것</b>: {@code stopping} 확인을 지워도 알림 단언은
     * <b>초록</b>이다. <b>이유가 처음 적은 것과 달랐다</b> — 「{@code FAILED} 출발이라 CAS 가
     * 막는다」고 적었는데, 실측해 보니 <b>CAS 까지 가지도 않는다.</b> 종료가 이 스레드를
     * 인터럽트해 왕복이 깨지고 {@code got} 이 {@code FAILED} 로 와서 알림 구간 자체를
     * 안 지나간다(찍어서 확인: {@code got=FAILED stopping=true interrupted=true}).
     * <b>봇이 짚은 시나리오는 {@code NONE} 출발이고 그때만 CAS 가 뚫리는데</b>
     * ({@code compareAndSet(NONE, got)} 이 성공해 버린다) 그 갈래에는 여전히 그물이 없다.
     *
     * <p>🔴 <b>반납 쪽은 이 검사가 결정적으로 잰다</b>(로컬 리뷰 라운드 8).
     * 이 시나리오에서 {@code releaseAndClose} 는 그 순간의 값이 {@code FAILED} 라
     * 후원 반납을 <b>안 쏘고 지나간다</b> — 그 뒤에 선 구독을 재시도가 스스로 거두지
     * 않으면 <b>어느 경로로도 반납되지 않는다.</b> 그 갈래를 지우면 아래 마지막 단언이
     * 빨간불이다(주입으로 확인함).
     *
     * <p><b>그리고 이 검사가 「불명은 쏜다」를 잰다</b> — 위에 적은 대로 여기서 {@code got} 은
     * 인터럽트 때문에 {@code FAILED} 로 오지만 <b>서버에는 구독이 섰다.</b> 조건을
     * {@code SUBSCRIBED} 로 좁히면 이 단언이 빨간불이다(그렇게 써 봤다가 잡혔다).
     */
    @Test
    void 반납이_지나간_뒤의_재시도는_상태를_되살리지_않는다() throws Exception {
        behavior.subscribeDonationStatus = 503;
        ChatSession session = newSession(java.time.Duration.ofMillis(60));
        session.open(java.time.Duration.ofSeconds(5), () -> false);
        assertThat(session.donationSubscription()).isEqualTo(DonationSubscription.FAILED);

        java.util.List<DonationSubscription> 알림 =
                java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        session.onDonationSubscriptionChanged(알림::add);

        // 다음 재시도가 응답을 못 받고 매달리게 한다.
        CountDownLatch 붙들림 = new CountDownLatch(1);
        CountDownLatch 놓아줌 = new CountDownLatch(1);
        behavior.subscribeDonationStatus = 200;
        behavior.onSubscribeDonationBeforeResponse = () -> {
            붙들림.countDown();
            try {
                놓아줌.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        assertThat(붙들림.await(5, TimeUnit.SECONDS))
                .as("재시도가 구독을 안 쐈다 — 이 검사가 아무것도 안 잰다").isTrue();
        session.releaseAndClose();
        놓아줌.countDown();
        Thread.sleep(300);

        assertThat(알림)
                .as("닫힌 세션의 상태를 되살리면 창구가 영영 「구독 중」으로 답한다")
                .isEmpty();
        assertThat(session.donationSubscription()).isEqualTo(DonationSubscription.NONE);
        assertThat(behavior.unsubscribeDonationCallCount())
                .as("반납이 지나간 뒤에 선 구독은 재시도가 스스로 거둬야 한다 — "
                        + "releaseAndClose 는 그때 FAILED 를 보고 이미 안 쏘고 갔다")
                .isEqualTo(1);
    }

    /**
     * <b>재시도가 한 바퀴로 끝나지 않는다</b> — 실패가 이어져도 계속 돈다.
     * 기존 검사는 첫 바퀴에 성공해서 이 자리를 안 지나간다.
     *
     * <p>🔴 <b>이 검사가 못 재는 것</b>: 라운드 7이 잡은 CAS 버그(기대값을 루프 밖에서
     * 잡아 실제 값과 갈리는 것)는 <b>{@code NONE} 에서 출발할 때만</b> 난다.
     * {@code FAILED} 출발이면 기대값이 우연히 실제와 같아 주입해도 초록이다(확인함).
     * 그리고 {@code NONE} 출발 갈래에는 그물이 없다 — 이유는
     * {@code ChatSession.establish} 의 ⑥ 주석에 적어 뒀다.
     * <b>이 검사 이름을 그 버그를 잡는 것으로 읽지 마라.</b>
     */
    @Test
    void 재시도는_한_바퀴로_끝나지_않는다() throws Exception {
        behavior.subscribeDonationStatus = 503;
        ChatSession session = newSession(java.time.Duration.ofMillis(60));
        try {
            session.open(java.time.Duration.ofSeconds(5), () -> false);
            assertThat(session.donationSubscription()).isEqualTo(DonationSubscription.FAILED);

            int 수립직후 = behavior.subscribeDonationCallCount();
            long 시한 = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
            while (behavior.subscribeDonationCallCount() < 수립직후 + 2
                    && System.nanoTime() < 시한) {
                Thread.sleep(20);
            }
            assertThat(behavior.subscribeDonationCallCount())
                    .as("두 바퀴를 못 돌면 이 검사가 첫 바퀴만 재는 기존 검사와 같아진다")
                    .isGreaterThanOrEqualTo(수립직후 + 2);

            behavior.subscribeDonationStatus = 200;
            시한 = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
            while (session.donationSubscription() != DonationSubscription.SUBSCRIBED
                    && System.nanoTime() < 시한) {
                Thread.sleep(20);
            }

            assertThat(session.donationSubscription())
                    .as("여러 바퀴를 돈 뒤의 성공이 반영되지 않는다")
                    .isEqualTo(DonationSubscription.SUBSCRIBED);
        } finally {
            session.releaseAndClose();
        }
    }

    /**
     * <b>권한 거부는 다시 시도하지 않는다.</b> 401·403은 시간이 안 풀어 주므로 두드리면
     * 남의 서버에 부하만 준다 — 위 검사의 <b>반대 방향</b>이라 둘을 같이 본다.
     */
    @Test
    void 후원_권한_거부는_다시_시도하지_않는다() throws Exception {
        behavior.subscribeDonationStatus = 403;
        ChatSession session = newSession(java.time.Duration.ofMillis(60));
        try {
            session.open(java.time.Duration.ofSeconds(5), () -> false);
            assertThat(session.donationSubscription()).isEqualTo(DonationSubscription.REFUSED);
            int 수립직후 = behavior.subscribeDonationCallCount();

            Thread.sleep(400);   // 재시도가 있다면 여섯 바퀴는 돌 시간

            assertThat(behavior.subscribeDonationCallCount())
                    .as("거부에도 재시도가 붙었다 — 시간이 안 풀어 주는 실패다")
                    .isEqualTo(수립직후);
        } finally {
            session.releaseAndClose();
        }
    }

    /**
     * 🔴 <b>{@code close()}만 부르는 경로에서도 재시도가 멈춘다.</b> 수립 실패·전송 절단이
     * 그 길이고, 거기서는 <b>세션 키가 안 비워진다</b> — 재시도 루프가 키를 보고 스스로
     * 나가는 길이 없어 <b>영원히 돈다.</b>
     *
     * <p>🔴 <b>구독 호출 수로 재면 안 된다.</b> 처음에 그렇게 썼는데
     * {@code releaseAndClose} 경로는 키를 비우므로 <b>중단 코드를 지워도 초록</b>이었다.
     * 실제로 주입해 보니 검사는 초록인데 <b>그 판의 전체 실행이 48분</b>이 걸렸다 —
     * 멈추지 않은 재시도가 60ms마다 가짜 서버를 두드리고 있었다.
     * <b>초록이 「멈췄다」를 뜻하지 않은 자리다.</b> 스레드 생존으로 바꿔 잰다.
     */
    @Test
    void close만_불러도_후원_재시도가_멈춘다() throws Exception {
        behavior.subscribeDonationStatus = 503;
        ChatSession session = newSession(java.time.Duration.ofMillis(60));
        session.open(java.time.Duration.ofSeconds(5), () -> false);
        assertThat(session.donationRetryAlive()).as("재시도가 시작조차 안 됐다").isTrue();

        session.close();

        long 시한 = System.nanoTime() + java.time.Duration.ofSeconds(3).toNanos();
        while (session.donationRetryAlive() && System.nanoTime() < 시한) {
            Thread.sleep(20);
        }
        assertThat(session.donationRetryAlive())
                .as("close 만으로는 안 멈춘다 — 그 경로는 키를 안 비워 영원히 돈다").isFalse();
    }

    /** 반납 경로도 멈춘다. 위와 갈래가 달라 둘을 같이 본다. */
    @Test
    void 반납_경로에서도_후원_재시도가_멈춘다() throws Exception {
        behavior.subscribeDonationStatus = 503;
        ChatSession session = newSession(java.time.Duration.ofMillis(60));
        session.open(java.time.Duration.ofSeconds(5), () -> false);

        session.releaseAndClose();

        long 시한 = System.nanoTime() + java.time.Duration.ofSeconds(3).toNanos();
        while (session.donationRetryAlive() && System.nanoTime() < 시한) {
            Thread.sleep(20);
        }
        assertThat(session.donationRetryAlive()).isFalse();
    }
}
