package com.pokeclip.chat.collector.broadcast.reattach;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 재부착이 돌고 있는지를 health가 읽는 자리.
 *
 * <p><b>왜 필요한가</b>: 이 카드가 사각을 하나 새로 만들었다 — {@link Reattacher#sweep()}이
 * 어떤 실패든 삼키고 경고만 남기므로(그래야 {@code @Scheduled}가 안 멈춘다),
 * <b>clip에 몇 시간을 못 닿아도 health는 초록</b>이다. 그동안 「재배포로 잃은 방송을 줍는」
 * 복구 장치가 통째로 죽어 있는데 밖에서는 아무 차이가 없다.
 *
 * <p>🔴 <b>PRD 비목표의 「처리가 막힌 것을 상태로 드러내기」와 다른 물음이다.</b>
 * 그쪽은 <b>알림 처리</b>가 막힌 것이고 이쪽은 <b>clip에 못 닿는 것</b>이다.
 *
 * <p><b>{@code IntakeStatus}와 같은 모양이다</b> — 「꺼짐」·「아직 한 번도 안 돌았다」·「도는
 * 중」·「못 닿는다」 넷을 가른다. 가운데 둘을 뭉치면 <b>부팅 직후의 창</b>과 <b>정상</b>이 같아
 * 보이고, 처음부터 clip을 못 잡고 있는 프로세스가 「아직 안 돌았을 뿐」으로 읽힌다.
 *
 * <p><b>한 칸에 담는다.</b> {@code IntakeStatus}는 시각과 사유를 낱개로 들고 스냅샷으로 묶는데,
 * 여기는 담을 것이 상태 하나뿐이라 <b>{@code AtomicReference} 하나가 곧 스냅샷</b>이다 —
 * 낱개 getter를 이어 부르다 값이 바뀌는 자리 자체가 없다.
 *
 * <p><b>실패 사유(예외 타입)는 여기 안 담는다.</b> 그것은 {@code chat.reattach.failed} 로그가
 * {@code causeType=}으로 이미 남긴다. health가 대신 지는 일은 「지금 어느 상태인가」 하나다.
 */
public class ReattachStatus {

    /** health 상세에 그대로 실리는 값이다. {@code letterIntake}의 낱말과 같은 결로 골랐다. */
    public enum State {
        DISABLED("disabled"),
        /** 켜졌는데 첫 회차가 아직 안 돌았다. 첫 지연만큼은 정상적으로 이 상태다. */
        STARTING("starting"),
        OK("ok"),
        /** 마지막 회차가 통째로 실패했다. 대개 clip에 못 닿는 것이다. */
        FAILING("failing");

        private final String label;

        State(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private final AtomicReference<State> state;

    public ReattachStatus(boolean enabled) {
        this.state = new AtomicReference<>(enabled ? State.STARTING : State.DISABLED);
    }

    /** 회복이 실패 표시를 지운다 — 안 지우면 한 번 못 닿은 뒤로 영영 아프다고 말한다. */
    public void sweepSucceeded() {
        state.set(State.OK);
    }

    public void sweepFailed() {
        state.set(State.FAILING);
    }

    public State state() {
        return state.get();
    }
}
