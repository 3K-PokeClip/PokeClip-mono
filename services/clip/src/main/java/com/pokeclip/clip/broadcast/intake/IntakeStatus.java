package com.pokeclip.clip.broadcast.intake;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 폴링이 도는지를 health가 읽는 자리.
 *
 * <p><b>스냅샷을 한 번에 만든다.</b> 낱개 getter를 이어 부르면 갈래를 고른 뒤 값이
 * 바뀌어 앞뒤가 안 맞는 응답이 나간다(chat-collector CollectorHealth가 겪은 함정).
 */
public class IntakeStatus {

    private final boolean enabled;
    private final AtomicReference<Instant> lastPollSucceededAt = new AtomicReference<>();
    private final AtomicReference<String> lastFailureReason = new AtomicReference<>();

    public IntakeStatus(boolean enabled) {
        this.enabled = enabled;
    }

    void pollSucceeded(Instant at) {
        lastPollSucceededAt.set(at);
        lastFailureReason.set(null);
    }

    void pollFailed(String reason) {
        lastFailureReason.set(reason);
    }

    public Snapshot snapshot() {
        return new Snapshot(enabled, lastPollSucceededAt.get(), lastFailureReason.get());
    }

    public record Snapshot(boolean enabled, Instant lastPollSucceededAt, String lastFailureReason) {
    }
}
