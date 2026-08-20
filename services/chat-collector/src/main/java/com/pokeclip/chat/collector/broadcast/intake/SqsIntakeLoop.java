package com.pokeclip.chat.collector.broadcast.intake;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.time.Duration;

/**
 * {@link SqsIntakeRunner#runLoop()}를 데몬 스레드에 얹고, 종료 때 세운다.
 *
 * <p><b>러너와 갈라 둔 이유</b>: 러너는 큐 프로토콜(무엇을 지우고 무엇을 남기나)을 알고,
 * 이쪽은 종료 예산(언제까지 기다리나)을 안다. 둘은 서로 다른 이유로 바뀐다 — 실제로
 * 종료 예산은 다음 태스크에서 다시 정해진다.
 *
 * <p><b>{@code SmartLifecycle}이다. {@code @PreDestroy}가 아니다.</b> 라이프사이클 정지는
 * <b>빈 파괴보다 먼저</b> 통째로 끝나므로, 편지를 꺼내는 일이 다른 빈이 닫히기 전에 멎는다.
 * 파괴에 맡기면 순서가 의존 그래프에 달리는데, 러너는 {@code SqsClient}를
 * {@code ObjectProvider}로 받아 <b>그 그래프에 간선이 없다</b> — 큐 클라이언트가 먼저 닫힌
 * 뒤에도 루프가 도는 순서가 실재한다. 기본 phase({@code Integer.MAX_VALUE})라 다른
 * 라이프사이클보다 <b>먼저</b> 선다: 새 편지를 그만 받는 것이 종료의 첫 걸음이다.
 */
public class SqsIntakeLoop implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(SqsIntakeLoop.class);

    /**
     * 종료가 도는 회차를 기다리는 시한. {@code CollectorRunner}의 재연결 스레드 대기와 같은 값이다.
     *
     * <p><b>이 시한이 마지막 회차를 끊지는 못한다.</b> 롱폴링(최대 20초)에 들어간
     * {@code receiveMessage}는 인터럽트로도 안 끊기고, 그 호출이 돌아오면 <b>이미 받은 편지를
     * 마저 처리한다</b> — 즉 정지 신호 뒤에도 세션 하나가 더 열릴 수 있다. 지금 그것이 새는
     * 곳은 없다(등록부의 종료는 아직 없다). <b>세션 여럿을 유예 안에 닫는 태스크 11이 이 창과
     * 예산을 같이 정한다.</b> 스레드는 데몬이라 어느 경우에도 JVM 종료를 붙들지 않는다.
     */
    static final Duration JOIN_WAIT = Duration.ofSeconds(2);

    private final SqsIntakeRunner runner;

    private volatile Thread thread;

    public SqsIntakeLoop(SqsIntakeRunner runner) {
        this.runner = runner;
    }

    @Override
    public void start() {
        Thread started = new Thread(runner::runLoop, "chzzk-intake");
        // 데몬이다. 마지막 회차가 롱폴링에 잠겨 있어도 프로세스 종료를 막지 않는다.
        started.setDaemon(true);
        thread = started;
        started.start();
        log.info("broadcast.intake.loop_started");
    }

    @Override
    public void stop() {
        Thread running = thread;
        if (running == null) {
            return;
        }
        runner.stop();
        try {
            running.join(JOIN_WAIT.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (running.isAlive()) {
            // 조용히 넘어가면 "종료가 끝났다"와 "아직 편지를 처리 중이다"가 구분되지 않는다.
            log.warn("broadcast.intake.loop_still_running waitMs={}", JOIN_WAIT.toMillis());
        }
        thread = null;
    }

    @Override
    public boolean isRunning() {
        Thread running = thread;
        return running != null && running.isAlive();
    }
}
