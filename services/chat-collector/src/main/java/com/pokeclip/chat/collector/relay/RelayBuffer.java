package com.pokeclip.chat.collector.relay;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * 수신 스레드 → 중계 스레드 바구니. <b>중계 규칙 넷이 전부 여기 한 곳에 있다</b>(계획 검증 F7) —
 * ① 방송 번호가 null이면 버린다 ② 닫혔으면 버린다 ③ 번호를 붙여 담는다 ④ 상한을 넘으면
 * 오래된 것부터 버리고 센다. 호출부(채팅·후원, PR-C의 방송 정보)마다 규칙을 두면
 * 「같은 뿌리인데 한 자리만」이 난다 — 이 저장소가 반복해서 데인 모양이다.
 *
 * <p><b>번호 붙이기와 담기가 한 모니터 안이다.</b> 둘이 갈리면 같은 방송에 두 스레드가 겹쳐
 * 넣을 때 줄 순서와 번호 순서가 어긋난다. 카운터를 {@code ConcurrentHashMap<AtomicLong>}으로
 * 따로 두지 않은 이유가 그것이고, 어차피 자물쇠 안이라 평범한 {@link HashMap}으로 충분하다.
 *
 * <p><b>넘칠 때 버리는 것은 이미 번호를 받은 것이다</b> — 그래서 화면이 빈틈으로 알아채고
 * 범위 창구로 메울 수 있다. 번호를 매기기 전에 버리면 건너뜀이 안 생겨 영영 모른다
 * (relay-loss-coverage 기준 C). 닫힘·번호 없음은 번호를 매기기 전에 버리지만 둘 다
 * 의도한 손실이다: 닫힘은 프로세스가 내려가는 중이고(다시 뜨면 번호가 1로 돌아가 화면이 메운다),
 * 번호 없음은 옛 경로라 보낼 곳이 없다.
 *
 * <p>수신 핫패스의 자물쇠지만 경합 상대는 중계 스레드 하나이고 그쪽은 {@code drain}만 한다 —
 * {@code ChatBuffer}와 같은 판단이다.
 */
public final class RelayBuffer implements RelaySink {

    private final ArrayDeque<RelayEvent> queue = new ArrayDeque<>();
    private final Map<String, Counter> counters = new HashMap<>();
    private final int capacity;
    private final LongSupplier clock;
    /** 마지막으로 내준 {@code seqEpoch}. 같은 ms에 카운터가 다시 서도 값이 겹치지 않게 이보다 크게 준다. */
    private long lastEpoch = Long.MIN_VALUE;
    private long dropped;
    private boolean closed;

    public RelayBuffer(int capacity) {
        this(capacity, System::currentTimeMillis);
    }

    /** 시계를 고정하는 검사용. 「같은 ms에 카운터가 다시 서도 epoch가 다르다」는 실제 시계로는 결정적으로 못 잰다. */
    RelayBuffer(int capacity, LongSupplier clock) {
        this.clock = clock;
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity는 1 이상이어야 한다: " + capacity);
        }
        this.capacity = capacity;
    }

    @Override
    public synchronized void offer(String streamId, RelayPayload payload) {
        if (streamId == null || closed) {
            return;
        }
        Counter counter = counters.computeIfAbsent(streamId, ignored -> new Counter(nextEpoch()));
        counter.seq++;
        queue.addLast(new RelayEvent(streamId, counter.seq, counter.epoch, payload));
        while (queue.size() > capacity) {
            queue.pollFirst();
            dropped++;
        }
    }

    public synchronized List<RelayEvent> drain(int max) {
        List<RelayEvent> out = new ArrayList<>(Math.min(max, queue.size()));
        RelayEvent event;
        while (out.size() < max && (event = queue.pollFirst()) != null) {
            out.add(event);
        }
        return out;
    }

    /**
     * 🔴 <b>남는 틈 하나</b>(F6): 번호를 읽은 수신 스레드가 멈춘 사이 이것이 지나가면 그 프레임이
     * 카운터를 1부터 되살리고 영영 남는다. 방송당 {@code long} 하나라 치우는 장치를 더 두지 않았다.
     */
    @Override
    public synchronized void forget(String streamId) {
        counters.remove(streamId);
    }

    /** 이후 {@link #offer}를 무시한다. 이미 담긴 것은 {@link #drain}으로 꺼낼 수 있다. */
    public synchronized void close() {
        closed = true;
    }

    /** 상한 초과로 버린 수. 닫힘·번호 없음은 세지 않는다(의도한 손실이라 섞으면 이 값이 거짓이 된다). */
    public synchronized long droppedCount() {
        return dropped;
    }

    public synchronized int size() {
        return queue.size();
    }

    /**
     * 번호 카운터를 들고 있는 방송 수. <b>검사가 {@link #forget}이 불렸는지를 재는 창이다</b> —
     * 안 불려도 동작은 같고 메모리만 새므로 행동으로는 안 보인다. 운영 코드는 안 부른다.
     */
    synchronized int trackedStreamCount() {
        return counters.size();
    }

    /** 시계 값이되 직전에 내준 것보다 늘 크다 — 한 프로세스 안의 재생성끼리는 절대 안 겹친다. */
    private long nextEpoch() {
        lastEpoch = Math.max(clock.getAsLong(), lastEpoch + 1);
        return lastEpoch;
    }

    private static final class Counter {
        private final long epoch;
        private long seq;

        private Counter(long epoch) {
            this.epoch = epoch;
        }
    }
}
