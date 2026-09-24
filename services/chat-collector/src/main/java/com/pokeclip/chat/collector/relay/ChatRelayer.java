package com.pokeclip.chat.collector.relay;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * 중계 스레드 하나({@code chzzk-relay})가 바구니를 비워 방송별로 clip에 민다.
 *
 * <p><b>수신은 넣기만 하고 보내는 것은 여기서만 한다</b> — 수신 콜백이 HTTP를 부르면 WS 수신 스레드가
 * clip 응답만큼 붙들려 채팅 폭주 때 수신이 밀린다(8/1 ping 사고와 같은 구조).
 *
 * <p><b>재시도하지 않는다.</b> 실패·모르는 방송은 버리고 센다 — 표가 정본이고 놓친 구간은 프론트가
 * {@code seq} 빈틈으로 알아채 범위 창구로 메운다(F3). 되돌려 넣으면 clip이 죽은 동안 바구니가 차서
 * <b>살아 있는 방송의 새 채팅</b>이 밀려난다.
 *
 * <p><b>묶음</b>: 비었으면 {@code flushMaxDelay}만큼 쉬고, 있으면 최대 {@link #MAX_BATCH}건을 꺼내 보낸다.
 * 보내는 동안 쌓인 것은 다음 바퀴에 한 묶음이 된다(F13) — 따로 묶음 창을 두지 않는다.
 * {@code offer}마다 깨우지 않는 것은 선택이다: 수신 핫패스에서 할 일을 하나라도 줄이고, 한가할 때의
 * 추가 지연은 그 주기(100ms)가 상한이라 목표(p95 1초) 안이다.
 *
 * <p>🔴 <b>방송별로 차례로 보낸다.</b> 한 방송의 clip 응답이 느리면 뒤 방송이 기다린다 — 그래서 시한이
 * 전용(접속 0.5초·읽기 1초, F11)이다. 방송마다 스레드를 두지 않은 이유는 방송 순서 안에서 번호 순서를
 * 지키는 가장 단순한 방법이 스레드 하나이기 때문이다.
 */
public class ChatRelayer implements RelayCounters, RelayLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ChatRelayer.class);

    /** 한 바퀴 최대 건수. 요청 본문이 이만큼까지 커진다. */
    static final int MAX_BATCH = 500;

    private final RelayBuffer buffer;
    private final ClipRelayClient client;
    private final long idleNanos;
    private final Thread thread;

    /**
     * 셈 셋은 <b>이 객체의 자물쇠</b> 아래에 있다. 기한을 넘긴 닫기({@link #awaitClosed})가 「나가 있는 묶음 +
     * 바구니 잔량」을 버린 수로 <b>확정</b>하는 순간과, 중계 스레드가 늦게 돌아와 그 묶음의 결과를 세려는 순간이
     * 겹치므로 한 자물쇠로 한쪽만 이기게 한다(감사 L5).
     */
    private long relayed;
    private long relayDropped;
    /** drain했지만 아직 결과를 안 센 건수. 확정할 때 버린 수로 옮긴다. */
    private long inFlight;

    /** {@link #beginClose()}가 켠다. 켜기 <b>전에</b> 바구니를 닫으므로, 켜진 것을 본 뒤의 빈 drain은 진짜 끝이다. */
    private volatile boolean closing;
    /**
     * 닫기 기한을 넘겨 셈을 확정했다. 그 뒤로 중계 스레드는 <b>더 보내지 않고</b>(남은 방송 포함) 결과도 안 센다 —
     * 확정 때 이미 버린 수로 셌다. 🔴 나가 있던 요청이 실제로는 clip에 닿았을 수 있다: 결과를 모르는 채
     * 버린 수로 세는 쪽을 골랐다 — 판정 줄이 닫기 직후 읽는 값이 나중에 바뀌면 그 줄의 등식이 거짓이 된다.
     */
    private volatile boolean abandoned;

    public ChatRelayer(RelayBuffer buffer, ClipRelayClient client, RelayProperties relay) {
        if (relay.flushMaxDelay() == null || relay.flushMaxDelay().isNegative() || relay.flushMaxDelay().isZero()) {
            throw new IllegalStateException("pokeclip.relay.flush-max-delay는 0보다 커야 한다: " + relay.flushMaxDelay());
        }
        this.buffer = buffer;
        this.client = client;
        this.idleNanos = relay.flushMaxDelay().toNanos();
        this.thread = new Thread(this::loop, "chzzk-relay");
        this.thread.setDaemon(true);
    }

    public void start() {
        thread.start();
    }

    /**
     * 🔴 <b>{@code Error}로 죽어도 나가 있던 묶음을 버린 수로 센다</b>(감사 L21). {@code send}는 {@code RuntimeException}만
     * 받으므로 {@code Error}(OOM·링크 오류)는 스레드를 끝낸다. 그때 {@code inFlight}가 어느 셈에도 안 옮겨지면
     * {@link #awaitClosed}는 죽은 스레드를 보고 곧장 돌아가 판정 줄의 등식
     * {@code relayOffered = relayed + relayDropped + bufferDropped}가 조용히 깨진다. 삼키지 않고 다시 던진다 —
     * 스레드가 살아 있는 척하면 바구니만 차고 아무도 모른다. 되살리지는 않는다(뒤 채팅은 바구니 넘침으로 세어진다).
     */
    private void loop() {
        try {
            runLoop();
        } catch (Throwable t) {
            long dropped;
            // 🔴 바구니도 닫고 남은 것까지 버린 수로 센다(PR #181 codex) — 나가 있던 묶음만 세면 MAX_BATCH를 넘게 쌓였던
            // 몫과 죽은 뒤 들어오는 몫이 바구니에 남아 등식이 틀리고 프로세스 끝까지 쥐고 있다. 닫힌 바구니는 더 안 받는다.
            buffer.close();
            synchronized (this) {
                dropped = abandoned ? 0 : inFlight;   // 확정이 이미 셌으면 또 세지 않는다
                inFlight -= dropped;
                if (!abandoned) {
                    List<RelayEvent> rest;
                    while (!(rest = buffer.drain(MAX_BATCH)).isEmpty()) {
                        dropped += rest.size();
                    }
                }
                relayDropped += dropped;
            }
            log.warn("chat.relay.thread_died causeType={} dropped={}", t.getClass().getSimpleName(), dropped);
            throw t;
        }
    }

    private void runLoop() {
        while (!abandoned) {
            // 🔴 닫힘 표시를 drain <b>앞에서</b> 읽는다. 뒤에서 읽으면 「빈 drain → 그 사이 offer → 닫힘 표시」
            // 순서에서 담긴 한 건을 남긴 채 끝난다. 표시는 바구니를 닫은 뒤에 켜지므로 앞에서 켜진 것을 봤다면
            // 이 drain 뒤로는 들어올 것이 없다.
            boolean closingNow = closing;
            List<RelayEvent> batch;
            synchronized (this) {
                // 확정과 drain이 겹치면 꺼낸 것이 어느 셈에도 안 들어간다 — 같은 자물쇠 안에서 꺼내고 센다.
                if (abandoned) {
                    return;
                }
                batch = buffer.drain(MAX_BATCH);
                inFlight += batch.size();
            }
            if (batch.isEmpty()) {
                if (closingNow) {
                    return;
                }
                LockSupport.parkNanos(idleNanos);
                continue;
            }
            relayOnce(batch);
        }
    }

    private void relayOnce(List<RelayEvent> batch) {
        Map<String, List<RelayEvent>> byStream = new LinkedHashMap<>();
        for (RelayEvent event : batch) {
            byStream.computeIfAbsent(event.streamId(), ignored -> new java.util.ArrayList<>()).add(event);
        }
        for (Map.Entry<String, List<RelayEvent>> entry : byStream.entrySet()) {
            // 🔴 방송마다 본다(L5). 루프 머리에서만 보면 한 묶음의 남은 방송에 기한 뒤에도 계속 보낸다 —
            // 방송 수 × 전용 시한(최대 1.5초)만큼 종료 뒤에 요청이 더 나간다. 남은 몫은 확정이 이미 셌다.
            if (abandoned) {
                return;
            }
            int size = entry.getValue().size();
            ClipRelayClient.Outcome outcome;
            try {
                outcome = client.send(entry.getKey(), entry.getValue());
            } catch (RuntimeException e) {
                // send는 던지지 않게 짰다. 그래도 여기서 받는다 — 이 스레드가 죽으면 바구니만 차고 아무도 모른다.
                log.warn("chat.relay.loop_failed stream={} causeType={} events={}",
                        entry.getKey(), e.getClass().getSimpleName(), size);
                outcome = ClipRelayClient.Outcome.FAILED;
            }
            synchronized (this) {
                if (abandoned) {
                    return;   // 확정이 이 묶음을 이미 버린 수로 셌다
                }
                inFlight -= size;
                if (outcome == ClipRelayClient.Outcome.SENT) {
                    long partial = Math.min(size, client.takePartialDropped());
                    relayed += size - partial;
                    relayDropped += partial;
                } else {
                    relayDropped += size;
                }
            }
        }
    }

    @Override
    public void beginClose() {
        buffer.close();
        closing = true;
        LockSupport.unpark(thread);
    }

    /**
     * 기한 안에 스레드가 끝나면 그대로 돌아온다. 넘기면 <b>나가 있는 묶음과 바구니 잔량을 버린 수로 확정하고</b>
     * 돌아온다 — 돌아온 뒤 셈은 안 바뀐다(판정 줄이 곧바로 읽는다). 기다리지 않는다: 나가 있는 요청은 전용 시한
     * (최대 약 1.5초) 안에 끝나고 스레드는 데몬이다. 두 번 불러도 같다(러너가 닫기를 두 번 지난다).
     */
    @Override
    public void awaitClosed(Duration budget) {
        if (thread.getState() == Thread.State.NEW) {
            return;
        }
        try {
            thread.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(Math.max(0, budget.toNanos()))));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!thread.isAlive()) {
            return;
        }
        long dropped;
        synchronized (this) {
            abandoned = true;
            dropped = inFlight;
            inFlight = 0;
            List<RelayEvent> rest;
            while (!(rest = buffer.drain(MAX_BATCH)).isEmpty()) {
                dropped += rest.size();
            }
            relayDropped += dropped;
        }
        LockSupport.unpark(thread);
        if (dropped > 0) {
            log.warn("chat.relay.close_abandoned dropped={}", dropped);
        }
    }

    /** 검사·health용. 시작됐고 아직 안 끝났는가. */
    boolean isRunning() {
        return thread.isAlive();
    }

    @Override
    public RelayCounters counters() {
        return this;
    }

    @Override
    public synchronized long relayed() {
        return relayed;
    }

    @Override
    public synchronized long relayDropped() {
        return relayDropped;
    }

    @Override
    public long relayOffered() {
        return buffer.offeredCount();
    }

    @Override
    public long bufferDropped() {
        return buffer.droppedCount();
    }
}
