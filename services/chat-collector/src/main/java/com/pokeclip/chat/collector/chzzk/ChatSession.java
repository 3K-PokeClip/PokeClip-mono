package com.pokeclip.chat.collector.chzzk;

import com.pokeclip.chat.collector.StopReason;
import com.pokeclip.chat.collector.engineio.EngineIoFrame;
import com.pokeclip.chat.collector.engineio.EngineIoSocket;
import com.pokeclip.chat.collector.engineio.Handshake;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * 수립 절차 ①~⑤를 한 덩어리로 돈다. <b>재진입 가능하다</b> —
 * POK-86이 강제 절단 뒤에 이 메서드를 통째로 다시 부른다.
 * 세션 URL은 약 30초면 만료되고 재사용이 안 되므로 절차를 처음부터 다시 탄다.
 *
 * <p><b>전체에 시한을 건다.</b> 시한이 없으면 connected가 안 올 때 무한 대기가
 * 되는데, 그때는 ping이 아직 시작 전이라 실패 조건이 하나도 안 걸리고
 * health도 DOWN이 아니고 로그도 안 나온다.
 */
public class ChatSession implements AutoCloseable {

    /** 수립 대기를 이만큼씩 끊어 중단 신호를 본다. 종료가 최대 이만큼 늦어진다. */
    private static final Duration ABORT_CHECK_SLICE = Duration.ofMillis(100);

    public record Established(Handshake handshake, EngineIoSocket socket) { }

    private static final Logger log = LoggerFactory.getLogger(ChatSession.class);

    private final ChzzkSessionClient client;
    private final AtomicReference<EngineIoSocket> current = new AtomicReference<>();

    private volatile Consumer<EngineIoFrame> frameSink = frame -> { };
    private volatile Runnable closedSink = () -> { };

    /** 삼킨 싱크 예외의 수. 안 세면 수신은 사는데 처리가 통째로 죽은 것을 못 본다. */
    private final AtomicLong sinkFailures = new AtomicLong();

    /**
     * 🔴 <b>후원 구독이 일시 실패했을 때 다시 시도하는 주기</b>(봇 codex P1이 잡았다).
     *
     * <p>그 전에는 재시도가 <b>아예 없었다</b> — {@code subscribeDonation}이 429·5xx·시한
     * 초과를 {@code FAILED}로 바꿔 값으로 돌려주고, 부르는 쪽은 그것을 상태로 적기만 했다.
     * 유일한 호출이 수립 ⑥이라 <b>채팅 소켓이 건강한 동안에는 아무도 다시 시도하지 않는다.</b>
     * 채팅 소켓은 방송 내내 멀쩡한 것이 정상이므로, 후원 구독이 한 번 미끄러지면
     * <b>그 방송의 후원이 통째로 안 들어온다.</b> 후원은 아카이브가 없어 되찾을 길도 없다.
     *
     * <p>재부착 주기와 같은 1분이다. 더 짧게 잡을 이유가 없다 — 상대가 429를 준 상황이면
     * 자주 두드리는 것이 오히려 나쁘고, 후원은 한 건이 늦는 것보다 <b>영영 안 오는 것</b>이
     * 문제였다.
     */
    static final Duration DONATION_RETRY_PERIOD = Duration.ofMinutes(1);

    /** 재시도 주기. 검사가 짧게 바꿔 잰다 — 1분을 기다리면 검사가 못 선다. */
    private final Duration donationRetryPeriod;

    /** 종료 신호. 재시도 스레드가 매 바퀴 본다. */
    private final AtomicBoolean stopping = new AtomicBoolean();

    private volatile Thread donationRetry;

    /**
     * 재시도 스레드가 살아 있나. <b>검사가 「멈췄다」를 이것으로만 잴 수 있다</b> —
     * 구독 호출 수로 재면 {@code releaseAndClose} 경로에서 <b>자동으로 참이 된다</b>:
     * 그 길은 세션 키를 비우고, 키가 비면 다음 바퀴가 스스로 돌아 나가기 때문이다.
     * 즉 호출 수로 잰 검사는 {@code stopDonationRetry}를 지워도 초록이다(실측).
     */
    boolean donationRetryAlive() {
        Thread retry = donationRetry;
        return retry != null && retry.isAlive();
    }

    public ChatSession(ChzzkSessionClient client) {
        this(client, DONATION_RETRY_PERIOD);
    }

    public ChatSession(ChzzkSessionClient client, Duration donationRetryPeriod) {
        this.client = client;
        this.donationRetryPeriod = donationRetryPeriod;
    }

    /** 종료할 때 구독 반납에 쓴다. 수립이 끝나야 채워진다. */
    private final AtomicReference<String> currentSessionKey = new AtomicReference<>();

    /**
     * 이 세션의 후원 구독 결과. 수립 ⑥에서 정해지고 반납할 때 비워진다.
     * <b>NONE이면 반납할 것이 없다</b> — 구독한 적 없는 키로 반납 REST를 한 번 더 쏘지 않는다.
     */
    private final AtomicReference<DonationSubscription> donation =
            new AtomicReference<>(DonationSubscription.NONE);

    /** 이 세션이 후원을 구독했나. 등록부가 창구에 실을 값으로 읽어 간다. */
    public DonationSubscription donationSubscription() { return donation.get(); }

    /**
     * 🔴 <b>재시도가 상태를 바꾸면 알린다</b>(봇 codex P2가 잡았다).
     *
     * <p>부르는 쪽은 {@code open()}이 돌아온 <b>뒤 한 번</b> {@link #donationSubscription()}을
     * 읽어 창구용 등록부에 적는다. 재시도는 그 뒤에 값을 바꾸므로, 알림이 없으면
     * <b>재시도가 성공해도 창구는 방송이 끝날 때까지 「failed」로 보고한다</b> —
     * 실제로는 후원이 잘 들어오고 있는데 화면이 그럴듯하게 틀린다.
     *
     * <p>기본값은 아무것도 안 한다 — 옛 경로({@code CollectorRunner})는 등록부가 없다.
     */
    private volatile Consumer<DonationSubscription> donationSink = state -> { };

    public void onDonationSubscriptionChanged(Consumer<DonationSubscription> sink) {
        this.donationSink = sink != null ? sink : state -> { };
    }

    /** 삼킨 싱크 예외의 수. 삼키기만 하고 안 세면 조용한 실패를 우리가 만드는 것이다. */
    public long sinkFailureCount() { return sinkFailures.get(); }

    /** 종료할 때 구독을 반납하려면 이 값이 필요하다. 수립 전이면 null이다. */
    public String sessionKey() { return currentSessionKey.get(); }

    /**
     * 구독 반납의 결말. <b>{@code SKIPPED}와 {@code FAILED}를 한 값으로 묶으면
     * "반납할 세션 키가 없었다"(수립 실패)와 "반납을 보냈는데 실패했다"가 같은
     * 로그 한 줄이 되어 아무도 못 가른다.</b>
     */
    public enum Release {
        RETURNED("returned"),
        FAILED("failed"),
        SKIPPED("skipped");

        private final String label;

        Release(String label) { this.label = label; }

        @Override
        public String toString() { return label; }
    }

    /**
     * 반납 REST 하나의 최악. {@code spring.http.clients}의 접속 2초 + 읽기 5초다.
     *
     * <p><b>관측이 아니라 시한이다</b> — 실측 왕복은 55~69ms라 평시엔 근처도 안 간다.
     * {@code SessionRegistry.CLOSE_ALL_BUDGET}(8초 = 반납 7 + 소켓 닫기 1)의 「반납 7」이
     * 이 값이고, <b>반납이 둘이 돼도 그 7이 그대로여야</b> 그 예산이 선다.
     */
    private static final Duration RELEASE_BUDGET = Duration.ofSeconds(7);

    /**
     * 구독을 반납하고 소켓을 닫는다. 반납이 먼저다 — 소켓을 먼저 닫으면
     * 서버가 세션을 정리하는 중이라 반납이 무의미해질 수 있다.
     *
     * <p>🔴 <b>후원 반납과 채팅 반납을 나란히 보낸다</b>(POK-234 감사 라운드 2 A2).
     * 직렬로 쏘면 최악이 7 + 7 = 14초라 {@code CLOSE_ALL_BUDGET} 8초를 넘고, 그때
     * {@code awaitClosed}는 취소하지 않고 로그만 남기고 돌아가므로 <b>채팅 반납이 나가기
     * 전에 프로세스가 종료 유예에 잘린다</b> — 그 예산이 막으려던 것(계정당 자리 3개)이
     * 정확히 그 사고다. 나란히면 최악이 {@code max(7, 7)} = 7초로 예산 안이다.
     * <b>겹치는 것을 실측했다</b>: 가짜 서버의 반납 둘을 1초씩 붙들면 직렬 2,006ms →
     * 나란히 1,0xx ms({@code SessionShutdownTest.후원_반납과_채팅_반납이_나란히_나간다}).
     *
     * <p><b>후원 반납의 값은 확인하지 않았다.</b> 여기 한때 「그 자리도 계정당 연결 상한 안에
     * 든다」고 적혀 있었는데 <b>거짓이다</b> — 공식 문서상 연결은 <b>계정당 3개</b>이고 이벤트
     * 구독은 <b>세션당 30개</b>로 별개 한도이며, 우리는 세션당 둘만 쓴다. 즉 후원 반납이
     * 연결 자리를 앞당기지 않는다. 그런데도 보내는 이유는 <b>세션 정리를 앞당기는 쪽이
     * 안전하기 때문</b>이고, 그 효과를 재 본 적은 없다. 실측 근거가 있는 것은 채팅 반납뿐이다
     * (안 보내면 자리가 10초~4분 42초 남는다).
     *
     * @return 반납의 결말. <b>어느 결말이든 소켓은 닫는다</b>
     */
    public Release releaseAndClose() {
        // 반납보다 먼저 멈춘다 — 키를 비운 뒤에 재시도가 한 바퀴 더 돌면 소모된 키로
        // 구독을 쏜다. 기다리지는 않는다(종료 예산).
        stopDonationRetry();
        String key = currentSessionKey.getAndSet(null);
        Release result;
        if (key == null || key.isBlank()) {
            result = Release.SKIPPED;
        } else {
            long endAt = System.nanoTime() + RELEASE_BUDGET.toNanos();
            // 구독하지 않았으면(NONE·REFUSED·FAILED) 아무것도 안 쏜다 — 구독한 적 없는
            // 키로 왕복을 하나 더 만들 이유가 없다. 그때는 스레드도 안 만든다.
            Thread donationRelease = null;
            if (donation.getAndSet(DonationSubscription.NONE) == DonationSubscription.SUBSCRIBED) {
                // 가상 스레드다. 세션 닫기마다 하나씩 나고 REST 시한 안에 반드시 끝난다.
                donationRelease = Thread.ofVirtual().name("chzzk-donation-release")
                        .start(() -> client.unsubscribeDonationQuietly(key));
            }
            // 결말은 채팅 반납의 것이다 — 후원 반납의 성패로 이 값을 바꾸면 로그의
            // subscription= 이 무엇의 결말인지가 갈린다.
            result = client.unsubscribeChatQuietly(key) ? Release.RETURNED : Release.FAILED;
            joinBeforeDeadline(donationRelease, endAt);
        }
        close();
        return result;
    }

    /**
     * 후원 반납이 끝나기를 <b>예산이 남은 만큼만</b> 기다린다.
     *
     * <p><b>기다리는 이유</b>: 안 기다리고 아래 {@code close()}로 내려가면 소켓이 먼저 닫히고,
     * 그러면 서버가 세션을 정리하는 중에 반납이 도착한다 — 반납을 소켓보다 앞에 두는
     * 이 메서드의 규칙이 후원에만 안 걸리게 된다.
     *
     * <p><b>시한을 처음에 뜨는 이유</b>: 채팅 반납이 끝난 <b>뒤</b>부터 7초를 새로 재면 최악이
     * 7 + 7로 되돌아간다. 둘은 같은 시점에 출발했으므로 남은 예산으로 기다리는 것이 맞다.
     * 만료해도 인터럽트하지 않는다 — {@code SessionRegistry.awaitClosed}와 같은 이유로
     * 나가 있는 반납을 끊으면 세션 키는 이미 소모돼 아무도 다시 못 보낸다.
     */
    private static void joinBeforeDeadline(Thread donationRelease, long endAt) {
        if (donationRelease == null) {
            return;
        }
        try {
            donationRelease.join(Duration.ofNanos(Math.max(endAt - System.nanoTime(), 0)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void onFrame(Consumer<EngineIoFrame> sink) { this.frameSink = sink; }
    public void onClosed(Runnable sink) { this.closedSink = sink; }

    /**
     * @param abort 우리가 멈추는 중인가. 시한과 별개로 <b>대기를 즉시 끝내는</b>
     *              신호다 — 없으면 종료가 시한만큼 매달리거나, 짧게 기다리고
     *              뒷정리 중인 스레드를 인터럽트하는 급사 경로를 만들어야 한다
     */
    public Established open(Duration deadline, BooleanSupplier abort) {
        long endAt = System.nanoTime() + deadline.toNanos();

        abortIfStopping(abort, EstablishStage.AUTH);
        String url = client.createSession();                        // ① AUTH

        AtomicReference<Handshake> handshake = new AtomicReference<>();
        AtomicReference<String> sessionKey = new AtomicReference<>();
        CountDownLatch connected = new CountDownLatch(1);
        CountDownLatch subscribed = new CountDownLatch(1);

        abortIfStopping(abort, EstablishStage.CONNECT);
        // 예산 계산을 try 밖에 둔다. 안에 두면 예산 만료가 아래 분류를 지나
        // CONNECT_FAILED로 둔갑한다.
        Duration connectBudget = remaining(endAt, EstablishStage.CONNECT);
        EngineIoSocket socket;
        try {                                                       // ② CONNECT
            socket = EngineIoSocket.open(
                    SessionUrl.toWebSocketUri(url),
                    frame -> handle(frame, handshake, sessionKey, connected, subscribed),
                    () -> closedSink.run(),
                    connectBudget, abort);
        } catch (Exception e) {
            // <b>중단이 먼저다.</b> 중단으로 빠져나온 접속은 아래 분류가 보면
            // 취소 예외라 CONNECT_FAILED로 떨어지고, 그러면 로그에서 "우리가 멈춘 것"과
            // "붙는 데 실패한 것"이 같은 줄이 된다 — 재연결이 반복 실패할 때 첫 단서다.
            abortIfStopping(abort, EstablishStage.CONNECT);
            // 진단용 구분이다. 재시도 여부는 이걸로 갈리지 않는다 —
            // 재시도 불가 사유는 AUTH의 401/403과 REVOKED뿐이고 둘 다 여기가 아니다.
            // 그래도 가르는 이유는, 재연결이 반복 실패할 때 로그가 한 줄이면
            // 시한 초과인지 DNS인지 TLS인지를 사람이 못 가르기 때문이다.
            //
            // 알고 남긴 구멍: CONNECT_REFUSED만 테스트가 지킨다(죽은 포트로 결정적으로
            // 만들 수 있다). CONNECT_TIMEOUT은 라우팅되지 않는 주소가 있어야 재현되는데
            // 느리고 환경을 타서 안 붙였다 — 그 분기를 지워도 아무 테스트도 안 깨진다.
            // 다시 조사하지 않도록 여기 적어 둔다.
            Throwable root = e.getCause() == null ? e : e.getCause();
            StopReason reason;
            if (root instanceof java.net.http.HttpTimeoutException
                    || root instanceof java.util.concurrent.TimeoutException) {
                reason = StopReason.CONNECT_TIMEOUT;
            } else if (root instanceof java.io.IOException) {
                reason = StopReason.CONNECT_REFUSED;
            } else {
                reason = StopReason.CONNECT_FAILED;
            }
            throw new SessionEstablishException(EstablishStage.CONNECT, reason,
                    "cause=" + root.getClass().getSimpleName());
        }
        current.set(socket);

        await(connected, endAt, abort, EstablishStage.WAITING_CONNECTED);   // ③

        // Handshake.parse는 깨진 본문에 null을 준다(예외를 던지면 수신이 멈춘다).
        // 타이밍을 못 읽으면 하트비트를 돌릴 수 없으므로 여기서 끊는다.
        if (handshake.get() == null) {
            throw new SessionEstablishException(EstablishStage.CONNECT,
                    StopReason.CONNECT_FAILED, "핸드셰이크를 읽지 못했다");
        }

        // 키를 세우기 전에 본다. 세운 뒤에 끊으면 구독한 적도 없는 키로 반납 REST가
        // 한 번 나간다 — 종료 경로에 없어도 되는 왕복이다.
        abortIfStopping(abort, EstablishStage.SUBSCRIBE);
        beforeSessionKey();
        currentSessionKey.set(sessionKey.get());
        client.subscribeChat(sessionKey.get());                     // ④ SUBSCRIBE
        await(subscribed, endAt, abort, EstablishStage.WAITING_SUBSCRIBED); // ⑤

        // ⑥ 후원 구독. <b>예산·중단 신호 안에 둔다</b>(계획 검증 F9) — 밖에 두면 수립 최악이
        // REST 시한(접속 2 + 읽기 5)만큼 더 늘고, 그 사이 releaseAndClose가 지나가면 방금 건
        // 후원 구독을 아무도 반납하지 않는다.
        //
        // <b>거부돼도 수립은 성공이다.</b> subscribeDonation은 던지지 않는다 — 후원 권한만
        // 없는 토큰이 채팅까지 못 걷게 되는 것을 막는 것이 이 카드의 축이다.
        //
        // <b>subscribed(DONATION) 프레임은 기다리지 않는다.</b> ⑤와 다른 선택이라 적어 둔다 —
        // 채팅은 그 프레임이 안 오면 채팅이 한 건도 안 오는 것과 같아 기다릴 값이 있지만,
        // 후원은 안 와도 채팅이 이미 걷고 있어 기다리는 동안 잃는 것(수립 지연)만 있다.
        // REST 200이 곧 구독이고 프레임은 확인이다.
        //
        // 🔴 <b>이 두 줄에는 그물이 놓여 있다</b> —
        // {@code SessionEstablishTest.구독_직후에_중단이_켜지면_후원_구독을_시작하지_않는다}.
        // 둘을 지우면 빨간불이다(POK-234 감사 라운드 3 D1).
        //
        // <b>평시에 지워도 관측이 안 바뀌는 것은 맞다.</b> 바로 위 ⑤의 await가 매 바퀴
        // <b>같은 둘</b>을 먼저 보기 때문이다 — {@code abort} → {@code remaining <= 0} →
        // 그 다음에야 latch. 즉 ⑤가 돌아왔다는 것은 그 순간 abort가 false였고 예산이
        // 남아 있었다는 뜻이라, 여기 도착했을 때 값이 뒤집히려면 그 사이(latch가 내려간
        // 찰나)에 상태가 바뀌어야 한다.
        //
        // <b>그 창을 여는 열쇠는 경합을 이기는 것이 아니라 {@code abort}가 주입된
        // {@link BooleanSupplier}라는 것이다.</b> 라운드 2에 시도한 셋은 전부 중단을
        // <b>미리 켜는</b> 방향이라 언제나 ①이나 ⑤가 먼저 잡았고(종료 신호를 미리 켜면 ①,
        // ④를 붙들어 그 사이에 켜면 ⑤, 예산을 얇게 잡으면 ⑤가 먼저 만료한다) — <b>그 셋이
        // 막힌다는 관측은 지금도 유효하지만 그것이 전부가 아니었다.</b> 넷째 길은
        // 「⑤를 통과한 뒤부터 참이 되는」 공급자를 주는 것이고, 그러면 ⑤의 루프가 한
        // 바퀴만 돌아 창이 <b>정의상</b> 열린다. 검사가 그렇게 잰다.
        //
        // 여기서 새는 대가는 <b>REST 시한 7초(접속 2 + 읽기 5)를 수립 예산 밖에서 쓰는
        // 것</b>이라 싸지 않다.
        abortIfStopping(abort, EstablishStage.SUBSCRIBE);
        if (System.nanoTime() < endAt) {
            donation.set(client.subscribeDonation(sessionKey.get()));   // ⑥ DONATION
            startDonationRetryIfFailed();
        }
        // 예산이 이미 다했으면 <b>건너뛴다(NONE)</b>. 던지지 않는 이유는 위와 같다 —
        // 여기서 던지면 후원 때문에 채팅 수립이 실패하는 길이 다시 열린다.
        // 건너뛴 세션은 다음 수립에서 다시 시도된다.

        return new Established(handshake.get(), socket);
    }

    /**
     * 🔴 <b>일시 실패일 때만 다시 시도한다.</b> {@code REFUSED}(401·403)는 권한이 없는
     * 것이라 시간이 안 풀어 준다 — 두드리면 남의 서버에 부하만 준다. {@code SUBSCRIBED}는
     * 할 일이 없고, {@code NONE}은 시도한 적이 없는 것(예산이 다해 건너뛴 경우)이라
     * 이 세션에서 새로 열 것이 아니다.
     *
     * <p><b>가상 스레드 하나이고 종료를 안 기다린다.</b> 종료 예산 다섯 항의 합이 이미
     * 19초라(운영 유예 20초) 항을 하나 더하면 넘친다 — 넘치면 세션 닫기가 잘려
     * 구독이 반납 안 되고 계정 자리가 남는다. 대신 {@code stopping}을 매 바퀴 보고
     * 인터럽트를 받으므로 종료가 이 스레드를 기다릴 이유가 없다.
     *
     * <p><b>결과를 {@code compareAndSet}으로 쓴다.</b> 그냥 쓰면 반납이 이미 비워 둔
     * {@code NONE}을 이 스레드가 되살려, 닫힌 세션이 창구에 「구독 중」으로 보인다.
     */
    private void startDonationRetryIfFailed() {
        if (donation.get() != DonationSubscription.FAILED) {
            return;
        }
        donationRetry = Thread.ofVirtual().name("chzzk-donation-retry").start(() -> {
            while (!stopping.get()) {
                try {
                    Thread.sleep(donationRetryPeriod);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (stopping.get()) {
                    return;
                }
                // 반납이 지나갔으면 키가 비어 있다. 소모된 키로 구독을 쏘지 않는다.
                String key = currentSessionKey.get();
                if (key == null || key.isBlank()) {
                    return;
                }
                DonationSubscription now = client.subscribeDonation(key);
                if (now != DonationSubscription.FAILED) {
                    // CAS 가 이긴 경우에만 알린다 — 진 것은 반납이 이미 NONE 을 쓴 것이라
                    // 그 값을 창구에 실으면 닫힌 세션이 「구독 중」으로 되살아난다.
                    if (donation.compareAndSet(DonationSubscription.FAILED, now)) {
                        notifyDonationChanged(now);
                    }
                    return;
                }
            }
        });
    }

    /**
     * 알림이 던져도 재시도 스레드를 죽이지 않는다 — 등록부 갱신이 실패해도 구독 자체는
     * 이미 섰고, 여기서 예외가 나가면 그 스레드가 조용히 사라져 다음 바퀴가 없다.
     */
    private void notifyDonationChanged(DonationSubscription state) {
        try {
            donationSink.accept(state);
        } catch (RuntimeException e) {
            log.warn("chat.donation.retry_notify_failed causeType={}", e.getClass().getSimpleName());
        }
    }

    /** 재시도를 멈춘다. <b>기다리지 않는다</b> — 위 javadoc의 예산 이유. */
    private void stopDonationRetry() {
        stopping.set(true);
        Thread retry = donationRetry;
        if (retry != null) {
            retry.interrupt();
        }
    }

    /**
     * <b>세션 키가 서기 직전. 검사가 여기에 다른 스레드를 통째로 끼워 넣는다.</b>
     * 평소에는 비어 있다.
     *
     * <p>이름을 준 이유는 {@code CollectorRunner.heartbeatListener(no)}와 같다 —
     * 이 자리에서 열리는 창에는 <b>붙잡을 I/O가 없어</b> 밖에서 끊기만 하면 두
     * 스레드의 경합이 되고 순서가 실행마다 갈린다.
     *
     * <p>고정하려는 순서는 이것이다: 절단 정리가 <b>키가 서기 전에</b> 통째로
     * 지나가면 그 정리는 반납할 키를 못 보고({@code subscription=skipped}) 가드만
     * 소모한다. 그 뒤에 ④가 만드는 구독은 <b>아무도 반납하지 않는다</b> —
     * 서버에 남아 연결 상한 3개 중 하나를 먹는다.
     * {@code CollectorRunner.releaseLate}가 막는 것이 정확히 그 상태다.
     */
    protected void beforeSessionKey() { }

    /**
     * <b>①②④ 앞에서 한 번씩 본다.</b> 셋은 REST와 WS 접속이라 <b>일단 시작하면
     * 조각으로 끊을 수 없다</b> — ①④는 접속 2초 + 읽기 5초, ②는 접속 시한 5초를
     * 통째로 쓴다. 그래서 여기서 할 수 있는 것은 <b>멈추라는 신호를 받은 뒤에 그런
     * 호출을 새로 시작하지 않는 것</b>뿐이고, 그것으로 충분하다: 이미 나간 호출은
     * 시한이 있어 반드시 돌아오고, 돌아오면 다음 단계 앞에서 여기에 걸린다.
     *
     * <p>이 검사가 없으면 {@code stop()}이 지나간 뒤에 ②가 소켓을 연다. 그 소켓은
     * 정리 가드가 이미 소모돼 아무도 안 닫고, 서버 쪽 자리는 죽은 전송을 알아챌
     * 때까지(실측 10초~4분 42초) 남는다.
     *
     * <p>사유는 {@code await()}의 중단과 같은 값이다 — 재시도 판단이 이걸로 안 갈린다.
     * 사람이 읽는 구분은 {@code detail}이 진다.
     *
     * <p><b>세 자리 중 검사가 지키는 것은 ①뿐이다.</b> 지우고 전체를 돌려 확인했다:
     * ①을 지우면 {@code 중단_신호가_이미_서_있으면_세션_발급조차_안_한다}가 단독으로
     * 깨지고, ②는 5회·④는 1회 전부 초록이다. <b>"원리적으로 필요 없다"가 아니라
     * "이 하네스에서 관측되지 않는다"다</b> — 둘은 다음 이유로 갈린다.
     *
     * <ul>
     *   <li>②를 지워도 {@code EngineIoSocket}의 조각 검사가 곧바로 걸려 접속을 버리는데,
     *       상대가 같은 JVM 루프백이라 <b>그 버리기가 TCP 핸드셰이크보다 빠르다.</b>
     *       실서버는 왕복이 있어 SYN이 이미 나간 뒤이고, 그때 서버가 접속을 성립시키면
     *       그것이 연결 상한 3개 중 하나를 먹는다. 검사를 여기 두면 SYN 자체가 안 나간다
     *   <li>④를 지워도 ⑤의 중단이 곧바로 걸리는데, <b>그 앞에 구독 REST 왕복이 통째로
     *       들어간다.</b> 로컬은 수 ms라 안 보이고 실서버는 접속 2초 + 읽기 5초까지 간다
     * </ul>
     */
    private static void abortIfStopping(BooleanSupplier abort, EstablishStage stage) {
        if (abort.getAsBoolean()) {
            throw new SessionEstablishException(stage, StopReason.ESTABLISH_TIMEOUT,
                    "stage=" + stage + " aborted");
        }
    }

    /**
     * 남은 수립 예산. <b>다 썼으면 그 자리에서 끊는다</b> — 0 이하를 접속 시한으로
     * 넘기면 {@code WebSocket.Builder}가 거부한다.
     */
    private static Duration remaining(long endAt, EstablishStage stage) {
        long left = endAt - System.nanoTime();
        if (left <= 0) {
            throw new SessionEstablishException(stage, StopReason.ESTABLISH_TIMEOUT,
                    "stage=" + stage);
        }
        return Duration.ofNanos(left);
    }

    private void handle(EngineIoFrame frame,
                        AtomicReference<Handshake> handshake,
                        AtomicReference<String> sessionKey,
                        CountDownLatch connected,
                        CountDownLatch subscribed) {
        switch (frame.type()) {
            case OPEN -> handshake.set(Handshake.parse(frame.payload()));
            // CONNECT(40)에는 답하지 않는다. auth는 핸드셰이크에서 이미 소비됐다.
            case CONNECT -> { }
            case EVENT -> {
                SystemEvent event = ChatEventDecoder.decodeSystem(frame.payload());
                if (event != null) {
                    switch (event.type()) {
                        case "connected" -> { sessionKey.set(event.sessionKey()); connected.countDown(); }
                        case "subscribed" -> subscribed.countDown();
                        default -> { }
                    }
                }
                emit(frame);
            }
            default -> emit(frame);
        }
    }

    /**
     * 싱크는 WS 수신 콜백 안에서 불린다. 예외가 밖으로 나가면 onError로 가
     * <b>그 한 건 때문에 방송 전체 수신이 멈춘다.</b> 디코더가 null을 돌려주도록
     * 방어한 것과 같은 이유로 여기도 막는다.
     *
     * <p>예외 자체는 안 남긴다 — 메시지에 본문이 딸려 올 수 있다. 종류만 센다.
     */
    private void emit(EngineIoFrame frame) {
        try {
            frameSink.accept(frame);
        } catch (RuntimeException e) {
            sinkFailures.incrementAndGet();
        }
    }

    /**
     * 래치를 기다리되 <b>조각으로 나눠 기다리며 매번 중단 신호를 본다.</b>
     *
     * <p>한 번에 시한 전체를 기다리면 멈추려는 쪽이 그만큼 매달린다 —
     * 운영 시한이 15초라 컨테이너 종료 유예를 넘기고, SIGKILL이 오면 구독 반납이
     * 통째로 안 나간다. 조각을 더 잘게 쪼개도 얻는 것이 없고(종료가 100ms 빨라질
     * 뿐이다) 깨어나는 횟수만 는다.
     *
     * <p><b>중단을 래치보다 먼저 본다.</b> 반대로 하면 "멈추는 중인데 마침 프레임이
     * 도착해서" 다음 단계로 넘어가는 길이 생기고, 그 세션은 아무도 안 닫는다.
     */
    private void await(CountDownLatch latch, long endAt, BooleanSupplier abort, EstablishStage stage) {
        try {
            while (true) {
                if (abort.getAsBoolean()) {
                    // 사유는 시한 초과와 같은 값이다. 재시도 판단이 이걸로 안 갈리고,
                    // 새 값을 만들면 재시도 분류표에 실제로는 안 오는 값이 한 줄 는다.
                    // 사람이 읽는 구분은 detail이 진다.
                    throw new SessionEstablishException(stage, StopReason.ESTABLISH_TIMEOUT,
                            "stage=" + stage + " aborted");
                }
                long remaining = endAt - System.nanoTime();
                if (remaining <= 0) {
                    throw new SessionEstablishException(stage, StopReason.ESTABLISH_TIMEOUT,
                            "stage=" + stage);
                }
                if (latch.await(Math.min(remaining, ABORT_CHECK_SLICE.toNanos()),
                        TimeUnit.NANOSECONDS)) {
                    return;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SessionEstablishException(stage, StopReason.ESTABLISH_TIMEOUT, "interrupted");
        }
    }

    @Override
    public void close() {
        // releaseAndClose 를 안 거치고 바로 닫는 경로도 있다(수립 실패·전송 절단).
        // 그 길에서도 재시도가 남으면 죽은 세션이 계속 구독을 쏜다.
        stopDonationRetry();
        EngineIoSocket socket = current.getAndSet(null);
        if (socket != null) socket.close();
    }
}
