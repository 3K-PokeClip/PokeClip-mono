package com.pokeclip.chat.collector.broadcast.attach;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 같은 줄({@code lane})의 작업은 <b>넣은 순서대로 하나씩</b>, 다른 줄끼리는 <b>완전히 병렬</b>로
 * 돌린다. 줄 이름은 스트리머 식별자다.
 *
 * <p><b>왜 스트리머로 가르나</b>: 세션 자리의 열쇠가 스트리머이고(ADR-046) 갈아끼움 판정도
 * 그 안에서 일어난다. 같은 스트리머의 두 방송이 병렬로 수립되면 등록부의 갈아끼움이 꼬인다.
 * 방송 번호로 가르면 그 보호가 사라진다 — 같은 스트리머의 두 방송이 다른 줄이 된다.
 *
 * <p><b>이 부품은 방송도 수집도 모른다.</b> 「누가 언제 도는가」만 안다.
 */
public final class StreamerSerialExecutor implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(StreamerSerialExecutor.class);

    /** 한 줄의 상태. <b>맵의 {@code compute} 안에서만 만지므로 자체 동기화가 없다.</b> */
    private static final class Lane {
        final Deque<Runnable> pending = new ArrayDeque<>();
        boolean running;
    }

    private final ConcurrentHashMap<String, Lane> lanes = new ConcurrentHashMap<>();
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    /**
     * <b>돌고 있는 것 + 대기 중인 것</b>. 「대기」만이 아니다 — {@link #run}의 finally에서만
     * 내려가므로 실행 중인 작업도 여기 들어 있다. 백프레셔의 뜻에 그쪽이 맞다:
     * 돌고 있는 붙이기도 메모리와 auth 커넥션을 쓴다.
     */
    private final AtomicInteger inFlight = new AtomicInteger();
    private final int maxInFlight;

    public StreamerSerialExecutor(int maxInFlight) {
        this.maxInFlight = maxInFlight;
    }

    /**
     * @return 받아들였으면 true. <b>false는 「지금은 더 못 받는다」</b>이고, 부르는 쪽은
     *         그것을 신호로 새 일감 꺼내기를 쉰다(백프레셔). 던지지 않는 이유는 이것이
     *         오류가 아니라 정상적인 포화이기 때문이다
     */
    public boolean submit(String lane, Runnable task) {
        // 세는 것이 맵 조작보다 먼저다. 자리를 잡고 나서 세면 그 사이 상한을
        // 넘긴 작업이 이미 줄에 들어가 있어 되돌릴 수 없다.
        // (「compute가 재실행되어 두 번 센다」가 아니다 — 계획 검증이 32스레드 ×
        //  2,000회로 재서 함수 호출이 정확히 64,000임을 확인했고 javadoc도
        //  "invoked exactly once"다. 재실행되는 것은 AtomicInteger.updateAndGet 같은
        //  CAS 루프다.)
        if (inFlight.incrementAndGet() > maxInFlight) {
            inFlight.decrementAndGet();
            return false;
        }
        boolean[] startNow = new boolean[1];
        lanes.compute(lane, (key, existing) -> {
            Lane target = existing != null ? existing : new Lane();
            if (target.running) {
                target.pending.addLast(task);
            } else {
                target.running = true;
                startNow[0] = true;
            }
            return target;
        });
        if (startNow[0]) {
            workers.execute(() -> run(lane, task));
        }
        return true;
    }

    /**
     * 그 줄의 <b>대기 중인</b> 작업을 버린다. 돌고 있는 것은 안 건드린다.
     *
     * <p><b>왜 필요한가</b>: 같은 방송의 알림 여럿을 한 회차에 받았는데 앞엣것이 실패하면
     * 뒤엣것을 처리하면 안 된다 — 뒤가 먼저 반영되면 앞엣것이 「낡음」으로 걸러진 뒤 지워져
     * <b>재전송으로도 못 고치는 영구 유실</b>이 된다(기존 러너의 회차 중단 주석과 같은 이유).
     * 버린 것은 큐에 그대로 있으므로 가시성 시한이 지나면 다시 온다.
     */
    public void dropPending(String lane) {
        lanes.computeIfPresent(lane, (key, target) -> {
            inFlight.addAndGet(-target.pending.size());
            target.pending.clear();
            return target;
        });
    }

    public int inFlight() {
        return inFlight.get();
    }

    /**
     * 더 받을 자리가 없는가. <b>부르는 쪽은 이것을 보고 큐를 두드리는 것 자체를 멈춘다</b> —
     * {@link #submit}이 false를 준 뒤에 멈추면 늦다. 이미 꺼낸 알림이 가시성 시한 동안
     * 숨겨지고, FIFO라 그 방송의 뒤 알림이 통째로 막힌다(계획 검증 M1).
     */
    public boolean saturated() {
        return inFlight.get() >= maxInFlight;
    }

    /** 살아 있는 줄의 수. 다 끝난 줄이 치워지는지를 검사가 이걸로 본다. */
    public int laneCount() {
        return lanes.size();
    }

    /**
     * @return 예산 안에 진행 중인 것이 0이 되면 true
     *
     * <p>🔴 <b>{@code lanes.isEmpty()}는 보지 않는다</b>(계획 검증 I3). 보면
     * {@code 다_끝나면_줄이_맵에서_사라진다}가 <b>자동으로 참</b>이 된다 —
     * {@code awaitIdle}이 이미 그것을 기다렸으므로 뒤따르는 단언이 아무것도 안 잰다.
     * 그리고 줄이 남는 것은 <b>누수</b>인데, 그것을 여기서 기다리면 누수가 있을 때
     * 종료가 예산을 다 쓴다. <b>기다리는 쪽과 재는 쪽을 가른다.</b>
     */
    public boolean awaitIdle(Duration budget) {
        long deadline = System.nanoTime() + budget.toNanos();
        while (System.nanoTime() < deadline) {
            if (inFlight.get() == 0) {
                return true;
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return inFlight.get() == 0;
    }

    @Override
    public void close() {
        // shutdownNow가 아니다 — 인터럽트하면 돌고 있는 붙이기가 REST 왕복 중에 끊긴다.
        // 가상 스레드라 JVM 종료를 붙들지 않는다.
        workers.shutdown();
    }

    private void run(String lane, Runnable first) {
        Runnable task = first;
        while (task != null) {
            try {
                task.run();
            } catch (Throwable t) {
                // 하나가 터져도 그 줄의 다음 작업은 돈다. 안 잡으면 줄이 통째로 멈춘 채
                // running=false가 안 되어 그 스트리머가 영영 막힌다.
                log.warn("chat.attach.task_failed lane={} causeType={}",
                        lane, t.getClass().getSimpleName());
            } finally {
                inFlight.decrementAndGet();
            }
            task = nextOrRelease(lane);
        }
    }

    /** 다음 작업을 꺼낸다. 없으면 줄을 <b>맵에서 지우고</b> null. 안 지우면 스트리머 수만큼 자란다. */
    private Runnable nextOrRelease(String lane) {
        Runnable[] next = new Runnable[1];
        lanes.compute(lane, (key, target) -> {
            if (target == null) {
                return null;
            }
            next[0] = target.pending.pollFirst();
            if (next[0] == null) {
                target.running = false;
                return null;   // 줄을 지운다
            }
            return target;
        });
        return next[0];
    }
}
