package com.pokeclip.clip.broadcast.intake;

/**
 * 종료 편지가 명부에 반영된 뒤 러너가 부른다. 트랜잭션 밖이라 "커밋 뒤"가 공짜다.
 * {@code BroadcastEventProcessor}는 바뀌지 않는다.
 *
 * <p><b>public 최상위 타입이어야 한다.</b> 러너 안의 중첩 타입으로 두면
 * {@code CardStreamRegistry}가 못 쓴다 — 러너가 package-private이라 바깥 패키지에서
 * {@code SqsIntakeRunner.EndedListener}를 참조할 수 없다(plan-critic 컴파일 확인).
 *
 * <p>러너가 {@code jumpcard} 패키지를 모르게 하려고 규약만 이쪽에 둔다.
 * <b>러너 자체는 package-private 그대로다.</b>
 */
@FunctionalInterface
public interface EndedListener {

    void broadcastEnded(String streamId);
}
