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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

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
    /**
     * 좀비 구간 <b>하나에 한 번만</b> 알린다. 매 주기 알리면 재연결 요청이
     * 임계를 넘는 내내 쌓인다.
     *
     * <p>pong이 다시 오면 {@link #recordPong()}이 재무장한다. 프로세스 생애 1회로
     * 잠가 두면, 재연결이 아직 안 붙은 채 같은 하트비트가 살아 있는 동안 다음
     * 좀비 구간이 통째로 안 알려진다 — <b>가드가 사고를 가리는 모양</b>이고
     * {@code cleanedUp}이 세션 생애로 잠겨 있던 것과 같은 결함이다.
     */
    private final AtomicBoolean pongTimeoutReported = new AtomicBoolean();

    /**
     * 송신 실패도 <b>구간 하나에 한 번만</b> 알린다. 위 좀비 가드와 같은 이유다 —
     * 매 주기 알리면 재연결 요청이 실패가 이어지는 내내 쌓이고, 첫 요청이 만든
     * 루프에 밀린 나머지가 대기 사유로 앉았다가 <b>재접속에 성공한 뒤 재생돼
     * 막 세운 세션을 헐어낸다.</b>
     *
     * <p>송신이 한 번이라도 성공하면 재무장한다. 잠가 두면 두 번째 실패 구간이
     * 통째로 안 알려져 <b>가드가 사고를 가린다.</b>
     *
     * <p>대가: 구간 중간에 원인이 바뀌어도({@code CONNECTION_DEAD} → {@code MISUSE})
     * 첫 원인만 나간다. 첫 신호가 이미 재연결을 결정한 뒤라 뒤바뀔 여지가 없고,
     * {@code MISUSE}는 송신 지점이 하나인 동안 발화하지 않는다.
     */
    private final AtomicBoolean sendFailureReported = new AtomicBoolean();

    /**
     * {@link #close()}가 시작되면 선다. <b>이 뒤로는 어떤 사건도 밖에 알리지 않는다.</b>
     *
     * <p>{@code shutdownNow()}가 돌고 있는 주기 작업을 인터럽트하면 송신이
     * {@code InterruptedException}으로 깨지고, 그것은 {@code IllegalStateException}이
     * 아니라서 {@code CONNECTION_DEAD}로 분류된다 — <b>우리 정리가 스스로 절단
     * 신호를 만든다.</b> 송신이 오래 매달리는 때가 곧 연결이 이미 병든 때이고
     * 우리가 하트비트를 닫는 것도 그때라, 드문 경합이 아니라 정리마다 나는 일이다.
     *
     * <p>재연결이 붙으면 그 신호가 <b>깨끗한 정리마다 재연결을 낳고</b>, 이미 도는
     * 루프에 밀려 대기 사유로 앉았다가 새로 세운 멀쩡한 세션을 헐어낸다.
     */
    private final AtomicBoolean closing = new AtomicBoolean();

    private Heartbeat() { }

    public static Heartbeat start(PingSender sender, Handshake handshake,
                                  HeartbeatListener listener) {
        Heartbeat heartbeat = new Heartbeat();
        long now = System.nanoTime();
        heartbeat.lastPingNanos.set(now);
        heartbeat.lastPongNanos.set(now);

        long periodMillis = handshake.sendPeriod().toMillis();
        Duration pongThreshold = handshake.pongThreshold();
        heartbeat.scheduler.scheduleAtFixedRate(() -> {
            heartbeat.senderThreadNames.add(Thread.currentThread().getName());
            try {
                sender.sendPing();
                heartbeat.mark(heartbeat.lastPingNanos, heartbeat.maxPingGapNanos);
                // 송신이 돌아왔다. 다음 실패 구간은 다시 알려야 한다.
                heartbeat.sendFailureReported.set(false);
            } catch (PingFailure e) {
                // 원인을 그대로 넘긴다. 여기서 뭉치면 우리 버그로 재연결이 돌고,
                // 재연결이 성공하는 한 그 버그는 영영 안 보이면서 상한만 태운다.
                heartbeat.sendFailures.incrementAndGet();
                if (heartbeat.sendFailureReported.compareAndSet(false, true)) {
                    heartbeat.notifyQuietly(() -> listener.onSendFailed(e.cause()));
                }
                // 송신이 죽었으면 pong이 안 오는 것은 당연하다. 여기서 이어서
                // 판정하면 같은 절단을 두 사유로 두 번 알린다.
                return;
            } catch (RuntimeException e) {
                // PingSender 구현이 PingFailure 밖의 것을 던진 경우. 스케줄러는 계속 돈다 —
                // 여기서 예외가 밖으로 나가면 scheduleAtFixedRate가 조용히 멈춰
                // ping이 영영 안 나간다. 그것이 2026-08-01의 결말이다.
                heartbeat.sendFailures.incrementAndGet();
                if (heartbeat.sendFailureReported.compareAndSet(false, true)) {
                    heartbeat.notifyQuietly(() -> listener.onSendFailed(PingFailure.Cause.CONNECTION_DEAD));
                }
                return;
            }

            // pong 판정은 산술 비교 둘뿐이라 이 스케줄러에 얹어도 된다. I/O도 로그도 없다.
            // 콜백이 무거워지는 순간이 8/1 사고를 재현하는 순간이라 계약으로 막았다.
            //
            // maxPongGap()이 아니라 sincePong()이다. 전자는 역대 최대라 한 번 올라가면
            // 안 내려온다 — "지금 pong이 안 온다"가 아니라 "한 번이라도 늦은 적이 있다"를
            // 판정하게 되어, 회복된 지연이 살아 있는 세션을 끊는다. 3층 CLAUDE.md가
            // 테스트에서 일부러 지운 자를 운영 분기 조건으로 되살리는 셈이다.
            Duration gap = heartbeat.sincePong();
            if (gap.compareTo(pongThreshold) >= 0
                    && heartbeat.pongTimeoutReported.compareAndSet(false, true)) {
                heartbeat.notifyQuietly(() -> listener.onPongTimeout(gap));
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
        if (closing.get()) {
            // 닫는 중에 나온 신호는 밖에서 온 것이 아니라 우리가 만든 것이다.
            // 세는 것은 그대로 둔다 — 송신이 실제로 깨진 것은 사실이고,
            // 요약·판정이 그 수를 싣는다.
            return;
        }
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
        // 좀비 구간이 끝났다. 다음 구간은 다시 알려야 한다.
        pongTimeoutReported.set(false);
    }

    private void mark(AtomicLong lastNanos, AtomicLong maxGapNanos) {
        long now = System.nanoTime();
        long gap = now - lastNanos.getAndSet(now);
        maxGapNanos.accumulateAndGet(gap, Math::max);
    }

    /**
     * 마지막 pong 이후 <b>지금까지</b> 흐른 시간. {@link #maxPongGap()}과 다르다 —
     * 그쪽은 요약·판정용 역대 최대라 한 번 오르면 안 내려온다.
     * <b>좀비 판정은 현재 상태를 봐야 한다.</b>
     */
    public Duration sincePong() {
        return Duration.ofNanos(System.nanoTime() - lastPongNanos.get());
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

    /**
     * <b>깃발을 먼저 세우고 인터럽트한다.</b> 순서가 반대면 인터럽트가 만든 신호가
     * 깃발보다 먼저 콜백에 닿아, 막으려던 것이 그대로 나간다.
     */
    @Override
    public void close() {
        closing.set(true);
        scheduler.shutdownNow();
    }
}
