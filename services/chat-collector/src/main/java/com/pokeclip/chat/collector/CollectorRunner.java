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
    private final CollectionMetrics metrics = new CollectionMetrics();

    private volatile ChatSession session;
    private volatile Heartbeat heartbeat;
    private volatile SummaryLogger summaryLogger;
    private volatile Instant collectingSince;

    /** 판정 라인은 종료 경로가 둘이라도 한 번만 나간다. */
    private final AtomicBoolean verdictLogged = new AtomicBoolean();

    public CollectorRunner(ChzzkProperties properties, CollectionStatus status) {
        this.properties = properties;
        this.status = status;
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
                RestClient.create(), properties.baseUrl(), properties.accessToken()));
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
     */
    private void handleClosed() {
        if (status.state() == CollectionStatus.State.STOPPED) {
            return;                       // 이미 사유가 있다. 덮어쓰지 않는다.
        }
        log.warn("chat.session.closed reason={}", StopReason.TRANSPORT_CLOSED);
        status.stopped(StopReason.TRANSPORT_CLOSED);
        logVerdictOnce(StopReason.TRANSPORT_CLOSED);
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
        // 만든 역순으로 닫는다. 요약이 먼저 멈춰야 닫히는 중인 지표를 안 읽는다.
        SummaryLogger logger = summaryLogger;
        if (logger != null) logger.close();
        summaryLogger = null;

        Heartbeat beat = heartbeat;
        if (beat != null) beat.close();

        // 판정 라인이 먼저다. 소켓을 닫으면 heartbeat 지표를 못 읽는 것은 아니지만,
        // 닫는 도중 예외가 나도 판정은 남아야 한다.
        if (status.state() != CollectionStatus.State.DISABLED) {
            logVerdictOnce(status.reason());
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
