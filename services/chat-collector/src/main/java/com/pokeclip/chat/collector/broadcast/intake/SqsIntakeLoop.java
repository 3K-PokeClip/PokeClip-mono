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
 *
 * <p>🔴 <b>줄을 닫는 실행기가 폴링보다 먼저 닫히는 순서는 없다</b>(POK-219에서 확인).
 * {@code StreamerSerialExecutor}를 닫는 자리는 <b>하나뿐</b>이다 —
 * {@code CollectorApplication.streamerSerialExecutor()}가 만든 {@code AutoCloseable} 빈을
 * 스프링이 파괴할 때다(코드에 {@code close()}를 부르는 다른 자리가 없다).
 * 그리고 스프링은 {@code AbstractApplicationContext.doClose()}에서
 * <b>라이프사이클 정지를 통째로 끝낸 뒤에</b> {@code destroyBeans()}로 넘어간다.
 * 즉 이 {@link #stop()}이 반환한 뒤에야 실행기가 닫힌다.
 * {@code AsyncIntakeShutdownOrderTest}가 <b>실물 컨텍스트로</b> 그 순서를 잰다 —
 * 「찾아봤는데 못 찾았다」가 아니라 「그런 순서가 존재하지 않는다」다.
 *
 * <p><b>다만 {@link #JOIN_WAIT}를 넘겨 마지막 회차가 아직 살아 있을 수는 있다.</b>
 * 그때 실행기가 닫히면 그 회차의 {@code submit}이 false를 받는데, 그것은 「줄이 찼다」와
 * 같은 갈래로 처리돼 <b>알림을 지우지 않고</b> 끝난다(태스크 1의 {@code releaseAfterReject}).
 * 조용히 사라지는 알림은 없다.
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

    /**
     * 이미 받아 둔 알림의 <b>붙이기가 끝나기를</b> 기다리는 예산.
     * <b>{@link #JOIN_WAIT}와 갈라 둔다</b> — 앞은 폴링 스레드가 롱폴링에서 돌아오기를
     * 기다리는 시간이고, 이쪽은 줄이 비기를 기다리는 시간이다.
     *
     * <p>🔴 <b>2초인 이유는 「기다려서 끝내려고」가 아니다</b>(계획 검증 M3).
     * {@code services/README.md}의 편지 경로 산수가 <b>join 2 + 세션 닫기 8 + flush 5 =
     * 15초</b>이고 유예는 20초다. 여기에 6초를 끼우면 21초라 <b>세션 닫기가 잘려 구독이
     * 반납 안 되고 계정 자리가 남는다</b> — 유예를 15초에서 20초로 올린 바로 그 이유를
     * 무효로 만든다.
     *
     * <p><b>그리고 오래 기다릴 이유가 없다.</b> 붙이기 하나의 최악은 auth 5초 +
     * 치지직 {@code establish-timeout} 15초라 6초로도 어차피 못 덮는다. 못 끝낸 붙이기가
     * 위험하지 않은 이유는 따로 있다 — {@code SessionRegistry.shutdown()}이 빗장
     * ({@code closing})을 먼저 걸고 {@code open()}이 자리를 잡은 뒤 그 빗장을 다시 보므로
     * <b>그 뒤로는 세션이 서지 않는다.</b> 즉 이 대기는 「이미 열린 것을 정리할 시간」이지
     * 「열리는 것을 막는 장치」가 아니다.
     */
    static final Duration DRAIN_WAIT = Duration.ofSeconds(2);

    private final SqsIntakeRunner runner;

    private volatile Thread thread;

    public SqsIntakeLoop(SqsIntakeRunner runner) {
        this.runner = runner;
    }

    @Override
    public void start() {
        // 부팅에 한 번. 큐의 가시성 시한이 붙기보다 짧으면 같은 알림이 붙는 도중 다시 온다.
        runner.reportQueueVisibility();
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
        // <b>줄을 비우는 것은 폴링을 세운 다음이다.</b> 순서를 뒤집으면 비운 뒤에 마지막
        // 회차가 새 알림을 줄에 넣고, 그것이 스프링의 빈 파괴와 겹쳐 세션·DB가 밑에서
        // 닫힌 채로 붙이기를 시도한다.
        if (!runner.awaitIdle(DRAIN_WAIT)) {
            // 못 끝낸 붙이기의 알림은 <b>아직 안 지워졌다</b> — 삭제가 줄 안에 있기 때문이다.
            // 가시성 시한이 지나면 다시 오므로 유실은 아니고, 그 사이가 곧 지연이다.
            log.warn("broadcast.intake.drain_timeout inFlight={} waitMs={}",
                    runner.inFlight(), DRAIN_WAIT.toMillis());
        }
        thread = null;
    }

    @Override
    public boolean isRunning() {
        Thread running = thread;
        return running != null && running.isAlive();
    }
}
