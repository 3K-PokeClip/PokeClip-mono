package com.pokeclip.clip.jumpcard.stream;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * SSE 전송을 <b>요청 스레드가 아닌 전용 스레드</b>에서 돈다. 연결은 스트라이프 하나에 고정돼
 * 같은 연결의 이벤트 순서가 지켜진다.
 *
 * <p><b>시한 장치가 없다.</b> 원안은 감시자가 시한을 넘긴 {@code send}를 {@code completeWithError}로
 * 끊는 것이었는데 작동하지 않는다 — 둘이 같은 {@code writeLock}을 써서 끊으러 간 감시자가 그 락에서
 * 같이 멈추고({@code state=WAITING} 실측), 감시자가 하나라 무관한 스트라이프의 시한까지 죽는다
 * (스트라이프 0을 막고 1에 200ms 시한을 걸었더니 2초가 지나도 안 끊겼다. 2026-08-23 실측).
 *
 * <p>그래서 둘에 맡긴다 — ① <b>스트라이프 격리</b>로 막힌 연결이 자기 줄만 막게 하고
 * ② 클라이언트가 계속 안 읽으면 <b>서버의 write timeout</b>이 소켓 write를 {@code IOException}으로
 * 풀어 그 스트라이프가 회복된다.
 *
 * <p><b>감수하는 것</b> — 같은 스트라이프의 다른 연결은 그 시간만큼 이벤트가 밀린다.
 * 스트라이프 4개면 최악 1/4이 영향을 받는다. 연결마다 스레드를 주면 막을 수 있지만
 * 상한 500개에 스레드 500개가 된다.
 */
@Component
public class CardStreamExecutor {

    private static final Logger log = LoggerFactory.getLogger(CardStreamExecutor.class);

    @FunctionalInterface
    public interface SendAction {
        /**
         * {@code throws Exception}이다. {@code IOException}으로 좁히면 {@code await()}를 쓰는
         * 시험 람다가 {@code incompatible thrown types}로 컴파일되지 않는다.
         */
        void run() throws Exception;
    }

    private final ThreadPoolExecutor[] stripes;

    @Autowired
    public CardStreamExecutor(StreamProperties properties) {
        this(properties.stripes(), properties.queueCapacity());
    }

    CardStreamExecutor(int stripeCount, int capacity) {
        this.stripes = new ThreadPoolExecutor[stripeCount];
        for (int i = 0; i < stripeCount; i++) {
            int n = i;
            stripes[i] = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<>(capacity),
                    r -> {
                        Thread t = new Thread(r, "jumpcard-stream-" + n);
                        // 종료 유예 동안 대기 중 전송을 끝내려면 데몬이 아니어야 한다.
                        t.setDaemon(false);
                        return t;
                    },
                    (r, executor) -> {
                        // 🔴 거부됐다는 사실을 호출자에게 <b>돌려준다</b>. 이 처리기는 예외를 안 던지고
                        // 정상 복귀하므로, 표시를 안 남기면 submit이 성공과 구별되지 않는다 —
                        // broadcastEnded가 성공처럼 보이고 SQS 러너가 편지를 지운다
                        // (PR #112 봇 지적 ②, 재현: 0ms에 예외 없이 반환·연결은 60초 뒤에도 살아 있음).
                        //
                        // execute()를 부른 <b>그 스레드에서 동기적으로</b> 도는 자리라
                        // submit이 반환할 때 값이 이미 확정이다.
                        if (r instanceof Job job) {
                            job.rejected = true;
                        }
                        // 넘친 것이 조용히 사라지면 "왜 카드가 안 왔나"를 추적할 방법이 없다.
                        //
                        // 🔴 <b>POK-174 뒤로 재연결은 어느 쪽도 안 메운다</b> — 통로가 지난 카드를 아예
                        // 안 보낸다. 버려진 카드를 메우는 것은 <b>카드 목록 문</b>이고 언제 다시 부를지는
                        // 화면이 정한다("통로 먼저, 목록 나중"). 전에 적혀 있던 "재연결이 전체 스냅샷으로
                        // 메운다"는 실시간 발행에만 참이었고 초기 스냅샷에는 그때도 거짓이었다 — 재연결해도
                        // 같은 스냅샷이라 같은 자리에서 또 잘렸다(2026-08-23 실측: 1200장에서 201건 유실이
                        // 2회차에도 그대로). 지금 sendInitial은 카드를 안 실어 태스크 하나 = 큐 한 칸이다.
                        //
                        // 🔴 여기서 completeWithError를 부르지 않는다. 거부 처리기는 execute()를 부른
                        // 스레드에서 도는데, 그 스레드가 publish의 afterCommit 안에 있는 요청 스레드다.
                        // completeWithError는 막힌 send가 쥔 writeLock을 기다리므로 요청 스레드가
                        // 거기서 잠기고, afterCommit은 커넥션 반납보다 먼저 도는 자리라 JDBC 커넥션을
                        // 쥔 채로 잠긴다 — 2A 한 번이 59,164ms 걸리는 것을 실측했다(비동기 2차 감사).
                        // 그것이 POK-93에서 실제로 난 풀 고갈 그림이다.
                        //
                        // 그 연결의 스트라이프에 제출하는 방법(대안 나)은 쓸 수 없다 — 지금 그 큐가
                        // 가득 차서 거부된 참이라 다시 거부된다. 자리 회수는 다음 send 실패가 한다
                        // (`끊고_다시_열_수_있다`가 재는 경로다).
                        log.warn("jumpcard.stream.rejected stripe={} reason={}", n,
                                executor.isShutdown() ? "shutdown" : "queue_full");
                    });
        }
    }

    /**
     * {@code stripe}는 호출자가 연결에 고정해 준 번호다. 같은 연결의 작업은 같은 스레드에서
     * 순서대로 돈다 — 다른 스레드로 나가면 「집음 → 놓음」이 뒤바뀌어 화면이 옛 상태를 나중에 받는다.
     *
     * @return 큐에 들어갔으면 {@code true}, 큐가 차서 버려졌으면 {@code false}.
     *         <b>버려진 것을 회복할 방법은 호출자마다 다르므로 여기서 정하지 않는다</b> —
     *         실시간 발행은 <b>카드 목록 문</b>이 메우고(POK-174 뒤로 재연결은 안 메운다 —
     *         {@link CardStreamRegistry#publish}), 종료 알림은 메울 것이 없어 자리를 회수한다
     *         ({@link CardStreamRegistry#broadcastEnded}).
     */
    public boolean submit(int stripe, SseEmitter emitter, SendAction action) {
        Job job = new Job(emitter, action);
        stripes[Math.floorMod(stripe, stripes.length)].execute(job);
        return !job.rejected;
    }

    private static final class Job implements Runnable {

        private final SseEmitter emitter;
        private final SendAction action;

        /**
         * 거부 처리기가 세운다. {@code volatile}인 이유는 값이 아니라 <b>규약</b>이다 —
         * 지금은 {@code execute()}를 부른 같은 스레드에서 세워지고 같은 스레드가 읽지만,
         * 그 사실이 {@code ThreadPoolExecutor} 구현에 달려 있어 밖에서 보장되지 않는다.
         */
        private volatile boolean rejected;

        Job(SseEmitter emitter, SendAction action) {
            this.emitter = emitter;
            this.action = action;
        }

        @Override
        public void run() {
            try {
                action.run();
            } catch (Exception e) {
                // 끊긴 연결이다. Registry가 콜백으로 자리를 치운다.
                // 여기서 던지면 스트라이프 스레드가 죽어 그 줄의 다른 연결이 통째로 멈춘다.
                emitter.completeWithError(e);
            }
        }
    }

    @PreDestroy
    void shutdown() {
        for (ThreadPoolExecutor stripe : stripes) {
            stripe.shutdown();
        }
        for (int i = 0; i < stripes.length; i++) {
            try {
                if (!stripes[i].awaitTermination(5, TimeUnit.SECONDS)) {
                    log.warn("jumpcard.stream.shutdown_timeout stripe={} pending={}", i,
                            stripes[i].getQueue().size() + stripes[i].getActiveCount());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
