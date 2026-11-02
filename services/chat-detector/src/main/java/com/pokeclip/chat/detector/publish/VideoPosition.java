package com.pokeclip.chat.detector.publish;

/**
 * 변환 창구의 답. <b>{@code positionMs}는 {@code CONVERTED}일 때만 값이 있다.</b>
 *
 * @param appliedOffsetMs 창구가 적용한 보정값. 판정과 무관하게 늘 실려 온다.
 *                        이것이 「장면 발생 → 채팅 도착」 구간이라 총 지연을 재는 데 쓴다
 */
public record VideoPosition(State state, Long positionMs, Long appliedOffsetMs) {

    /**
     * 판정을 가르는 축은 <b>「다시 물으면 답이 바뀌나」</b>다.
     * 뭉치면 부르는 쪽이 영영 안 올 것을 영원히 다시 묻는다.
     */
    public enum State {
        /** 위치를 찾았다 */
        CONVERTED,
        /** 조각이 아직 장부에 안 왔다 — <b>다시 물으면 된다</b> */
        NOT_YET_INDEXED,
        /** 첫 조각 이전이거나 진짜 공백 — <b>영영 없다</b>. 방송 시작 직후 보정값만큼이 여기다 */
        NO_FOOTAGE,
        /** 창구가 죽었거나 답을 못 읽었다. 「없다」와 다르다 */
        UNAVAILABLE
    }
}
