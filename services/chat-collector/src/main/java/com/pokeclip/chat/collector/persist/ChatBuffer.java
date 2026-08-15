package com.pokeclip.chat.collector.persist;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * 수신 스레드와 저장 스레드 사이의 바구니. <b>수신 스레드는 offer만 부른다</b> —
 * 여기서 I/O를 하면 채팅 폭주 때 수신이 밀린다(8/1 ping 사고와 같은 구조).
 *
 * <p>가득 차면 <b>가장 오래된 것</b>을 버린다. DB가 죽어 있는 동안 상한을 넘치면
 * 어차피 무언가는 잃는다 — 최근 것이 판별(실시간 급증)에 더 가치 있으므로
 * 오래된 쪽을 버리고, 버린 수를 세서 요약 로그로 드러낸다.
 *
 * <p><b>큐 조작과 크기·dropped 갱신은 전부 한 모니터(this) 안이다.</b> 예전에는
 * ConcurrentLinkedDeque + AtomicInteger size였는데 — 그 size()가 O(n)이라 카운터를
 * 따로 뒀다 — offer의 add와 size 증가, drain의 poll과 size 감소가 서로 다른 순간에
 * 일어나서 두 스레드가 겹치면 size가 큐의 실제 크기와 어긋났다. 상한 판정과 dropped가
 * 그 size를 보므로 어긋난 순간에 상한이 뚫리거나(size가 capacity+1로 보임) 필요 없는
 * 드롭이 났다(PR #56 P1 — 스트레스 300라운드 중 227라운드에서 음수, 173라운드에서
 * 상한 초과 관측). 락 안에서는 ArrayDeque.size()가 O(1)이라 별도 카운터 자체가
 * 필요 없고, 카운터가 없으니 어긋날 것도 없다.
 *
 * <p>수신 핫패스에 락이지만 경합 상대는 저장 스레드 하나뿐이고, 그쪽이 락을 잡는
 * 것은 1초에 한 번 drain(최대 DRAIN_MAX개 poll — 마이크로초 단위)과 실패 시
 * restoreFront뿐이다. 무경합 synchronized는 수십 ns라 수신에 안 보인다.
 */
@Component
public final class ChatBuffer {

    /**
     * 운영 기본 상한. 폭주 시 손실 없이 담을 수 있는 양을 실측한 적은 없다 —
     * 계획(POK-84)이 정한 값이고, dropped 카운터가 0이 아니게 되는 날 다시 잰다.
     */
    private static final int DEFAULT_CAPACITY = 10_000;

    // Deque인 이유: 실패한 배치를 <b>앞으로</b> 되돌려야 한다(restoreFront).
    // 꼬리로 되돌리면 시각 순서가 뒤집혀 상한 초과 head-drop이 실패분 대신
    // 더 새로운 채팅을 버린다.
    private final ArrayDeque<PersistableChat> queue = new ArrayDeque<>();
    private long dropped;
    private final int capacity;

    /** 스프링이 쓰는 생성자 — 생성자가 여럿이라 기본 생성자로 폴백한다. */
    public ChatBuffer() {
        this(DEFAULT_CAPACITY);
    }

    public ChatBuffer(int capacity) {
        this.capacity = capacity;
    }

    public synchronized void offer(PersistableChat chat) {
        queue.addLast(chat);
        evictOverflow();
    }

    /**
     * 저장에 실패한 배치를 <b>큐 앞으로</b> 되돌린다 — drain으로 꺼낸 것이 가장
     * 오래된 쪽이므로 앞이 원래 자리다. 역순 addFirst라 배치 내부 순서도 유지된다.
     * 저장 스레드 전용이다(수신 스레드는 offer만 부른다).
     *
     * <p>되돌리는 사이 새 수신이 상한까지 찼으면 되돌린 만큼 초과다 — 초과분은
     * head(방금 되돌린, 가장 오래된 것)부터 버리고 센다. 안 지키면 상한이
     * 순간적으로 뚫려 이 상한이 말하는 메모리 한도가 거짓이 된다.
     */
    synchronized void restoreFront(List<PersistableChat> chats) {
        for (int i = chats.size() - 1; i >= 0; i--) {
            queue.addFirst(chats.get(i));
        }
        evictOverflow();
    }

    /** 상한을 넘은 만큼 head(가장 오래된 것)부터 버리고 센다. 호출자가 락을 쥔다. */
    private void evictOverflow() {
        while (queue.size() > capacity) {
            queue.pollFirst();
            dropped++;
        }
    }

    public synchronized List<PersistableChat> drain(int max) {
        List<PersistableChat> out = new ArrayList<>();
        PersistableChat chat;
        while (out.size() < max && (chat = queue.pollFirst()) != null) {
            out.add(chat);
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
