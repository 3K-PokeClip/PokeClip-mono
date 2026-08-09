package com.pokeclip.chat.collector;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 지금 수집이 어떤 상태인지 한 곳. 러너가 쓰고 health가 읽는다.
 *
 * <p>상태와 사유를 한 참조로 묶어 바꾼다. 둘을 따로 두면 "STOPPED인데 사유가
 * 아직 안 채워진" 찰나가 생기고, health가 하필 그때 읽으면 이유 없는 DOWN이 나간다.
 */
@Component
public class CollectionStatus {

    public enum State { DISABLED, ESTABLISHING, COLLECTING, RECONNECTING, STOPPED }

    /**
     * @param disconnectedAt RECONNECTING일 때만 채워진다. 언제부터 못 받고 있는지가
     *                       없으면 "방금 끊겼다"와 "10분째 못 붙는다"가 같아 보인다
     * @param attempt        몇 번째 재시도인지. 0이면 재연결 중이 아니다
     */
    private record Snapshot(State state, StopReason reason, Instant disconnectedAt, int attempt) { }

    private final AtomicReference<Snapshot> current =
            new AtomicReference<>(new Snapshot(State.DISABLED, null, null, 0));

    public State state() { return current.get().state(); }

    /** STOPPED·RECONNECTING이 아니면 null이다. */
    public StopReason reason() { return current.get().reason(); }

    /** RECONNECTING이 아니면 null이다. */
    public Instant disconnectedAt() { return current.get().disconnectedAt(); }

    /** RECONNECTING이 아니면 0이다. */
    public int attempt() { return current.get().attempt(); }

    public void establishing() { current.set(new Snapshot(State.ESTABLISHING, null, null, 0)); }
    public void disabled() { current.set(new Snapshot(State.DISABLED, null, null, 0)); }

    /**
     * <b>ESTABLISHING일 때만</b> COLLECTING으로 간다.
     *
     * <p>수립을 마치는 사이에 전송이 끊기면 WS 스레드가 먼저 STOPPED를 찍는다.
     * 조건 없이 set하면 부팅 스레드가 그 STOPPED를 덮어, 정리는 이미 끝났는데
     * health는 UP인 상태가 된다 — 수집이 죽었는데 아무 신호가 없는,
     * 이 서비스의 유일한 치명적 실패다.
     *
     * @return COLLECTING으로 갔으면 true. false면 그 사이에 끝난 것이다
     */
    public boolean collectingIfEstablishing() {
        Snapshot now = current.get();
        return now.state() == State.ESTABLISHING
                && current.compareAndSet(now, new Snapshot(State.COLLECTING, null, null, 0));
    }

    /**
     * 끊겼고 다시 붙는 중. <b>STOPPED는 안 덮는다</b> — 재시도 불가 사유로 이미
     * 멈춘 뒤라면 그 사유가 진짜 원인이고, 덮으면 어디에도 안 남는다.
     */
    public void reconnecting(StopReason lastReason, Instant disconnectedAt, int attempt) {
        Snapshot now = current.get();
        while (now.state() != State.STOPPED) {
            Snapshot next = new Snapshot(State.RECONNECTING, lastReason, disconnectedAt, attempt);
            if (current.compareAndSet(now, next)) {
                return;
            }
            now = current.get();
        }
    }

    /**
     * <b>첫 사유가 이긴다.</b> 절단으로 STOPPED가 된 뒤 부팅 스레드가 시한 만료로
     * 다시 부르는 길이 있는데, 덮어쓰면 진짜 원인(TRANSPORT_CLOSED)이 사라지고
     * 그 결과(ESTABLISH_TIMEOUT)만 남아 왜 끊겼는지가 어디에도 없다.
     */
    public void stopped(StopReason reason) {
        Snapshot now = current.get();
        while (now.state() != State.STOPPED) {
            if (current.compareAndSet(now, new Snapshot(State.STOPPED, reason, null, 0))) {
                return;
            }
            now = current.get();
        }
    }
}
