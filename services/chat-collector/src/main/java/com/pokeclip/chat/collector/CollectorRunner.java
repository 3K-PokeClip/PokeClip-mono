package com.pokeclip.chat.collector;

import com.pokeclip.chat.collector.chzzk.ChatEventDecoder;
import com.pokeclip.chat.collector.chzzk.ChatMessage;
import com.pokeclip.chat.collector.chzzk.ChatSession;
import com.pokeclip.chat.collector.chzzk.ChzzkSessionClient;
import com.pokeclip.chat.collector.chzzk.SessionEstablishException;
import com.pokeclip.chat.collector.chzzk.SystemEvent;
import com.pokeclip.chat.collector.engineio.EngineIoFrame;
import com.pokeclip.chat.collector.engineio.PingFailure;
import com.pokeclip.chat.collector.observe.CollectionMetrics;
import com.pokeclip.chat.collector.observe.Heartbeat;
import com.pokeclip.chat.collector.observe.HeartbeatListener;
import com.pokeclip.chat.collector.observe.SummaryLogger;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.pokeclip.chat.collector.reconnect.ReconnectPolicy;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 부팅 시 수집을 시작하고, 끊기면 세션 발급부터 다시 타고, 종료 시 역순으로 닫는다.
 *
 * <p><b>재시도가 안 통하는 사유는 401·403과 {@code REVOKED}뿐이다.</b> 그 밖은
 * 포기하지 않는다 — 끊겨 있는 동안의 채팅은 되돌릴 수 없고, 백필이 되는지도 모른다.
 */
@Component
public class CollectorRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CollectorRunner.class);

    /** 실측 30초. 요약 주기는 하트비트와 무관하다 — 얹으면 8/1이 재현된다. */
    private static final Duration SUMMARY_PERIOD = Duration.ofSeconds(30);

    private final ChzzkProperties properties;
    private final CollectionStatus status;

    /**
     * <b>{@code RestClient.create()}로 만들지 않는다.</b> 그러면 자동 설정을 우회해
     * {@code spring.http.clients.*}의 타임아웃이 어디에도 안 걸린다. 그때
     * {@code createSession()}은 {@code establishTimeout}으로 못 끊는 동기 호출이라
     * <b>부팅이 안 끝나고</b>, 같은 클라이언트가 종료 시 구독 반납에도 쓰여
     * <b>종료도 안 끝난다.</b> 치지직이 연결만 받고 답을 안 주면 그대로 멈춘다.
     */
    private final RestClient restClient;

    private final CollectionMetrics metrics = new CollectionMetrics();

    /**
     * 한 세션이 소유한 것 전부. <b>필드로 흩어 놓지 않는다.</b>
     *
     * <p>가드만 세션 단위로 갈고 나머지를 필드로 두면, 뒷정리가 반납 왕복(실서버
     * 약 1초)에 갇힌 사이에 다음 세션이 시작될 때 <b>깨어난 앞 뒷정리가 새 세션의
     * 필드를 지운다.</b> 그러면 새 세션이 끊겨도 구독 반납도 소켓 닫기도 안 나가고,
     * 상한이 3개라 반납이 새면 금방 못 붙게 된다.
     *
     * <p><b>필드마다 세대를 검사하는 것으로는 안 된다.</b> 검사와 검사 사이에 세대가
     * 바뀌면 일부만 지워진 상태가 남는다 — 창이 좁아지지 사라지지 않는다.
     * 덩어리 하나를 참조 하나로 들면 <b>남의 세션을 만질 길 자체가 없다.</b>
     *
     * <p>{@code heartbeat}·{@code summaryLogger}·{@code collectingSince}가 홀더인
     * 이유는 소켓이 있어야 만들 수 있어 {@code open()} 뒤에야 채워지기 때문이다.
     * <b>그때 레코드를 새로 만들어 갈아 끼우면 안 된다</b> — 덩어리의 정체가 바뀌어
     * 뒷정리의 {@code compareAndSet(내것, null)}이 실패하고, 자리가 소모된 가드를
     * 든 채 영영 안 비워져 다음 세션이 영영 못 선다. 홀더는 이 세션의 것이므로
     * 누가 언제 읽어도 남의 세션에 닿지 않는다.
     *
     * @param no            이 프로세스에서 몇 번째 세션인가. 재연결이 붙으면 판정·반납
     *                      줄이 여러 번 나가는데, 번호가 없으면 운영자가 N번째와
     *                      N+1번째를 못 가른다. <b>자리를 잡은 뒤에 채운다</b> —
     *                      거절된 {@code start()}가 번호를 먹으면 로그에 구멍이 생기고,
     *                      사람은 그 구멍을 "판정 줄을 잃어버렸다"로 읽는다
     * @param cleanedUp     뒷정리 가드. 종료 경로가 둘(전송 절단·프로세스 종료)이라
     *                      같은 세션에서 두 번 도는 길이 있다
     */
    private record SessionScope(
            ChatSession chat,
            AtomicLong no,
            AtomicBoolean cleanedUp,
            AtomicReference<Heartbeat> heartbeat,
            AtomicReference<SummaryLogger> summaryLogger,
            AtomicReference<Instant> collectingSince) {

        static SessionScope opening(ChatSession chat) {
            return new SessionScope(chat, new AtomicLong(), new AtomicBoolean(),
                    new AtomicReference<>(), new AtomicReference<>(), new AtomicReference<>());
        }
    }

    /** 지금 자리를 잡고 있는 세션. 비어 있을 때만 새 세션이 들어올 수 있다. */
    private final AtomicReference<SessionScope> activeSession = new AtomicReference<>();

    /** 아직 아무 세션도 안 선 상태의 번호. 부팅 첫 수립이 실패한 경우가 여기다. */
    private static final long NO_SESSION = 0L;

    /**
     * 재연결 전용 스레드. <b>WS 수신 콜백이나 ping 스케줄러에서 재수립하지 않는다</b> —
     * 앞의 것은 소켓의 스레드라 자기가 닫을 소켓 위에서 도는 꼴이고, 뒤의 것은
     * 그 스레드가 재수립에 붙들려 <b>ping이 안 나가는</b> 2026-08-01 사고를 재현한다.
     *
     * <p>단일 스레드다. 둘 이상이면 서로 다른 시도가 동시에 자리를 노리고, 진 쪽이
     * 연 소켓은 아무도 안 닫는다.
     */
    private final ExecutorService reconnector = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "chzzk-reconnect");
        t.setDaemon(true);
        return t;
    });

    /**
     * 재연결 루프가 <b>하나만</b> 돌게 막는다. 루프가 끝날 때 푼다.
     *
     * <p><b>감지원이 셋이라 한 절단에 신호가 둘 이상 발화한다</b> — pong 임계 초과와
     * 전송 절단이 같은 죽음을 서로 다른 경로로 본다. 그때 세션의 {@code cleanedUp}
     * 가드는 이미 소모돼 no-op이라 아무것도 안 막는다. 가드가 없으면 <b>루프가 둘
     * 큐에 쌓이고, 첫 루프가 재접속에 성공한 뒤 두 번째가 깨어나 health를 DOWN으로
     * 되돌리고 살아 있는 세션을 고아로 만든다.</b>
     */
    private final AtomicBoolean reconnectInFlight = new AtomicBoolean();

    /**
     * 루프가 도는 동안 들어온 절단 신호. 루프가 끝날 때 집어 간다.
     *
     * <p><b>사유만 두면 안 된다.</b> 루프가 재접속에 성공하고 {@code finally}에
     * 닿기까지의 창에 <b>이미 치워진 앞 세션</b>의 늦은 신호가 들어올 수 있고, 그것을
     * 그대로 재생하면 살아 있는 새 세션을 헐어낸다. 어느 세션의 신호인지를 같이 든다.
     *
     * @param sessionNo 신호를 낸 세션의 번호. 재생할 때 그 세션이 아직 자리에 있는지 본다
     */
    private record ReconnectSignal(long sessionNo, StopReason reason) { }

    private final AtomicReference<ReconnectSignal> pendingSignal = new AtomicReference<>();

    /**
     * 언제부터 못 받고 있나. 다시 붙으면 지운다.
     *
     * <p>재연결이 여러 번 실패해도 <b>첫 절단 시각을 유지한다</b> — 시도마다 갱신하면
     * "10분째 못 붙는다"가 매번 "방금 끊겼다"로 보인다.
     *
     * <p><b>평범한 필드로 두면 그 "첫 시각이 이긴다"가 감지원이 겹칠 때 깨진다.</b>
     * 만지는 스레드가 셋이고(WS 콜백 · ping 스케줄러 · 재연결 스레드) 한 번의 죽음을
     * 둘 이상이 서로 다른 경로로 본다 — 둘이 같이 빈 것을 보면 나중 시각이 덮어써
     * 끊긴 시간이 그만큼 짧게 보고된다. 세우기는 {@code compareAndSet(null, …)},
     * 걷기는 {@code getAndSet(null)}이라 둘 다 한 번씩만 이긴다.
     *
     * <p><b>🔴 이 원자성을 지키는 검사가 없다.</b> 평범한 필드로 되돌려도 전체가 초록이다
     * (실측). 겹치는 창이 널 검사와 대입 사이 몇 인스트럭션이라 붙잡을 I/O가 없어서
     * <b>결정적으로 재현할 장치를 못 만들었다</b> — "원리적으로 불가능"이 아니라
     * "지금 코드에 관측 가능한 차이를 만들 손잡이가 없다"이다.
     * <b>되돌려도 빌드가 안 막으니, 고치려거든 여기를 먼저 읽어라.</b>
     */
    private final AtomicReference<Instant> disconnectedAt = new AtomicReference<>();

    private final ReconnectPolicy policy;

    /**
     * 최종 판정 가드. <b>세션이 아니라 프로세스에 하나다.</b>
     *
     * <p>판정은 "수집이 영영 끝났다"는 뜻이라 나가는 자리가 둘뿐이다 —
     * 영구 정지와 프로세스 종료. 세션마다 두면 재연결할 때마다 "최종" 판정이 쌓이고
     * 그중 어느 것도 최종이 아니다.
     */
    private final AtomicBoolean verdictLogged = new AtomicBoolean();

    /**
     * <b>우리가 멈추는 중이라는 신호. 수립 대기가 이걸 본다.</b>
     *
     * <p>없으면 {@code stop()}이 둘 중 하나를 골라야 한다 — 수립 시한(운영 15초)만큼
     * 기다려 종료 유예를 넘기거나, 짧게 기다리고 {@code shutdownNow()}로 뒷정리 중인
     * 스레드를 인터럽트하거나. 뒤쪽이면 구독 반납 REST가 즉시 실패하고 우아한 닫기도
     * 못 돌아 <b>급사 경로</b>가 된다. 그때 서버가 세션을 놓아주는 데 10초~4분 42초가
     * 걸리고(실측) 상한이 3개라, 이 카드가 없애려던 것을 우리가 만드는 셈이 된다.
     */
    private final CountDownLatch stopSignal = new CountDownLatch(1);

    /**
     * 자리를 잡은 세션에만 번호를 준다. <b>프로세스 안에서 유일하다</b> —
     * 그래서 static이다.
     *
     * <p>러너마다 1부터 세면 번호가 "이 러너의 몇 번째"가 되고, 러너가 둘 이상인
     * 순간(스트리머별 수집 · 검사) <b>서로 다른 세션이 같은 번호로 로그에 나간다.</b>
     * 그러면 {@code session=N}으로 줄을 고르는 사람도 도구도 남의 세션을 자기 것으로
     * 집는다 — 실제로 검사에서 앞 테스트의 낙오 반납 줄이 뒤 테스트의 줄 수 단언에
     * 섞여 들어갔다(실측: 기대 1, 실제 2).
     */
    private static final AtomicLong SESSION_SEQ = new AtomicLong();

    /**
     * 이 러너가 마지막으로 자리를 준 세션의 번호. 자리가 비워진 뒤에도 남는다.
     *
     * <p><b>검사가 자기 러너의 줄만 고르는 열쇠다.</b> {@code LogCaptor}는 JVM 전역
     * 루트 로거에 붙어 있어 남의 러너가 늦게 찍은 줄까지 담는다. 번호를 모르면
     * 줄 수 단언이 그 낙오까지 세고, 판정이 0줄이 되는 진짜 결함을 낙오 한 건이
     * 메워 준다.
     */
    private final AtomicLong lastSessionNo = new AtomicLong();

    long lastSessionNo() {
        return lastSessionNo.get();
    }

    public CollectorRunner(ChzzkProperties properties, CollectionStatus status,
                           RestClient.Builder restClientBuilder) {
        this.properties = properties;
        this.status = status;
        // 빌더는 프로토타입 빈이다. 한 번만 build()해서 들고 있는다.
        this.restClient = restClientBuilder.build();
        this.policy = new ReconnectPolicy(
                properties.reconnectFirstDelay(), properties.reconnectMaxDelay());
    }

    /**
     * 부팅 경로. <b>첫 시도부터 실패한 것도 재연결 대상이다</b> — 치지직이 잠깐
     * 아플 때 부팅 타이밍이 겹쳤다는 이유로 그 프로세스가 영영 수집을 안 하면,
     * 사람이 알아채고 재배포할 때까지의 채팅이 통째로 사라진다.
     */
    @Override
    public void run(ApplicationArguments args) {
        try {
            start();
        } catch (SessionEstablishException e) {
            // 사유가 영구면 루프가 첫 바퀴에서 즉시 멈춘다.
            // stopped 줄은 start()가 detail까지 실어 이미 남겼다.
            requestReconnect(NO_SESSION, e.reason());
        }
    }

    /**
     * 지금까지의 관측값. 유출 검사가 "바늘이 실제로 코드 안을 지나갔나"를
     * 확인하는 데 쓴다 — 그 대조가 없으면 아무것도 안 흘렀을 때
     * "안 샜다"가 자동으로 참이 된다.
     */
    public CollectionMetrics metrics() {
        return metrics;
    }

    /**
     * 세션 하나를 연다.
     *
     * @return <b>이 호출이 수집을 시작시켰는가.</b> 자리를 못 잡았거나 그 사이에
     *         끝났으면 false다. <b>값으로 주는 이유</b> — 재연결 루프가 성공 여부를
     *         {@code status.state()}로 읽으면, 거절된 호출에서 그 상태는 <b>앞 세션이
     *         남긴 값</b>이라 「붙었다」로 오독된다. 그러면 루프가 빠져나가고
     *         아무도 재시도하지 않는다
     * @throws SessionEstablishException 수립에 실패했다. <b>던지기 전에 자기가 연 것은
     *         치운다.</b> 사유를 보고 재시도할지 판단하는 것은 부르는 쪽 일이다
     */
    public boolean start() {
        if (!properties.enabled()) {
            // 붙지 않는다. 로그 한 줄은 남긴다 — "왜 채팅이 안 들어오지"의
            // 첫 번째 답이 대개 이것이다.
            log.info("chat.collector.disabled");
            status.disabled();
            return false;
        }

        ChatSession opening = new ChatSession(new ChzzkSessionClient(
                restClient, properties.baseUrl(), properties.accessToken()));
        SessionScope scope = SessionScope.opening(opening);
        if (!activeSession.compareAndSet(null, scope)) {
            // 앞 세션이 아직 자리를 들고 있다. 덮어쓰면 그 세션의 소켓도 스케줄러도
            // 아무도 안 닫아 ping이 죽은 소켓에 계속 나가고, 구독은 서버에 남아
            // 상한 3개를 먹는다. 조용히 돌아가지 않는다 — 재연결 루프가 왜
            // 안 붙었는지 알 길이 없으면 그것이 곧 조용한 실패다.
            log.warn("chat.session.start_skipped reason=ALREADY_ACTIVE");
            return false;
        }
        if (stopSignal.getCount() == 0) {
            // 잡은 자리를 되돌린다. stop()은 자리가 빈 것을 보고 이미 지나갔을 수
            // 있고, 그러면 여기서 연 세션은 아무도 안 닫는다 — 소켓도 구독도
            // 프로세스가 죽을 때까지 남는다.
            activeSession.compareAndSet(scope, null);
            log.warn("chat.session.start_skipped reason=STOPPING");
            return false;
        }
        // 자리를 잡은 뒤라 번호를 두 세션이 나눠 갖는 길이 없고, 콜백을 아직
        // 걸지 않아 이 값을 우리보다 먼저 읽는 스레드도 없다.
        long no = SESSION_SEQ.incrementAndGet();
        scope.no().set(no);
        lastSessionNo.set(no);
        // <b>싱크를 걸기 직전이다.</b> 수신 시계를 여기서 다시 잡지 않고 아래
        // 절단 구간을 닫는 자리(수립 마무리 뒤)에서 잡으면, 그 사이에 온 첫 채팅이
        // 앞 세션의 마지막 수신과 짝지어져 끊겨 있던 시간이 수신 공백에도 실린다.
        metrics.beginSession();
        opening.onFrame(frame -> handleFrame(scope, frame));
        // 필드가 아니라 이 덩어리를 캡처해서 넘긴다. 콜백이 나중에 필드를 다시
        // 읽으면, 그때 자리에 있는 것은 이미 다음 세션일 수 있다.
        opening.onClosed(() -> handleClosed(scope));
        status.establishing();

        try {
            ChatSession.Established established =
                    opening.open(properties.establishTimeout(), () -> stopSignal.getCount() == 0);

            // 수립을 마치는 사이에 WS 스레드가 절단을 처리했으면 정리가 이미
            // 끝났다. 그 위에 스케줄러를 올리면 닫힌 소켓에 대고 ping을 쏘고,
            // 상태까지 COLLECTING으로 되돌리면 health는 UP인 채로 수집만 죽는다.
            // 정리 가드는 이미 소모돼 stop()도 아무것도 못 한다.
            if (scope.cleanedUp().get()) {
                // <b>우리가 연 소켓은 우리가 닫는다.</b> 정리가 ②보다 먼저 지나갔으면
                // 그 정리는 아직 없던 소켓을 못 닫았고, 가드가 소모돼 아무도 다시
                // 안 온다. close()는 멱등이라 이미 닫힌 경우엔 아무 일도 안 한다.
                opening.close();
                return false;
            }

            Heartbeat beat = Heartbeat.start(established.socket(), established.handshake(),
                    heartbeatListener(no));
            // 값이 아니라 읽는 길을 넘긴다 — 삼킨 예외 수는 계속 늘어난다.
            // 세션은 이 덩어리의 것이라 바뀌지 않으므로 그쪽을 직접 읽는다.
            SummaryLogger logger = SummaryLogger.start(metrics, beat, SUMMARY_PERIOD,
                    opening::sinkFailureCount);
            // 상태 전이보다 먼저 보인다. 이 뒤에 절단이 오면 cleanUpOnce가 여기서
            // 읽어 둘 다 닫는다 — 반대 순서면 정리가 실행기를 못 보고 지나친다.
            scope.heartbeat().set(beat);
            scope.summaryLogger().set(logger);
            scope.collectingSince().set(Instant.now());

            if (!status.collectingIfPending()) {
                // 우리가 올린 것은 우리가 내린다 — cleanUpOnce가 이미 지나갔다면
                // 아무도 안 내려 준다.
                beat.close();
                logger.close();
                scope.heartbeat().set(null);
                scope.summaryLogger().set(null);
                // <b>세션도 치운다.</b> 여기 오는 길이 둘이고 둘째가 새 길이다 —
                // ① 검사와 전이 사이에 절단이 들어왔다(뒷정리가 이미 지났고 이 호출은
                //   가드에 막혀 no-op이다)
                // ② 이미 영구 정지(STOPPED)인 러너에 start()가 들어왔다. establishing()이
                //   더는 그 위를 안 덮으므로 수립은 끝까지 가는데 아무도 COLLECTING으로
                //   못 올린다. 안 치우면 <b>소켓도 자리도 통째로 샌다</b> — 구독은 서버에
                //   남아 상한 3개를 먹고, 자리가 안 비어 다음 세션이 영영 못 선다.
                cleanUpOnce(scope, status.reason());
                return false;
            }
            // 다시 붙었다. 끊겨 있던 구간을 여기서 닫는다 — 지우기만 하면 그 시간이
            // 어느 지표에도 안 남고, 수신 공백에 섞인 채로 "한산했을 뿐"과 같아 보인다.
            // 절단 시각도 같이 비운다. 안 비우면 다음 절단이 남의 시각을 물려받는다.
            Instant since = disconnectedAt.getAndSet(null);
            if (since != null) {
                metrics.recordOutage(since, Instant.now());
            }
            log.info("chat.session.collecting pingIntervalMs={} sendPeriodMs={}",
                    established.handshake().pingInterval().toMillis(),
                    established.handshake().sendPeriod().toMillis());
            return true;
        } catch (SessionEstablishException e) {
            // URL·응답 본문·토큰을 안 찍는다. detail에 들어가는 것은 상태 코드·예외
            // 단순 이름·단계 이름뿐이라(던지는 자리 7곳 전수 확인) 토큰이 실릴 길이 없다.
            //
            // <b>detail이 없으면 열거값 아래 한 단계가 통째로 사라진다.</b>
            // SESSION_AUTH_FAILED는 500인지 타임아웃인지를, CONNECT_REFUSED는
            // DNS인지 TLS인지 연결 거부인지를 말하지 못한다. 재연결이 반복 실패할 때
            // 사람은 같은 줄만 보고 엉뚱한 곳을 판다.
            log.warn("chat.session.stopped stage={} reason={} detail={}",
                    e.stage(), e.reason(), e.getMessage());
            // <b>여기서 status.stopped()를 찍지 않는다.</b> STOPPED는 안 덮이는
            // 상태라, 재시도해도 되는 실패(5xx·시한 초과)에 찍어 두면 재연결이
            // 붙어도 영영 못 올라온다. 재시도 여부는 사유를 받은 쪽이 정한다.
            //
            // 사유는 status에서 읽는다. 절단이 먼저였다면 그것이 원인이고
            // 여기서 잡은 시한 만료는 그 결과다 — 결과가 원인을 덮으면 추적이 끊긴다.
            cleanUpOnce(scope, status.reason() == null ? e.reason() : status.reason());
            // ③④⑤에서 실패하면 소켓이 이미 열려 있다(구독 5xx · 핸드셰이크 파싱 실패 ·
            // connected 미도착 = 연결 상한 초과의 증상). 위에서 안 치우면 시도마다
            // 소켓·HttpClient·치지직 세션이 새고, 상한이 3개라 세 번째에 스스로 막힌다.
            throw e;
        }
    }

    /**
     * 하트비트가 알리는 두 사건을 운영 로그로 옮긴다. <b>태스크 5·6이 운영에
     * 붙는 유일한 지점이다.</b>
     *
     * <p><b>익명 클래스로 {@code start()} 안에 묻어 두지 않는다.</b> 거기 있는 동안은
     * 두 메서드를 빈 몸통으로 바꿔도 전 테스트가 초록이었다(CP2 실측) — 좀비 판정도
     * 송신 실패 분류도 통째로 사라질 수 있고 아무도 몰랐다.
     *
     * <p>좀비 쪽은 가짜 서버가 pong을 끊으면 러너를 통째로 지나 재현되지만,
     * <b>송신 실패 쪽은 루프백으로 못 만든다</b> — 전송이 끊기면 우리 뒷정리가
     * 하트비트를 먼저 닫으므로 "쓰기는 실패하는데 읽기 통지는 아직 안 왔다"는
     * 실제 망 사고의 순서가 같은 기계 안에서는 만들어지지 않는다. 이름을 준 것은
     * 그 한 갈래를 검사가 지나갈 수 있게 하기 위해서다.
     *
     * @param sessionNo 이 하트비트가 붙은 세션. <b>여기서 신호에 실어 보내지 않으면
     *                  늦게 도착한 좀비 판정이 이미 다시 붙은 세션을 헐어낸다</b> —
     *                  하트비트를 닫아도 이미 시작된 주기 하나는 끝까지 돈다
     */
    HeartbeatListener heartbeatListener(long sessionNo) {
        return new HeartbeatListener() {
            @Override
            public void onSendFailed(PingFailure.Cause cause) {
                log.warn("chat.session.ping_send_failed cause={}", cause);
                // MISUSE는 우리 버그다. 재연결로 덮으면 버그는 영영 안 보이고
                // 자리만 태우므로 사유를 갈라서 넘긴다.
                requestReconnect(sessionNo, cause == PingFailure.Cause.MISUSE
                        ? StopReason.SEND_MISUSE : StopReason.PING_SEND_FAILED);
            }

            @Override
            public void onPongTimeout(Duration gap) {
                log.warn("chat.session.pong_timeout gapMs={}", gap.toMillis());
                requestReconnect(sessionNo, StopReason.PONG_TIMEOUT);
            }
        };
    }

    /**
     * 끊겼다. <b>여기서는 아무것도 하지 않는다 — 요청만 큐에 넣는다.</b>
     *
     * <p>부르는 쪽이 WS 수신 스레드거나 ping 스케줄러다. <b>ping 스케줄러 위에서
     * 뒷정리를 돌리면 {@code beat.close()}의 {@code shutdownNow()}가 자기 스레드를
     * 인터럽트한다</b>(실측). 그러면 뒤따르는 구독 반납 REST가 즉시
     * {@code InterruptedException}으로 실패해 {@code Release.FAILED}가 되고, 우아한
     * 소켓 닫기도 같은 이유로 못 돌아 <b>abort만 남는다 = 급사 경로.</b> 서버가
     * 세션을 놓아주는 데 10초~4분 42초가 걸리고 상한이 3개라, 우리가 없애려던 것을
     * 우리가 만드는 셈이 된다.
     *
     * @param sessionNo 신호를 낸 세션. <b>지금 자리에 있는 세션의 것이 아니면 버린다</b> —
     *                  이미 치워진 세션의 늦은 신호가 살아 있는 새 세션을 헐어낸다
     */
    private void requestReconnect(long sessionNo, StopReason reason) {
        if (stopSignal.getCount() == 0) {
            return;                       // 우리가 멈추는 중이다. 뒷정리는 stop()이 한다
        }
        if (!isCurrent(sessionNo)) {
            // 낡은 신호다. 그 세션은 이미 치워졌고, 받아들이면 지금 붙어 있는
            // 세션을 헐어 구독을 반납하고 health를 DOWN으로 되돌린다.
            log.debug("chat.session.signal_stale session={} reason={}", sessionNo, reason);
            return;
        }
        // 비어 있을 때만 세운다. 이미 서 있으면 그것이 첫 절단이다.
        disconnectedAt.compareAndSet(null, Instant.now());
        // 상태를 먼저 내린다. 실행기에 넘기기만 하면 그 사이 COLLECTING(health UP)인데
        // 소켓은 죽은 창이 생긴다 — "UP인데 수집 없음"이 이 서비스의 유일한 치명
        // 실패라 창을 안 만든다. STOPPED는 안 덮인다.
        status.reconnecting(reason, disconnectedAt.get(), status.attempt());
        if (!reconnectInFlight.compareAndSet(false, true)) {
            // 이미 한 루프가 돈다. <b>신호를 버리지 않고 남긴다</b> — 루프가 재접속에
            // 성공하고 finally에 닿기까지의 창에 들어온 절단이 통째로 사라지면,
            // 상태는 COLLECTING(health UP)인데 소켓은 죽어 있게 된다.
            //
            // 같은 세션이면 첫 사유가 이긴다(뒤엣것은 대개 그 결과다).
            // 다른 세션이면 나중 세션이 이긴다 — 낡은 신호를 붙들고 새 신호를
            // 버리면 방금 죽은 세션을 아무도 못 본다.
            pendingSignal.accumulateAndGet(new ReconnectSignal(sessionNo, reason),
                    (current, incoming) -> current == null
                            || current.sessionNo() < incoming.sessionNo() ? incoming : current);
            return;
        }
        try {
            reconnector.execute(() -> reconnectLoop(sessionNo, reason));
        } catch (RejectedExecutionException e) {
            // 위 검사와 여기 사이에 stop()이 들어왔다. handleClosed는 JDK WS 콜백
            // 안이라 여기서 새면 예외가 콜백 밖으로 나간다.
            // 그 세션의 뒷정리는 stop()이 이미 했거나 곧 한다.
            reconnectInFlight.set(false);
        }
    }

    /** 그 번호가 지금 자리에 있는 세션의 것인가. 자리가 비어 있으면 "아직 세션 없음"이다. */
    private boolean isCurrent(long sessionNo) {
        SessionScope seated = activeSession.get();
        return seated == null ? sessionNo == NO_SESSION : seated.no().get() == sessionNo;
    }

    /**
     * 뒷정리하고, 재시도해도 되는 사유면 간격을 늘려 가며 다시 붙는다.
     *
     * <p><b>이 스레드에서 뒷정리를 한다.</b> 신호를 낸 콜백 스레드에서 하면 위
     * {@link #requestReconnect}의 인터럽트가 난다.
     */
    private void reconnectLoop(long sessionNo, StopReason firstReason) {
        SessionScope from = activeSession.get();
        if (from != null && from.no().get() == sessionNo) {
            cleanUpOnce(from, firstReason);
        }
        StopReason reason = firstReason;
        int attempt = 0;
        try {
            while (stopSignal.getCount() > 0) {
                if (status.state() == CollectionStatus.State.STOPPED) {
                    // 이미 영구 정지다. 판정은 그때 나갔다.
                    return;
                }
                if (!ReconnectPolicy.retriable(reason)) {
                    // 수집이 다시 시작될 가능성이 없어졌다.
                    // 판정이 나가야 할 두 시점 중 하나다(다른 하나는 stop()).
                    log.warn("chat.session.stopped reason={} retriable=false attempt={}",
                            reason, attempt);
                    status.stopped(reason);
                    logVerdictOnce(reason);
                    return;
                }
                attempt++;
                status.reconnecting(reason, disconnectedAt.get(), attempt);
                Duration delay = policy.delayFor(attempt);
                try {
                    if (stopSignal.await(delay.toMillis(), TimeUnit.MILLISECONDS)) {
                        return;           // 종료가 대기를 깨웠다
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                log.info("chat.session.reconnecting attempt={} afterMs={} lastReason={}",
                        attempt, delay.toMillis(), reason);
                try {
                    if (start()) {
                        return;           // 붙었다. 다음 절단은 새 루프가 받는다
                    }
                    // 자리를 못 잡았거나 수립 직후에 끊겼다. 사유는 상태가 든다.
                    reason = status.reason() == null ? StopReason.TRANSPORT_CLOSED : status.reason();
                } catch (SessionEstablishException e) {
                    reason = e.reason();  // start()가 던지기 전에 자기 세션을 이미 치웠다
                }
            }
        } finally {
            // 성공이든 종료든 여기서 푼다. 안 풀면 다음 절단에 루프가 영영 안 돈다.
            reconnectInFlight.set(false);
            // 내가 도는 동안 들어온 신호를 집어 간다. 안 그러면 그 절단이 버려진다.
            // 여기서 놓치는 것은 set(false) 뒤에 온 신호뿐이고, 그건 자기가 CAS를
            // 이겨 스스로 루프를 띄운다.
            //
            // <b>그 세션이 아직 자리에 있을 때만 재생한다.</b> 이미 치워진 세션의
            // 신호를 재생하면 방금 붙은 세션을 헐어낸다. STOPPED에서도 재생하지
            // 않는다 — retriable한 사유가 남아 있으면 만료 토큰으로 영원히 두드린다.
            //
            // <b>이 세 조건은 지금 아무 검사도 안 지킨다.</b> 셋 다 requestReconnect가
            // 입구에서 다시 보므로(stopSignal · isCurrent · 루프의 STOPPED 분기)
            // 여기서 떼어도 관측 가능한 차이가 없다 — 실제로 떼고 149건 전부 초록임을
            // 확인했다. 여기 남겨 두는 것은 이 블록만 읽고도 무엇이 재생되는지
            // 알 수 있게 하려는 것이고, <b>다음 사람이 이것을 검사받는 방어로
            // 읽지 않도록</b> 적어 둔다.
            //
            // 재생 자체를 지워도(pendingSignal을 통째로 버려도) 초록이다.
            //
            // <b>창이 좁아서가 아니다.</b> 여기 원래 "창이 마이크로초라 결정적으로 열
            // 장치가 없다"고 적혀 있었는데 틀렸다 — 창은 <b>루프의 start() 호출 전체</b>다.
            // onClosed가 자리를 잡은 직후부터 살아 있어 수립 중 절단이 곧장 여기 앉는다.
            // 가짜 서버가 subscribed를 쏜 뒤 끊고 구독 응답을 붙들면 그 창이 결정적으로
            // 200ms 열린다(CP4가 그렇게 만들어 확인했다).
            //
            // <b>초록인 진짜 이유는 하트비트가 같은 죽음을 다시 알려 주기 때문이다.</b>
            // 첫 ping이 initialDelay=0이라 죽은 소켓에 즉시 나가 실패하고, 그 실패 통지가
            // 아래 set(false)보다 늦게 도착하면 스스로 CAS를 이겨 새 루프를 띄운다.
            //
            // <b>그런데 그 재알림에는 래치가 있다.</b> Heartbeat.sendFailureReported가
            // 구간당 1회라, 재알림이 set(false)보다 <b>먼저</b> 도착하는 순서에서는
            // 그것마저 pending에 앉고 그 뒤로는 어떤 신호도 안 나온다 —
            // COLLECTING(health UP)인 채로 죽은 소켓이 영구히 남는다.
            // <b>그 순서에서는 이 재생이 유일한 방어다. 지우지 마라.</b>
            //
            // 검사를 못 붙인 이유는 장치가 없어서가 아니라 <b>고정할 지점이 없어서</b>다.
            // 그 순서를 만들려면 첫 ping 실패 보고를 이 finally보다 앞에 세워야 하는데,
            // Heartbeat.start()와 여기 사이에 붙잡을 I/O가 하나도 없다.
            ReconnectSignal missed = pendingSignal.getAndSet(null);
            if (missed != null && stopSignal.getCount() > 0
                    && status.state() != CollectionStatus.State.STOPPED
                    && isCurrent(missed.sessionNo())) {
                requestReconnect(missed.sessionNo(), missed.reason());
            }
        }
    }

    /**
     * 서버가 조용히 끊었을 때. 상태를 COLLECTING으로 두면 수집이 죽었는데
     * health가 UP인 상태가 되는데, 그게 2026-08-01의 마지막 장면이다.
     *
     * <p><b>뒷정리를 종료 훅까지 미루지 않는다.</b> 프로세스는 계속 살아 있으므로,
     * 미루면 죽은 세션에 ping을 계속 쏘고({@code ping_send_failed}가 쌓인다)
     * 30초 요약도 계속 찍고 실행기·세션 자원이 프로세스가 죽을 때까지 남는다.
     *
     * <p><b>사유를 안 덮는 것과 뒷정리를 안 하는 것은 다른 일이다.</b> 한 번의 절단에
     * 신호가 둘 들어오는 길이 있고(좀비 판정 · 전송 절단 콜백), 먼저 온 쪽이 이미
     * STOPPED를 찍었다고 여기서 통째로 돌아가면 <b>이 세션이 자리를 문 채 남는다.</b>
     * 그러면 구독 반납도 판정도 안 나가고 다음 세션이 영영 못 선다 —
     * 재연결 루프가 붙어도 {@code start_skipped}만 반복한다.
     */
    private void handleClosed(SessionScope scope) {
        log.warn("chat.session.closed reason={}", StopReason.TRANSPORT_CLOSED);
        // 사유가 이미 찍혀 있어도(먼저 온 신호가 STOPPED를 남겼어도) 요청은 넘긴다.
        // <b>사유를 안 덮는 것과 뒷정리를 안 하는 것은 다른 일이다</b> — 여기서
        // 통째로 돌아가면 이 세션이 자리를 문 채 남아 구독 반납도 못 하고
        // 다음 세션이 영영 못 선다. 사유를 덮는 것은 status가 막고,
        // 영구 정지에서 재시도하지 않는 것은 루프가 막는다.
        requestReconnect(scope.no().get(), StopReason.TRANSPORT_CLOSED);
    }

    /**
     * <b>수집이 영영 끝났을 때 딱 한 번.</b> 부르는 곳이 둘이다 — 재연결 루프의
     * 비재시도 분기(영구 정지)와 {@code stop()}(프로세스 종료). 영구 정지 뒤에
     * 컨테이너가 내려가면 두 경로를 다 지나므로 이 가드가 유일한 방어다.
     *
     * <p>절단에서는 안 부른다. 재연결이 붙은 뒤로 절단은 끝이 아니고, 거기서
     * 판정을 내면 <b>최종이 아닌 최종 판정</b>이 세션 수만큼 쌓인다.
     */
    private void logVerdictOnce(StopReason reason) {
        if (!verdictLogged.compareAndSet(false, true)) {
            return;
        }
        // <b>열려 있는 절단 구간을 여기서 닫는다.</b> 닫는 자리가 원래 재접속
        // 성공 하나뿐이라, 다시 못 붙고 끝나면 판정 줄이 "outage=0ms
        // lastOutageFrom=none"이라고 말했다 — 그 절단 이후로 계속 못 받고 있는데도.
        // 판정을 내는 두 자리(영구 정지 · 프로세스 종료)가 둘 다 이 가드를 지나므로
        // 여기 두면 양쪽이 한 번에 덮인다.
        //
        // <b>recordOutage가 아니다.</b> 그쪽은 reconnects를 같이 올리는데, 다시 붙은
        // 적이 없으므로 그러면 한 번도 못 붙은 프로세스가 재연결 1회로 보고된다.
        Instant openSince = disconnectedAt.getAndSet(null);
        if (openSince != null) {
            metrics.recordUnrecoveredOutage(openSince, Instant.now());
        }
        // 세션의 값을 여기서 하나도 안 읽는다. 세션이 끝날 때 metrics가 전부
        // 걷어 두므로, 여기서 마지막 세션 것을 다시 읽으면 그 항만 앞 세션 값이 지워진다.
        //
        // 번호는 이 프로세스가 마지막으로 연 세션의 것이다. 판정은 프로세스 전체의
        // 누계라 "몇 번째까지 갔나"를 그 번호가 말한다.
        SummaryLogger.logFinalVerdict(lastSessionNo.get(), metrics.verdict(), reason);
    }

    /**
     * <b>개별 메시지를 로그로 남기지 않는다.</b> 세기만 한다.
     * 예외를 밖으로 내지 않는다 — 여기는 WS 수신 콜백 안이다.
     */
    private void handleFrame(SessionScope scope, EngineIoFrame frame) {
        if (frame.type() == EngineIoFrame.Type.PONG) {
            // 이 프레임을 받은 소켓의 하트비트에 적는다. 자리에서 다시 읽으면
            // 앞 세션의 pong이 새 세션의 공백을 메워 준 것처럼 보인다.
            Heartbeat beat = scope.heartbeat().get();
            if (beat != null) {
                beat.recordPong();
            }
            return;
        }
        if (frame.type() != EngineIoFrame.Type.EVENT) {
            return;
        }

        ChatMessage message = ChatEventDecoder.decodeChat(frame.payload());
        if (message != null) {
            metrics.recordMessage(message, System.currentTimeMillis());
            return;
        }

        SystemEvent event = ChatEventDecoder.decodeSystem(frame.payload());
        if (event != null) {
            metrics.recordSystemEvent(event.type());
            if ("revoked".equals(event.type())) {
                // 대응은 POK-93이다. 여기서는 온 사실만 남긴다.
                log.warn("chat.session.revoked");
            }
            return;
        }
        // CHAT도 SYSTEM도 아니게 깨진 것. 본문을 붙이지 않는다 — 깨진 JSON이 곧 본문이다.
        metrics.recordDecodeFailure();
    }

    @PreDestroy
    public void stop() {
        // 먼저 신호를 내린다. 뒷정리보다 뒤에 두면 수립 중인 스레드가 그동안
        // 시한을 계속 쓰고, 그 시간이 그대로 종료 시간에 얹힌다.
        // 백오프 대기도 이 신호를 보고 즉시 깨어난다 — 안 깨우면 종료가 대기
        // 간격만큼 매달리고, 컨테이너 유예를 넘기면 SIGKILL이 와서 구독 반납이
        // 안 나간다. 이 카드가 없애려던 좀비를 우리가 만드는 것이다.
        stopSignal.countDown();
        reconnector.shutdown();
        try {
            // 짧게 기다린다. 여기서 시한을 다 쓰면 shutdownNow()가 재연결 스레드를
            // 뒷정리 도중 인터럽트하고, 그때 구독 반납 REST와 우아한 소켓 닫기가
            // 통째로 실패해 abort만 남는다 = 우리가 없애려던 급사 경로.
            //
            // <b>2초는 수립 최악값이 아니다.</b> 수립 다섯 단계 중 ②③⑤는 조각마다
            // 중단 신호를 보고 100ms 안에 끊기지만, ①④는 <b>이미 나간 REST라 못 끊는다</b> —
            // 접속 2초 + 읽기 5초까지 간다. 그 스레드를 여기서 안 기다리고 버리는데,
            // <b>버려도 새는 것이 없기 때문이다</b>: ①④가 돌아오면 다음 단계 앞에서
            // 중단 신호에 걸리고(ChatSession.abortIfStopping), 그래도 소켓이 늦게
            // 성립하면 cleanUpOnce가 가드에 막힌 채로 그것을 닫는다.
            //
            // 2초는 <b>뒷정리 중인 스레드</b>를 위한 값이다 — 구독 반납 왕복이 실서버
            // 약 1초다. 그쪽을 인터럽트하는 것이 여기서 유일하게 비싼 실수다.
            reconnector.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        reconnector.shutdownNow();
        SessionScope scope = activeSession.get();
        if (scope != null) {
            cleanUpOnce(scope, status.reason());
        }
        // 판정이 나가야 할 두 시점 중 둘째. 영구 정지에서 이미 나갔으면 가드가 막는다.
        // <b>꺼져 있으면 안 낸다</b> — 원래 cleanUpOnce의 판정 호출이 그 검사를 달고
        // 있었고, 여기로 옮기면서 빠뜨리면 꺼진 서버가 종료마다 received=0 판정을 뱉는다.
        if (status.state() != CollectionStatus.State.DISABLED) {
            logVerdictOnce(status.reason());
        }
    }

    /**
     * 두 종료 경로(전송 절단 · 프로세스 종료)가 공유하는 정리 절차.
     *
     * <p><b>세션마다 한 번만 돈다.</b> 절단이 먼저 정리하고 나면 뒤이은 {@code stop()}은
     * 아무것도 안 한다. 다음 세션은 자기 가드를 갖고 오므로 여기를 다시 지난다.
     *
     * <p>가드가 막는 것은 <b>판정 두 줄</b>이다. 구독 반납은 이 가드가 아니라
     * {@code ChatSession.releaseAndClose()}의 세션 키 {@code getAndSet(null)}이
     * 독립적으로 막는다 — 두 스레드가 동시에 들어와도 둘째는 {@code SKIPPED}다.
     * <b>가드를 지워도 반납은 여전히 한 번이다</b>(CP1 실측). 반납 중복을 이 가드의
     * 존재 이유로 적어 두면 다음 사람이 없는 결함을 찾는다.
     *
     * @param scope  <b>부른 쪽이 들고 있던 덩어리.</b> 자리에서 다시 읽지 않는다 —
     *               반납 왕복 동안 자리의 주인이 바뀌었을 수 있다
     * @param reason 판정 라인에 남길 사유. null이면 정상 종료(SHUTDOWN)다
     */
    private void cleanUpOnce(SessionScope scope, StopReason reason) {
        if (!scope.cleanedUp().compareAndSet(false, true)) {
            // <b>가드에 막혀도 소켓은 닫는다.</b> 앞선 정리가 지나간 <i>뒤에</i> ②가
            // 성립시킨 소켓은 그 정리가 못 봤고, 여기서 안 닫으면 아무도 안 닫는다 —
            // 서버 쪽 자리가 죽은 전송을 알아챌 때까지(10초~4분 42초) 남고 상한은 3개다.
            // 판정 두 줄만 가드가 막으면 된다. close()는 멱등이다.
            scope.chat().close();
            return;
        }
        // 자리부터 비운다. 반납은 실서버에서 약 1초 걸리는 왕복인데, 그것을 끝낸
        // 뒤에 비우면 그 사이에 시작한 다음 세션을 우리가 지운다 — 그 세션은
        // 끊겨도 반납도 소켓 닫기도 못 하고 상한 3개를 먹는다.
        // compareAndSet이라 이미 남의 세션이 들어와 있으면 아무것도 안 한다.
        activeSession.compareAndSet(scope, null);

        // 만든 역순으로 닫는다. 요약이 먼저 멈춰야 닫히는 중인 지표를 안 읽는다.
        SummaryLogger logger = scope.summaryLogger().getAndSet(null);
        if (logger != null) logger.close();

        // 닫기 전에 걷는다. "닫힌 것에서 읽는다"를 코드로 남기지 않는다.
        // 하트비트도 삼킨 프레임 수도 세션과 함께 사라지므로, 여기서 안 걷으면
        // 판정 줄에 마지막 세션 값만 남고 앞 세션에서 ping이 막혔던 사실도
        // 프레임을 삼킨 사실도 사라진다 — 그 값이 POK-85가 정한 실패 조건이라
        // 합격선이 조용히 무력해진다.
        //
        // 하트비트가 없는 갈래(수립 실패)에서도 걷는다. 삼킨 프레임은 수립 도중에도
        // 늘 수 있어, 하트비트가 설 때까지 기다리면 그 세션 값이 통째로 사라진다.
        Heartbeat beat = scope.heartbeat().getAndSet(null);
        Instant since = beat == null ? null : scope.collectingSince().get();
        Duration maxPingGap = beat == null ? Duration.ZERO : beat.maxPingGap();
        Duration maxPongGap = beat == null ? Duration.ZERO : beat.maxPongGap();
        long sendFailures = beat == null ? 0L : beat.sendFailureCount();
        metrics.recordSessionEnd(
                since == null ? Duration.ZERO : Duration.between(since, Instant.now()),
                maxPingGap, maxPongGap, sendFailures,
                beat == null ? 0L : beat.callbackFailureCount(),
                scope.chat().sinkFailureCount());
        if (beat != null) {
            beat.close();
        }

        // <b>판정은 여기서 안 부른다. 절단은 더 이상 끝이 아니다.</b>
        // 이 줄이 세션 하나의 끝을 표시하고, 판정은 수집이 영영 끝났을 때만 나간다.
        //
        // 세션별 하트비트 값을 여기 싣는다 — 판정 줄은 프로세스 누계라 "몇 번째
        // 세션에서 ping이 막혔나"를 못 보여준다. PRD가 세션별 진단을 이 줄에
        // 두기로 한 이유다. 값은 위에서 걷어 둔 것을 쓴다 — 닫은 하트비트에서
        // 다시 읽는 코드를 남기지 않는다. 소켓을 닫는 것보다 먼저다:
        // 닫다가 터져도 이 줄은 남는다.
        if (status.state() != CollectionStatus.State.DISABLED) {
            log.info("chat.session.ended session={} reason={} maxPingGap={}ms maxPongGap={}ms"
                            + " sendFailures={}",
                    scope.no().get(), reason == null ? "SHUTDOWN" : reason,
                    maxPingGap.toMillis(), maxPongGap.toMillis(), sendFailures);
        }

        // 구독을 반납하고 끊는다고 알린다. 안 하면 세션 반납이 우리가 아니라
        // 서버가 죽은 전송을 알아채는 때에 달리고, 실측에서 10초와 4분 42초로
        // 갈렸다. 연결 상한이 3개라 짧은 간격의 재시작 세 번이면 막힌다.
        ChatSession.Release released = scope.chat().releaseAndClose();
        log.info("chat.session.released session={} subscription={}", scope.no().get(), released);
    }
}
