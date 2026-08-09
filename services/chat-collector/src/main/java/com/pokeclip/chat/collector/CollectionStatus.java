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
    public record Snapshot(State state, StopReason reason, Instant disconnectedAt, int attempt) { }

    private final AtomicReference<Snapshot> current =
            new AtomicReference<>(new Snapshot(State.DISABLED, null, null, 0));

    /**
     * 지금 이 순간의 상태 전부. <b>여러 항을 함께 보는 쪽은 이것을 쓴다.</b>
     *
     * <p>아래 낱개 getter를 이어 부르면 <b>서로 다른 순간의 값이 섞인다</b> —
     * 상태만 보고 갈래를 고른 뒤 사유를 읽는 사이에 재접속이 성공하면, 그 사유는
     * 이미 비어 있다. 갈래는 "재연결 중"인데 상세는 "사유 없음"인 응답이 나간다.
     */
    public Snapshot snapshot() { return current.get(); }

    public State state() { return current.get().state(); }

    /** STOPPED·RECONNECTING이 아니면 null이다. */
    public StopReason reason() { return current.get().reason(); }

    /** RECONNECTING이 아니면 null이다. */
    public Instant disconnectedAt() { return current.get().disconnectedAt(); }

    /** RECONNECTING이 아니면 0이다. */
    public int attempt() { return current.get().attempt(); }

    /**
     * 수립 중. <b>RECONNECTING과 STOPPED는 안 덮는다.</b>
     *
     * <p>재연결 루프는 시도마다 {@code start()}를 부르고 그 안에서 이 메서드가 불린다.
     * 조건 없이 set하면 <b>재시도할 때마다 health가 UP으로 돌아간다</b> — 수립이
     * establishTimeout(운영 15초)을 다 쓰는 동안 "채팅은 안 오는데 health는 UP"이 되고,
     * 그게 이 서비스가 유일한 치명 실패로 규정한 모양이다. STOPPED 쪽은 영구 정지라
     * 되돌리면 왜 멈췄는지가 어디에도 안 남는다.
     *
     * <p>그래서 첫 부팅(DISABLED에서 온다)만 실제로 ESTABLISHING을 찍고, 재연결은
     * RECONNECTING인 채로 수립한다. <b>둘 다 "아직 안 붙었다"이므로 health는 같다</b> —
     * 다른 것은 재연결 쪽이 언제부터 못 받고 있는지와 몇 번째인지를 들고 있다는 점뿐이다.
     */
    public void establishing() {
        Snapshot now = current.get();
        while (now.state() != State.STOPPED && now.state() != State.RECONNECTING) {
            if (current.compareAndSet(now, new Snapshot(State.ESTABLISHING, null, null, 0))) {
                return;
            }
            now = current.get();
        }
    }

    public void disabled() { current.set(new Snapshot(State.DISABLED, null, null, 0)); }

    /**
     * <b>수립을 기다리는 중일 때만</b> COLLECTING으로 간다. 첫 수립은 ESTABLISHING에서,
     * 재연결은 RECONNECTING에서 들어온다.
     *
     * <p>조건 없이 set하면 수립을 마치는 사이에 끊겨 이미 찍힌 STOPPED를 부팅 스레드가
     * 덮어, 정리는 이미 끝났는데 health는 UP인 상태가 된다 — 수집이 죽었는데 아무
     * 신호가 없는, 이 서비스의 유일한 치명적 실패다.
     *
     * @return COLLECTING으로 갔으면 true. false면 그 사이에 끝난 것이다
     */
    public boolean collectingIfPending() {
        Snapshot now = current.get();
        return (now.state() == State.ESTABLISHING || now.state() == State.RECONNECTING)
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
     * 영구 정지. <b>이미 STOPPED면 첫 사유가 이긴다</b> — 한 번의 정지에 신호가 둘
     * 들어오는 길이 있고, 덮어쓰면 진짜 원인이 그 결과로 바뀐다.
     *
     * <p><b>RECONNECTING은 덮는다.</b> 영구 정지가 "재시도 중"을 이기는 것이 맞다.
     * 반대로 하면 401로 멈췄는데 사유가 {@code TRANSPORT_CLOSED}로 남아
     * <b>재시도 불가 판정의 근거를 잃는다.</b>
     *
     * <p>그래서 이 필드의 뜻은 <b>"왜 영영 멈췄나"</b>이고, "이번에 왜 끊겼나"는
     * 로그가 든다({@code chat.session.closed reason=}).
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
