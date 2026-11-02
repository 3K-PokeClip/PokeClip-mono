package com.pokeclip.chat.collector.broadcast.reattach;

import java.util.concurrent.atomic.AtomicLong;
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
 * <p><b>낱개 getter를 이어 부르는 위험이 없다.</b> {@code IntakeStatus}는 시각과 사유를
 * 함께 읽어야 뜻이 서서 스냅샷으로 묶는데, 여기 둘은 <b>서로 독립이다</b> — 상태와
 * 못 읽은 수 사이에 「같은 순간의 것이어야 한다」는 관계가 없다(하나는 마지막 회차의
 * 성패, 하나는 프로세스 누계). 그래서 스냅샷 타입을 안 만든다.
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

    /**
     * 재부착이 <b>숫자로 못 읽은 스트리머 식별자</b>의 프로세스 누계.
     * {@link Reattacher}가 세고 {@code ReattachScheduler}가 회차마다 여기로 옮긴다.
     *
     * <p><b>왜 밖으로 내보내나</b>: 1번이 식별자 체계를 바꾸면 <b>모든 방송이 그 길</b>인데,
     * 로그만으로는 「체계가 바뀌었다」와 「한 건 이상했다」가 구분되지 않는다. 알림 경로의
     * 같은 이름 카운터는 이미 health에 실려 있었다 — <b>쌍둥이 중 한쪽만이던 것을 메웠다</b>
     * (감사 라운드 3 H2).
     *
     * <p><b>{@code Reattacher}가 여기 직접 안 쓰는 이유</b>는 그 부품이 「무엇을 줍나」만
     * 알아야 해서다(검사 열다섯이 그 생성자를 직접 부른다). 회차의 성패를 옮기는 것과
     * 같은 손이 같은 자리에서 옮긴다.
     */
    private final AtomicLong unreadableStreamerIds = new AtomicLong();

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

    /** <b>더하지 않고 덮어쓴다.</b> {@link Reattacher}의 누계가 원본이고 여기는 그 사본이다. */
    public void unreadableStreamerIds(long count) {
        unreadableStreamerIds.set(count);
    }

    public long unreadableStreamerIds() {
        return unreadableStreamerIds.get();
    }
}
