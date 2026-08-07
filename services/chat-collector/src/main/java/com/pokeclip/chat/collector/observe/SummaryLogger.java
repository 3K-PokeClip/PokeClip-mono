package com.pokeclip.chat.collector.observe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * 30초마다 요약 한 줄. <b>자기 전용 스케줄러를 갖는다.</b>
 *
 * <p>이것을 ping 스케줄러에 얹고 싶어지는 순간이 2026-08-01 사고를 재현하는
 * 순간이다 — 그때 ping이 다른 일과 스레드를 공유하다 74초간 못 나갔다.
 * 요약은 로그 I/O라 언제든 느려질 수 있고, 느려지면 ping이 뒤에 줄 선다.
 *
 * <p>개별 메시지 로그는 0줄이다. 여기 나가는 것도 세는 값뿐이고
 * 본문·작성자 식별자·닉네임은 어느 필드에도 없다.
 */
public final class SummaryLogger implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SummaryLogger.class);

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "chzzk-summary");   // ping 스레드와 다른 이름
                t.setDaemon(true);
                return t;
            });

    private final Set<String> emitterThreadNames = new ConcurrentSkipListSet<>();

    private SummaryLogger() { }

    /**
     * @param sinkFailures 삼킨 싱크 예외의 수를 읽어 오는 곳. ChatSession이 들고
     *                     있는 값이라 공급자로 받는다 — 세션은 재수립마다 바뀐다
     */
    public static SummaryLogger start(CollectionMetrics metrics, Heartbeat heartbeat,
                                      Duration period, LongSupplier sinkFailures) {
        SummaryLogger logger = new SummaryLogger();
        long periodMillis = period.toMillis();
        logger.scheduler.scheduleAtFixedRate(() -> {
            logger.emitterThreadNames.add(Thread.currentThread().getName());
            try {
                log.info("{}", render(metrics.snapshot(), heartbeat, sinkFailures.getAsLong()));
            } catch (RuntimeException e) {
                // 요약이 터져도 스케줄러는 계속 돈다. 여기서 예외가 밖으로 나가면
                // scheduleAtFixedRate가 조용히 멈춰 요약이 영영 안 나가고,
                // 그러면 수집이 멀쩡한지 아무도 못 본다.
                log.warn("chat.summary.render_failed causeType={}", e.getClass().getSimpleName());
            }
        }, periodMillis, periodMillis, TimeUnit.MILLISECONDS);
        return logger;
    }

    /** 순수 함수라 스케줄러 없이도 검사할 수 있다. */
    public static String render(CollectionMetrics.Snapshot s, Heartbeat heartbeat, long sinkFailures) {
        return "chat.summary"
                + " received=" + s.received()
                + " maxReceiveGap=" + duration(s.maxReceiveGap())
                + " lastReceivedAt=" + instant(s.lastReceivedAt())
                + " lastPingAt=" + instant(heartbeat.lastPingAt())
                + " maxPingGap=" + duration(heartbeat.maxPingGap())
                + " lastPongAt=" + instant(heartbeat.lastPongAt())
                + " maxPongGap=" + duration(heartbeat.maxPongGap())
                + " orderViolations=" + s.orderViolations()
                + " delayMin=" + duration(s.delayMin())
                + " delayMedian=" + duration(s.delayMedian())
                + " delayMax=" + duration(s.delayMax())
                + " system=" + s.systemEvents()
                + " decodeFailures=" + s.decodeFailures()
                + " sendFailures=" + heartbeat.sendFailureCount()
                + " callbackFailures=" + heartbeat.callbackFailureCount()
                + " sinkFailures=" + sinkFailures;
    }

    /**
     * 수집이 끝났을 때 한 줄. <b>30초 요약은 창 값이라 이 줄이 없으면 판정하려고
     * 20줄을 뒤져야 한다.</b> 여기 값은 전부 세션 전체 누적이다.
     *
     * <p>수립조차 못 했을 때도 나와야 하므로 static이다 — 그때는 SummaryLogger
     * 인스턴스가 아예 없다.
     *
     * <p>요약과 같은 규칙이다. 본문·작성자 식별자·닉네임·토큰은 어느 필드에도 없다.
     */
    public static void logFinalVerdict(CollectionMetrics.Verdict verdict, Heartbeat heartbeat,
                                       long sinkFailures, Duration collectedFor, Object stopReason) {
        log.info("{}", renderVerdict(verdict, heartbeat, sinkFailures, collectedFor, stopReason));
    }

    /** 순수 함수라 로그 없이도 검사할 수 있다. */
    public static String renderVerdict(CollectionMetrics.Verdict v, Heartbeat heartbeat,
                                       long sinkFailures, Duration collectedFor, Object stopReason) {
        return "chat.session.verdict"
                + " received=" + v.totalReceived()
                + " collectedFor=" + duration(collectedFor)
                + " lastReceivedAt=" + instant(v.lastReceivedAt())
                + " maxReceiveGap=" + duration(v.maxReceiveGap())
                + " maxPingGap=" + duration(heartbeat.maxPingGap())
                + " maxPongGap=" + duration(heartbeat.maxPongGap())
                + " orderViolations=" + v.orderViolations()
                + " delayMin=" + duration(v.delayMin())
                + " delayMedian=" + duration(v.delayMedian())
                + " delayMax=" + duration(v.delayMax())
                + " delaySamples=" + v.delaySamples()
                + " system=" + v.systemEvents()
                + " decodeFailures=" + v.decodeFailures()
                + " sendFailures=" + heartbeat.sendFailureCount()
                + " callbackFailures=" + heartbeat.callbackFailureCount()
                + " sinkFailures=" + sinkFailures
                // 왜 끝났는지가 없으면 "정상 종료"와 "조용히 끊겼다"가 같은 줄이 된다.
                + " reason=" + (stopReason == null ? "SHUTDOWN" : stopReason);
    }

    public Set<String> emitterThreadNames() { return Set.copyOf(emitterThreadNames); }

    private static String duration(Duration d) {
        long millis = d.toMillis();
        return millis < 1_000 ? millis + "ms" : (millis / 100) / 10.0 + "s";
    }

    /** 한 건도 못 받았으면 시각이 없다. 0을 찍으면 1970년으로 읽힌다. */
    private static String instant(Instant at) {
        return at == null ? "none" : at.toString();
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
