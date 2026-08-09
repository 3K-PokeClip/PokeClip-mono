package com.pokeclip.chat.collector.observe;

import com.pokeclip.chat.collector.engineio.Handshake;
import com.pokeclip.chat.collector.engineio.PingFailure;
import com.pokeclip.chat.collector.engineio.PingSender;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * ping을 보내는 전용 스케줄러.
 *
 * <p><b>이 스케줄러에는 ping 외의 어떤 작업도 얹지 않는다.</b> 2026-08-01 사고의
 * 원인이 정확히 그것이다 — ping 전송이 수신 처리와 같은 스레드에 얹혀 있었고,
 * 채팅이 몰리자 74초간 한 번도 못 나갔다. 서버는 조용히 끊었고 오류 로그는
 * 0줄이었다. 요약 로그·통계 집계를 여기 얹고 싶어지면 그때가 사고를
 * 재현하는 순간이다. SummaryLogger가 자기 스케줄러를 따로 갖는 이유다.
 *
 * <p>주기와 임계는 전부 핸드셰이크에서 파생한다. 25000을 박으면 치지직이
 * 값을 바꾸는 날 조용히 죽는다.
 */
public final class Heartbeat implements AutoCloseable {

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "chzzk-ping");
                t.setDaemon(true);
                return t;
            });

    private final AtomicLong lastPingNanos = new AtomicLong();
    private final AtomicLong lastPongNanos = new AtomicLong();
    private final AtomicLong maxPingGapNanos = new AtomicLong();
    private final AtomicLong maxPongGapNanos = new AtomicLong();
    private final AtomicLong sendFailures = new AtomicLong();
    /** 실패 콜백이 던진 횟수. 삼킨 예외가 남기는 유일한 흔적이라 요약이 이 값을 싣는다. */
    private final AtomicLong callbackFailures = new AtomicLong();
    private final Set<String> senderThreadNames = new ConcurrentSkipListSet<>();

    private Heartbeat() { }

    public static Heartbeat start(PingSender sender, Handshake handshake,
                                  Consumer<PingFailure.Cause> onSendFailed) {
        Heartbeat heartbeat = new Heartbeat();
        long now = System.nanoTime();
        heartbeat.lastPingNanos.set(now);
        heartbeat.lastPongNanos.set(now);

        long periodMillis = handshake.sendPeriod().toMillis();
        heartbeat.scheduler.scheduleAtFixedRate(() -> {
            heartbeat.senderThreadNames.add(Thread.currentThread().getName());
            try {
                sender.sendPing();
                heartbeat.mark(heartbeat.lastPingNanos, heartbeat.maxPingGapNanos);
            } catch (PingFailure e) {
                // 원인을 그대로 넘긴다. 여기서 뭉치면 우리 버그로 재연결이 돌고,
                // 재연결이 성공하는 한 그 버그는 영영 안 보이면서 상한만 태운다.
                heartbeat.sendFailures.incrementAndGet();
                heartbeat.notifyQuietly(() -> onSendFailed.accept(e.cause()));
            } catch (RuntimeException e) {
                // PingSender 구현이 PingFailure 밖의 것을 던진 경우. 스케줄러는 계속 돈다 —
                // 여기서 예외가 밖으로 나가면 scheduleAtFixedRate가 조용히 멈춰
                // ping이 영영 안 나간다. 그것이 2026-08-01의 결말이다.
                heartbeat.sendFailures.incrementAndGet();
                heartbeat.notifyQuietly(() -> onSendFailed.accept(PingFailure.Cause.CONNECTION_DEAD));
            }
        }, 0, periodMillis, TimeUnit.MILLISECONDS);

        return heartbeat;
    }

    /**
     * 콜백을 감싸는 이유는, 이것이 없으면 콜백이 던진 예외가 위 catch를 지나
     * 밖으로 나가 스케줄러를 죽이기 때문이다.
     *
     * <p>삼켰으면 센다. 콜백 안에 health를 DOWN으로 돌리는 일이 있으면,
     * 세지 않을 경우 수집이 죽었는데 health는 UP이고 요약에도 표시가 없는
     * 상태가 된다 — PRD가 유일한 치명적 실패로 규정한 모양이다.
     */
    private void notifyQuietly(Runnable callback) {
        try {
            callback.run();
        } catch (RuntimeException callbackFailure) {
            callbackFailures.incrementAndGet();
        }
    }

    /**
     * 소켓 없이 지표만 0으로 든 인스턴스. 요약 렌더링을 소켓 없이 검사하려고 둔다.
     * 스케줄러 스레드는 첫 작업이 들어올 때 만들어지므로 여기서는 안 생긴다.
     */
    public static Heartbeat idleForTest() {
        Heartbeat heartbeat = new Heartbeat();
        long now = System.nanoTime();
        heartbeat.lastPingNanos.set(now);
        heartbeat.lastPongNanos.set(now);
        return heartbeat;
    }

    /** 수신 스레드가 부른다. */
    public void recordPong() {
        mark(lastPongNanos, maxPongGapNanos);
    }

    private void mark(AtomicLong lastNanos, AtomicLong maxGapNanos) {
        long now = System.nanoTime();
        long gap = now - lastNanos.getAndSet(now);
        maxGapNanos.accumulateAndGet(gap, Math::max);
    }

    public Duration maxPingGap() { return gap(maxPingGapNanos, lastPingNanos); }
    public Duration maxPongGap() { return gap(maxPongGapNanos, lastPongNanos); }

    /** 마지막 이후 흘러가는 중인 공백도 센다. 안 그러면 완전히 멈춘 상태가 0으로 보인다. */
    private Duration gap(AtomicLong maxGapNanos, AtomicLong lastNanos) {
        long running = System.nanoTime() - lastNanos.get();
        return Duration.ofNanos(Math.max(maxGapNanos.get(), running));
    }

    public Instant lastPingAt() { return toInstant(lastPingNanos.get()); }
    public Instant lastPongAt() { return toInstant(lastPongNanos.get()); }
    public long sendFailureCount() { return sendFailures.get(); }
    public long callbackFailureCount() { return callbackFailures.get(); }
    public Set<String> senderThreadNames() { return Set.copyOf(senderThreadNames); }

    private static Instant toInstant(long nanos) {
        return Instant.now().minusNanos(System.nanoTime() - nanos);
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
