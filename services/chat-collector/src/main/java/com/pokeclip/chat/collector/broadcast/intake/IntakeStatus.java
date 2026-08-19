package com.pokeclip.chat.collector.broadcast.intake;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 편지를 꺼내고 있는지를 health가 읽는 자리(태스크 13).
 *
 * <p><b>스냅샷을 한 번에 만든다.</b> 낱개 getter를 이어 부르면 갈래를 고른 뒤 값이 바뀌어
 * 앞뒤가 안 맞는 응답이 나간다 — {@code CollectorHealth}가 겪은 함정이다.
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
        // 회복을 지우지 않으면 health가 영영 DOWN이라 다시 붙은 것을 아무도 모른다.
        lastFailureReason.set(null);
    }

    void pollFailed(String reason) {
        lastFailureReason.set(reason);
    }

    public Snapshot snapshot() {
        return new Snapshot(enabled, lastPollSucceededAt.get(), lastFailureReason.get());
    }

    /**
     * @param lastFailureReason 예외 <b>타입 이름만</b> 담는다. 메시지에는 큐 주소·계정 번호가
     *                          실릴 수 있고 이 값은 health 응답으로 밖에 나간다
     */
    public record Snapshot(boolean enabled, Instant lastPollSucceededAt, String lastFailureReason) {

        /**
         * <b>꺼져 있으면 건강하다.</b> 기본이 꺼짐이라(CI·팀원 로컬) 여기서 DOWN을 주면
         * 아무도 안 쓰는 서버가 늘 빨간불이다.
         *
         * <p><b>「켜졌는데 아직 한 번도 못 돌았다」도 건강으로 친다.</b> 부팅 직후 창이 있어
         * 여기서 DOWN을 주면 뜰 때마다 잠깐 빨간불이 뜬다. 그 창을 가르려면 마지막 성공
         * 시각이 얼마나 낡았는지를 봐야 하는데, 그 판단은 <b>임계를 정하는 쪽(health, 태스크
         * 13)의 것</b>이라 여기서 미리 정하지 않는다 — 스냅샷에 시각을 그대로 내주는 이유다.
         */
        public boolean healthy() {
            return !enabled || lastFailureReason == null;
        }
    }
}
