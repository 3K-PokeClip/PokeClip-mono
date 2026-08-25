package com.pokeclip.chat.detector.metrics;

/**
 * 전달 지연이 얼마나 늦었나. 유예값을 어느 쪽으로 얼마나 옮길지의 근거다.
 *
 * @param beyondGrace          유예보다 늦게 온 것 — <b>놓칠 수 있었던 상한</b>
 *                             (창 끝에 찍힌 채팅만 실제로 놓친다)
 * @param beyondWindowAndGrace 창 + 유예보다 늦게 온 것 — <b>반드시 놓친 하한</b>
 * @param maxDelayMs           가장 늦은 것. 유예를 <b>얼마로</b> 올릴지는 이 값이 정한다
 */
public record LateArrivalCount(long total, long beyondGrace, long beyondWindowAndGrace, long maxDelayMs) {

    /**
     * 🔴 {@code maxDelayMs}가 0이 아니라 {@link Long#MIN_VALUE}다. 0으로 두면 지연이 전부
     * 음수인 방송에서 {@code Math.max}가 실제값 대신 <b>0</b>을 남긴다 — 유예값을 정하는
     * 근거 숫자가 조용히 틀린다(계획 검증 F12). 부호가 뒤집히는 것은 실재한다:
     * 수집 서버가 시계 오프셋 혼입으로 −39~−70ms를 실측했다.
     */
    public static final LateArrivalCount EMPTY =
            new LateArrivalCount(0, 0, 0, Long.MIN_VALUE);

    /** 방송 여럿을 합친다. <b>최댓값은 더하지 않는다</b> — 가장 늦은 하나가 답이다. */
    public LateArrivalCount plus(LateArrivalCount other) {
        return new LateArrivalCount(total + other.total,
                beyondGrace + other.beyondGrace,
                beyondWindowAndGrace + other.beyondWindowAndGrace,
                Math.max(maxDelayMs, other.maxDelayMs));
    }

    /** 로그에 실을 값. 잰 것이 하나도 없으면 최댓값이 없다 — 0을 적으면 「지연 0」이라는 거짓이다. */
    public String maxDelayForLog() {
        return maxDelayMs == Long.MIN_VALUE ? "none" : String.valueOf(maxDelayMs);
    }
}
