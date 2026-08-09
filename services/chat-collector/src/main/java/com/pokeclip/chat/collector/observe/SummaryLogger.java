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
     * 20줄을 뒤져야 한다.</b>
     *
     * <p><b>값 항목이 전부 프로세스 생애 누계다. 아래가 전 항목이다</b> —
     * 빠진 항이 하나라도 있으면 "이 줄은 어느 경계인가"가 다시 열린다.
     *
     * <ul>
     *   <li><b>프로세스 누계</b>(지표를 세션마다 갈아 끼우지 않는다) — {@code received}·
     *       {@code lastReceivedAt}·{@code maxReceiveGap}·{@code orderViolations}·
     *       {@code delayMin}·{@code delayMedian}·{@code delayMax}·{@code delaySamples}·
     *       {@code system}·{@code decodeFailures}·{@code collectedFor}·
     *       {@code maxPingGap}·{@code maxPongGap}·{@code sendFailures}·
     *       {@code callbackFailures}·{@code sinkFailures}·
     *       {@code reconnects}·{@code outage}
     *   <li>{@code lastOutageFrom}·{@code lastOutageTo}는 누계가 아니라
     *       <b>마지막 절단 하나</b>의 시각이다. 누계로 읽으면 "이 시각부터 내내
     *       끊겨 있었다"가 된다. 한 번도 안 끊겼으면 둘 다 {@code none}이고,
     *       <b>{@code lastOutageFrom}만 있고 {@code lastOutageTo}가 {@code none}이면
     *       끊긴 채로 끝난 것이다</b> — 그때 {@code outage}는 판정 시각까지의
     *       하한이지 유실의 전부가 아니다
     *   <li>{@code session}은 경계가 아니라 몇 번째 세션의 판정인가다
     *   <li>{@code reason}은 경계가 아니라 그 판정의 사유다
     * </ul>
     *
     * <p><b>{@code maxReceiveGap}은 누계지만 절단 구간을 빼고 잰다.</b> 안 빼면
     * 끊겨 있던 시간이 통째로 하나의 수신 공백이 되어 "한산했을 뿐"과 "끊겨
     * 있었다"가 같은 숫자로 보인다 — 한산한 것은 정상이고(방송을 꺼도 세션은
     * 안 끊긴다) 끊긴 것은 유실이다. 빼낸 시간은 {@code outage}가 든다.
     *
     * <p><b>{@code sinkFailures}도 {@code CollectionMetrics}가 걷어 올린다.</b>
     * 세는 주체가 {@code ChatSession}이라 세션과 함께 사라지는데, 여기서 그 세션
     * 값을 직접 읽으면 이 한 항만 경계가 다르다. 그러면 판정이 프로세스 종료
     * 1회로 옮겨지는 순간 앞 세션이 삼킨 프레임 수가 어디에도 안 남는다 —
     * {@code maxPingGap}과 같은 결함이다.
     *
     * <p>하트비트 값을 {@code Heartbeat}에서 직접 읽지 않는다 — 그 객체는 소켓마다
     * 새로 만들어져 <b>마지막 세션 값만</b> 든다. 세션이 끝날 때 걷은 것을 여기서 쓴다.
     *
     * <p><b>"방송 전체"가 아니다.</b> 세션은 방송이 아니라 계정에 붙어 방송을 꺼도
     * 안 끊기고(361초 실측), 방송 경계를 알려면 POK-82가 필요하다.
     *
     * <p>수립조차 못 했을 때도 나와야 하므로 static이다 — 그때는 SummaryLogger
     * 인스턴스가 아예 없다.
     *
     * <p>요약과 같은 규칙이다. 본문·작성자 식별자·닉네임·토큰은 어느 필드에도 없다.
     */
    public static void logFinalVerdict(long session, CollectionMetrics.Verdict verdict,
                                       Object stopReason) {
        log.info("{}", renderVerdict(session, verdict, stopReason));
    }

    /** 순수 함수라 로그 없이도 검사할 수 있다. */
    public static String renderVerdict(long session, CollectionMetrics.Verdict v,
                                       Object stopReason) {
        return "chat.session.verdict"
                // 첫 항이다. 줄을 세션 단위로 고르는 사람도 도구도 여기서 갈린다.
                + " session=" + session
                + " received=" + v.totalReceived()
                + " collectedFor=" + duration(v.totalCollectedFor())
                + " lastReceivedAt=" + instant(v.lastReceivedAt())
                + " maxReceiveGap=" + duration(v.maxReceiveGap())
                + " maxPingGap=" + duration(v.maxPingGap())
                + " maxPongGap=" + duration(v.maxPongGap())
                + " orderViolations=" + v.orderViolations()
                + " delayMin=" + duration(v.delayMin())
                + " delayMedian=" + duration(v.delayMedian())
                + " delayMax=" + duration(v.delayMax())
                + " delaySamples=" + v.delaySamples()
                + " system=" + v.systemEvents()
                + " decodeFailures=" + v.decodeFailures()
                + " sendFailures=" + v.sendFailures()
                + " callbackFailures=" + v.callbackFailures()
                + " sinkFailures=" + v.sinkFailures()
                // 끊겼다 붙은 횟수와 그동안 놓친 시간. 위 maxReceiveGap이 절단을
                // 빼고 재므로, 이 둘이 없으면 유실 구간이 어느 항에도 안 남는다.
                + " reconnects=" + v.reconnects()
                + " outage=" + duration(v.totalOutage())
                // PRD 완료 조건: "끊긴 시각·복구 시각 두 값". 누적 시간만으로는
                // "언제 놓쳤나"를 못 찾는다 — 영상과 대조하려면 시각이 필요하다.
                + " lastOutageFrom=" + instant(v.lastOutageFrom())
                + " lastOutageTo=" + instant(v.lastOutageTo())
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
