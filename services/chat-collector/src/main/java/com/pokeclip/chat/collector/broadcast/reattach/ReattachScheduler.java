package com.pokeclip.chat.collector.broadcast.reattach;

import org.springframework.scheduling.annotation.Scheduled;

/**
 * 재부착을 주기적으로 부르고, <b>그 회차가 됐는지와 못 읽은 스트리머 수를 health가 읽을
 * 자리에 남긴다.</b>
 *
 * <p><b>{@link Reattacher}와 갈라 둔 이유</b>: 재부착기는 「무엇을 줍고 무엇을 거르나」를 알고,
 * 이쪽은 「얼마나 자주 부르고 그 결과를 어디에 알리나」를 안다. 둘은 서로 다른 이유로 바뀐다 —
 * 실제로 이 카드에서 재부착기는 여덟 태스크에 걸쳐 만들어졌고 주기·health는 여기 하나뿐이다.
 * ({@code SqsIntakeRunner}와 {@code SqsIntakeLoop}를 가른 것과 같은 결이다.)
 *
 * <p><b>{@code @Component}가 아니다.</b> 재부착이 꺼진 프로세스에는 이 빈이 아예 없어야
 * 하므로 {@link ReattachConfiguration}이 조건부로 만든다.
 *
 * <h2>종료와의 관계 — <b>재부착은 종료 예산에 아무것도 더하지 않는다</b></h2>
 *
 * <p>스프링은 라이프사이클 정지를 <b>전부</b> 끝낸 뒤 빈을 파괴하고,
 * {@code SessionRegistry.shutdown()}(빗장 + {@code closeAll})은 그 <b>파괴</b> 단계에서
 * {@code CollectorRunner}가 부른다. 즉 이 스케줄러가 멈추는 시점이 언제든
 * <b>세션을 다 닫은 뒤에 새 세션이 서는 순서는 없다</b> — 늦게 도착한 붙이기는
 * {@code registry.open}의 빗장에 걸린다.
 *
 * <p>그리고 <b>재부착은 알림을 하나도 안 지운다.</b> 종료와 겹쳐 붙이기가 실패해도 잃는 것이
 * 없고, 다음 프로세스의 첫 회차가 같은 목록을 다시 받는다. {@code SqsIntakeLoop.stop()}의
 * {@code DRAIN_WAIT}에 재부착 작업이 같이 걸리지만 그 값은 <b>상한</b>이라 예산이 안 늘어난다.
 *
 * <h2>{@code EndedStreamSweeper}와 <b>스레드 하나를 나눠 쓴다</b></h2>
 *
 * <p>Boot의 {@code spring.task.scheduling.pool.size} 기본값이 <b>1</b>이다
 * (4.1.0 설정 메타데이터로 확인). 이 카드 전에는 {@code @Scheduled}가 하나뿐이라 드러날
 * 자리가 없었다. 지금은 한쪽이 도는 동안 다른 쪽이 밀린다 — <b>둘 다 상한이 있어서
 * 무해하다.</b>
 *
 * <p>이 회차의 상한은 <b>약 47초</b>다: clip REST(접속 2 + 읽기 5) + 메모 조회 하나.
 * 🔴 <b>그 조회에 커넥션 대기가 붙는다</b> — {@code spring.datasource.hikari.connection-timeout}이
 * 설정에 없어 기본 <b>30초</b>이고, 붙이기 작업 50개가 같은 풀 10개를 나눠 쓴다(감사 라운드 3 H5,
 * 태스크 8이 이 항을 안 셌다). 거기에 조회 자체의 {@code socketTimeout} 10초가 더해진다.
 * 스위퍼는 같은 이유로 <b>약 40초</b>다.
 *
 * <p><b>결론은 그대로다</b>: 주기가 1분·1시간이라 최악에도 한쪽이 다음 주기를 한 번 밀 뿐이고,
 * 재부착은 상태가 없어서 밀린 회차를 잃어도 다음 회차가 같은 목록을 다시 받는다.
 * <b>세 번째 {@code @Scheduled}를 더하는 날 이 값을 다시 본다.</b>
 * (붙이기 자체는 여기서 안 돈다 — 줄 실행기의 가상 스레드가 받는다.)
 */
public class ReattachScheduler {

    private final Reattacher reattacher;
    private final ReattachStatus status;

    public ReattachScheduler(Reattacher reattacher, ReattachStatus status) {
        this.reattacher = reattacher;
        this.status = status;
    }

    /**
     * <b>{@code initialDelayString}을 주기와 다른 열쇠로 준다.</b> 같은 값을 두 자리에 쓰면
     * ({@code EndedStreamSweeper}가 그 모양이라 베끼기 쉽다) 부팅 직후 <b>한 주기를 그냥
     * 잃는다</b> — 배포로 채팅이 안 걷히는 시간이 정확히 그만큼이다. 반대로 0으로 두면
     * 컨텍스트가 다 뜨기 전에 clip을 두드린다.
     *
     * <p>{@code sweep()}은 던지지 않는다 — {@code @Scheduled}는 태스크가 한 번이라도 던지면
     * 그 뒤 주기가 안 돈다. 그 대신 성패를 돌려주므로 여기서 health에 남긴다.
     */
    @Scheduled(fixedDelayString = "${pokeclip.reattach.interval}",
            initialDelayString = "${pokeclip.reattach.initial-delay}")
    public void tick() {
        boolean swept = reattacher.sweep();
        // <b>성패보다 먼저, 성패와 무관하게 옮긴다.</b> 회차가 통째로 실패해도 그 전에
        // 못 읽은 것을 셌을 수 있다 — 그때 안 옮기면 「clip 명부가 이상하다」는 신호가
        // 「clip에 못 닿는다」에 가려진다.
        status.unreadableStreamerIds(reattacher.unreadableStreamerIds());
        if (swept) {
            status.sweepSucceeded();
        } else {
            status.sweepFailed();
        }
    }
}
