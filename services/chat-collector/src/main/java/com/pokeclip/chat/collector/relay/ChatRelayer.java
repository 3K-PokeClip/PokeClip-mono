package com.pokeclip.chat.collector.relay;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
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

    private final AtomicLong relayed = new AtomicLong();
    private final AtomicLong relayDropped = new AtomicLong();

    /** {@link #beginClose()}가 켠다. 켜기 <b>전에</b> 바구니를 닫으므로, 켜진 것을 본 뒤의 빈 drain은 진짜 끝이다. */
    private volatile boolean closing;
    /** 닫기 기한을 넘겼다. 지금 보내는 묶음이 끝나면 남은 것을 버린 수로 세고 끝낸다. */
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

    private void loop() {
        while (true) {
            if (abandoned) {
                dropRemaining();
                return;
            }
            // 🔴 닫힘 표시를 drain <b>앞에서</b> 읽는다. 뒤에서 읽으면 「빈 drain → 그 사이 offer → 닫힘 표시」
            // 순서에서 담긴 한 건을 남긴 채 끝난다. 표시는 바구니를 닫은 뒤에 켜지므로 앞에서 켜진 것을 봤다면
            // 이 drain 뒤로는 들어올 것이 없다.
            boolean closingNow = closing;
            List<RelayEvent> batch = buffer.drain(MAX_BATCH);
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
            if (outcome == ClipRelayClient.Outcome.SENT) {
                relayed.addAndGet(size);
            } else {
                relayDropped.addAndGet(size);
            }
        }
    }

    private void dropRemaining() {
        long dropped = 0;
        List<RelayEvent> rest;
        while (!(rest = buffer.drain(MAX_BATCH)).isEmpty()) {
            dropped += rest.size();
        }
        relayDropped.addAndGet(dropped);
        log.warn("chat.relay.close_abandoned dropped={}", dropped);
    }

    @Override
    public void beginClose() {
        buffer.close();
        closing = true;
        LockSupport.unpark(thread);
    }

    @Override
    public void awaitClosed(Duration budget) {
        if (thread.getState() == Thread.State.NEW) {
            return;
        }
        try {
            thread.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(budget.toNanos())));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (thread.isAlive()) {
            abandoned = true;
            // 기다리지 않는다 — 지금 나가 있는 요청은 전용 시한(최대 약 1.5초) 안에 끝나고 스레드는 데몬이다.
        }
    }

    /** 검사·health용. 시작됐고 아직 안 끝났는가. */
    boolean isRunning() {
        return thread.isAlive();
    }

    @Override
    public long relayed() {
        return relayed.get();
    }

    @Override
    public long relayDropped() {
        return relayDropped.get();
    }

    @Override
    public long bufferDropped() {
        return buffer.droppedCount();
    }
}
