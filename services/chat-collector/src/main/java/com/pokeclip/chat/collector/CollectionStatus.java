package com.pokeclip.chat.collector;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 지금 수집이 어떤 상태인지 한 곳. 러너가 쓰고 health가 읽는다.
 *
 * <p>상태와 사유를 한 참조로 묶어 바꾼다. 둘을 따로 두면 "STOPPED인데 사유가
 * 아직 안 채워진" 찰나가 생기고, health가 하필 그때 읽으면 이유 없는 DOWN이 나간다.
 */
@Component
public class CollectionStatus {

    public enum State { DISABLED, ESTABLISHING, COLLECTING, STOPPED }

    private record Snapshot(State state, StopReason reason) { }

    private final AtomicReference<Snapshot> current =
            new AtomicReference<>(new Snapshot(State.DISABLED, null));

    public State state() { return current.get().state(); }

    /** STOPPED가 아니면 null이다. */
    public StopReason reason() { return current.get().reason(); }

    public void establishing() { current.set(new Snapshot(State.ESTABLISHING, null)); }
    public void collecting() { current.set(new Snapshot(State.COLLECTING, null)); }
    public void disabled() { current.set(new Snapshot(State.DISABLED, null)); }
    public void stopped(StopReason reason) { current.set(new Snapshot(State.STOPPED, reason)); }
}
