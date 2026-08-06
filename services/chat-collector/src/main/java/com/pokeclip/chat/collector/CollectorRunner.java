package com.pokeclip.chat.collector;

import com.pokeclip.chat.collector.chzzk.ChatEventDecoder;
import com.pokeclip.chat.collector.chzzk.ChatMessage;
import com.pokeclip.chat.collector.chzzk.ChatSession;
import com.pokeclip.chat.collector.chzzk.ChzzkSessionClient;
import com.pokeclip.chat.collector.chzzk.SessionEstablishException;
import com.pokeclip.chat.collector.chzzk.SystemEvent;
import com.pokeclip.chat.collector.engineio.EngineIoFrame;
import com.pokeclip.chat.collector.observe.CollectionMetrics;
import com.pokeclip.chat.collector.observe.Heartbeat;
import com.pokeclip.chat.collector.observe.SummaryLogger;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 부팅 시 수집을 시작하고 종료 시 역순으로 닫는다.
 *
 * <p><b>재시도하지 않는다.</b> 실패는 사유를 로그와 health에 남기고 멈춘다 —
 * 재연결은 POK-86이고, 여기서 넣으면 두 카드가 같은 코드를 두 번 짠다.
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

    private volatile ChatSession session;
    private volatile Heartbeat heartbeat;
    private volatile SummaryLogger summaryLogger;
    private volatile Instant collectingSince;

    /** 판정 라인은 종료 경로가 둘이라도 한 번만 나간다. */
    private final AtomicBoolean verdictLogged = new AtomicBoolean();

    /**
     * 뒷정리도 마찬가지다. <b>종료 경로 둘이 같은 절차를 공유하므로</b> 가드가
     * 없으면 구독 반납이 두 번 나간다. 소켓을 우리 손으로 닫는 것이 다시
     * {@code onClose}를 부를 수도 있어, 같은 스레드로 재진입하는 길도 있다.
     */
    private final AtomicBoolean cleanedUp = new AtomicBoolean();

    public CollectorRunner(ChzzkProperties properties, CollectionStatus status,
                           RestClient.Builder restClientBuilder) {
        this.properties = properties;
        this.status = status;
        // 빌더는 프로토타입 빈이다. 한 번만 build()해서 들고 있는다.
        this.restClient = restClientBuilder.build();
    }

    @Override
    public void run(ApplicationArguments args) {
        start();
    }

    /**
     * 지금까지의 관측값. 유출 검사가 "바늘이 실제로 코드 안을 지나갔나"를
     * 확인하는 데 쓴다 — 그 대조가 없으면 아무것도 안 흘렀을 때
     * "안 샜다"가 자동으로 참이 된다.
     */
    public CollectionMetrics metrics() {
        return metrics;
    }

    public void start() {
        if (!properties.enabled()) {
            // 붙지 않는다. 로그 한 줄은 남긴다 — "왜 채팅이 안 들어오지"의
            // 첫 번째 답이 대개 이것이다.
            log.info("chat.collector.disabled");
            status.disabled();
            return;
        }

        status.establishing();
        ChatSession opening = new ChatSession(new ChzzkSessionClient(
                restClient, properties.baseUrl(), properties.accessToken()));
        opening.onFrame(this::handleFrame);
        opening.onClosed(this::handleClosed);
        session = opening;

        try {
            ChatSession.Established established = opening.open(properties.establishTimeout());
            heartbeat = Heartbeat.start(established.socket(), established.handshake(),
                    () -> log.warn("chat.session.ping_send_failed"));
            // 세션은 재수립마다 바뀌므로 값이 아니라 읽는 길을 넘긴다.
            summaryLogger = SummaryLogger.start(metrics, heartbeat, SUMMARY_PERIOD,
                    () -> {
                        ChatSession current = session;
                        return current == null ? 0L : current.sinkFailureCount();
                    });
            collectingSince = Instant.now();
            status.collecting();
            log.info("chat.session.collecting pingIntervalMs={} sendPeriodMs={}",
                    established.handshake().pingInterval().toMillis(),
                    established.handshake().sendPeriod().toMillis());
        } catch (SessionEstablishException e) {
            // URL·응답 본문·토큰을 안 찍는다. 단계와 사유면 어디서 막혔는지 충분하다.
            log.warn("chat.session.stopped stage={} reason={}", e.stage(), e.reason());
            status.stopped(e.reason());
            stop();
        }
    }

    /**
     * 서버가 조용히 끊었을 때. 상태를 COLLECTING으로 두면 수집이 죽었는데
     * health가 UP인 상태가 되는데, 그게 2026-08-01의 마지막 장면이다.
     *
     * <p><b>뒷정리를 종료 훅까지 미루지 않는다.</b> 프로세스는 계속 살아 있으므로,
     * 미루면 죽은 세션에 ping을 계속 쏘고({@code ping_send_failed}가 쌓인다)
     * 30초 요약도 계속 찍고 실행기·세션 자원이 프로세스가 죽을 때까지 남는다.
     */
    private void handleClosed() {
        if (status.state() == CollectionStatus.State.STOPPED) {
            return;                       // 이미 사유가 있다. 덮어쓰지 않는다.
        }
        log.warn("chat.session.closed reason={}", StopReason.TRANSPORT_CLOSED);
        status.stopped(StopReason.TRANSPORT_CLOSED);
        cleanUpOnce(StopReason.TRANSPORT_CLOSED);
    }

    /**
     * 수집이 끝났을 때 딱 한 번. 종료 경로가 둘이라(전송 절단 · 프로세스 종료)
     * 가드가 없으면 두 줄이 나가고, 그러면 어느 것이 진짜 끝인지 흐려진다.
     */
    private void logVerdictOnce(StopReason reason) {
        if (!verdictLogged.compareAndSet(false, true)) {
            return;
        }
        ChatSession current = session;
        Heartbeat beat = heartbeat;
        Instant since = collectingSince;
        SummaryLogger.logFinalVerdict(
                metrics.verdict(),
                beat == null ? Heartbeat.idleForTest() : beat,
                current == null ? 0L : current.sinkFailureCount(),
                since == null ? Duration.ZERO : Duration.between(since, Instant.now()),
                reason);
    }

    /**
     * <b>개별 메시지를 로그로 남기지 않는다.</b> 세기만 한다.
     * 예외를 밖으로 내지 않는다 — 여기는 WS 수신 콜백 안이다.
     */
    private void handleFrame(EngineIoFrame frame) {
        if (frame.type() == EngineIoFrame.Type.PONG) {
            Heartbeat current = heartbeat;
            if (current != null) {
                current.recordPong();
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
        cleanUpOnce(status.reason());
    }

    /**
     * 두 종료 경로(전송 절단 · 프로세스 종료)가 공유하는 정리 절차.
     *
     * <p><b>한 번만 돈다.</b> 절단이 먼저 정리하고 나면 뒤이은 {@code stop()}은
     * 아무것도 안 한다 — 안 그러면 구독 반납이 두 번 나간다. POK-86이 재연결을
     * 붙이면 이 가드를 세션마다 초기화해야 한다.
     *
     * @param reason 판정 라인에 남길 사유. null이면 정상 종료(SHUTDOWN)다
     */
    private void cleanUpOnce(StopReason reason) {
        if (!cleanedUp.compareAndSet(false, true)) {
            return;
        }

        // 만든 역순으로 닫는다. 요약이 먼저 멈춰야 닫히는 중인 지표를 안 읽는다.
        SummaryLogger logger = summaryLogger;
        if (logger != null) logger.close();
        summaryLogger = null;

        Heartbeat beat = heartbeat;
        if (beat != null) beat.close();

        // 판정 라인이 먼저다. 소켓을 닫으면 heartbeat 지표를 못 읽는 것은 아니지만,
        // 닫는 도중 예외가 나도 판정은 남아야 한다.
        if (status.state() != CollectionStatus.State.DISABLED) {
            logVerdictOnce(reason);
        }
        heartbeat = null;

        ChatSession current = session;
        if (current != null) {
            // 구독을 반납하고 끊는다고 알린다. 안 하면 세션 반납이 우리가 아니라
            // 서버가 죽은 전송을 알아채는 때에 달리고, 실측에서 10초와 4분 42초로
            // 갈렸다. 연결 상한이 3개라 짧은 간격의 재시작 세 번이면 막힌다.
            ChatSession.Release released = current.releaseAndClose();
            log.info("chat.session.released subscription={}", released);
        }
        session = null;
    }
}
