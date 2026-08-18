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
    /**
     * 루프가 실제로 돌기 시작한 때. <b>빈이 만들어진 때가 아니다</b> — 컨텍스트 로딩과
     * 실제 시작 사이에 간격이 있고, 꺼져 있으면 루프가 아예 안 돈다. null이면
     * "아직 시작 안 함"이고, 그것과 "돌다가 멈춤"을 가르는 것이 이 칸의 존재 이유다.
     */
    private final AtomicReference<Instant> loopStartedAt = new AtomicReference<>();
    private final AtomicReference<Instant> lastPollSucceededAt = new AtomicReference<>();
    private final AtomicReference<String> lastFailureReason = new AtomicReference<>();

    public IntakeStatus(boolean enabled) {
        this.enabled = enabled;
    }

    void loopStarted(Instant at) {
        loopStartedAt.set(at);
    }

    void pollSucceeded(Instant at) {
        lastPollSucceededAt.set(at);
        lastFailureReason.set(null);
    }

    void pollFailed(String reason) {
        lastFailureReason.set(reason);
    }

    public Snapshot snapshot() {
        return new Snapshot(enabled, loopStartedAt.get(), lastPollSucceededAt.get(),
                lastFailureReason.get());
    }

    public record Snapshot(boolean enabled, Instant loopStartedAt, Instant lastPollSucceededAt,
                           String lastFailureReason) {
    }
}
