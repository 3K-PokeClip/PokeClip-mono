package com.pokeclip.chat.collector.persist;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * 후원용 바구니. <b>{@link ChatBuffer}의 쌍둥이다</b> — 규칙(한 모니터 안에서 큐·상한·
 * dropped를 다룬다, 상한을 넘으면 가장 오래된 것부터 버리고 센다, 실패한 배치는 앞으로
 * 되돌린다)이 글자 그대로 같고 담는 타입만 다르다. 한쪽을 고치면 다른 쪽도 본다.
 *
 * <p>상한이 1,000으로 채팅(10,000)보다 작은 이유는 유입량 차이다 — 후원은 채팅보다
 * 두 자릿수 드물다. <b>버린 수를 반드시 센다</b>: 후원은 아카이브에 안 쌓으므로
 * (검산 등식을 지키려고, {@code StreamSession} 주석) 여기서 버려지면 <b>표에도 원본에도
 * 없다</b> — 범위 창구가 메워 줄 길이 없는 유일한 유실이다.
 *
 * <p>🔴 <b>바구니 하나를 방송 전부가 나눠 쓴다</b>(스프링 빈 하나, POK-234 감사 라운드 2 A5).
 * 그래서 한 방송의 후원 폭주가 <b>다른 방송의 후원을 밀어낸다.</b> 채팅 바구니도 같은
 * 모양이라 새 결함은 아니지만, 채팅은 S3 아카이브에 원본이 남고 <b>후원은 그것이 없어
 * 되찾을 길이 없다</b> — 「되찾을 길이 없는 유일한 유실」이라는 성질이 이 바구니에만 있다.
 * <b>바구니를 키우거나 방송별로 가르는 날 그 사실이 근거가 된다.</b>
 * 지금은 고치지 않는다: 상한을 올리는 것은 값이 불확실하고(동시 방송 수가 아직 작다)
 * 방송별로 가르는 것은 구조 변경이다.
 */
@Component
public final class DonationBuffer {

    private static final int DEFAULT_CAPACITY = 1_000;

    private final ArrayDeque<PersistableDonation> queue = new ArrayDeque<>();
    private long dropped;
    private final int capacity;

    /** 스프링이 쓰는 생성자 — 생성자가 여럿이라 기본 생성자로 폴백한다. */
    public DonationBuffer() {
        this(DEFAULT_CAPACITY);
    }

    public DonationBuffer(int capacity) {
        this.capacity = capacity;
    }

    public synchronized void offer(PersistableDonation donation) {
        queue.addLast(donation);
        evictOverflow();
    }

    /** 저장에 실패한 배치를 큐 앞으로 되돌린다. 저장 스레드 전용이다. */
    synchronized void restoreFront(List<PersistableDonation> donations) {
        for (int i = donations.size() - 1; i >= 0; i--) {
            queue.addFirst(donations.get(i));
        }
        evictOverflow();
    }

    private void evictOverflow() {
        while (queue.size() > capacity) {
            queue.pollFirst();
            dropped++;
        }
    }

    public synchronized List<PersistableDonation> drain(int max) {
        List<PersistableDonation> out = new ArrayList<>();
        PersistableDonation donation;
        while (out.size() < max && (donation = queue.pollFirst()) != null) {
            out.add(donation);
        }
        return out;
    }

    public synchronized long droppedCount() {
        return dropped;
    }

    public synchronized int size() {
        return queue.size();
    }
}
