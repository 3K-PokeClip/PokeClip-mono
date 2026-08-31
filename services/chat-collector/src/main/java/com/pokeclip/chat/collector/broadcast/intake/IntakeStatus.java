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
    /**
     * 🔴 <b>삭제 실패는 따로 센다</b>(감사 G2). 폴링 실패와 같은 칸에 담으면
     * {@link #pollSucceeded}가 그것을 지운다 — 그런데 <b>삭제만 실패하는 상태에서도
     * 수신은 계속 성공한다.</b> 롱폴링이 20초라 20초마다 한 번 깜빡이고, 그 사이
     * {@code lastPollSucceededAt}이 계속 갱신되어 {@code stalled} 판정도 안 걸린다.
     * 계획 검증 I7이 겨눈 문장이 정확히 「삭제만 실패하고 수신은 되는 동안 계속 초록」이다.
     *
     * <p>{@code DeleteMessage} 권한만 빠진 IAM 정책이 그 상태를 만든다 — 모든 알림이
     * 가시성 시한마다 무한 재처리되는데 health는 대체로 {@code ok}였다.
     *
     * <p><b>회복은 삭제 성공이 지운다</b>({@link #deleteSucceeded}). 폴링 성공이 아니다 —
     * 그것이 이 결함의 뿌리였다.
     */
    private final AtomicReference<String> lastDeleteFailureReason = new AtomicReference<>();

    public IntakeStatus(boolean enabled) {
        this.enabled = enabled;
    }

    public void pollSucceeded(Instant at) {
        lastPollSucceededAt.set(at);
        // 회복을 지우지 않으면 health가 영영 DOWN이라 다시 붙은 것을 아무도 모른다.
        lastFailureReason.set(null);
    }

    void pollFailed(String reason) {
        lastFailureReason.set(reason);
    }

    /** 알림을 지우지 못했다. <b>줄 스레드에서 불린다</b> — 폴링 스레드가 아니다. */
    void deleteFailed(String reason) {
        lastDeleteFailureReason.set(reason);
    }

    /** 삭제가 다시 된다. 이 신호만 위 표시를 지운다. */
    void deleteSucceeded() {
        lastDeleteFailureReason.set(null);
    }

    public Snapshot snapshot() {
        return new Snapshot(enabled, lastPollSucceededAt.get(), lastFailureReason.get(),
                lastDeleteFailureReason.get());
    }

    /**
     * @param lastFailureReason 예외 <b>타입 이름만</b> 담는다. 메시지에는 큐 주소·계정 번호가
     *                          실릴 수 있고 이 값은 health 응답으로 밖에 나간다
     * @param lastDeleteFailureReason 같은 규칙. <b>폴링 실패와 갈라 둔다</b> — 「큐에 못 닿는다」와
     *                          「받기는 되는데 못 지운다」는 운영에서 고칠 자리가 다르다(권한 대 연결)
     */
    public record Snapshot(boolean enabled, Instant lastPollSucceededAt, String lastFailureReason,
                           String lastDeleteFailureReason) {

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
            return !enabled || (lastFailureReason == null && lastDeleteFailureReason == null);
        }
    }
}
