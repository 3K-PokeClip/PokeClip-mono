package com.pokeclip.chat.collector.persist;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 수신 스레드와 저장 스레드 사이의 바구니. <b>수신 스레드는 offer만 부른다</b> —
 * 여기서 I/O를 하면 채팅 폭주 때 수신이 밀린다(8/1 ping 사고와 같은 구조).
 *
 * <p>가득 차면 <b>가장 오래된 것</b>을 버린다. DB가 죽어 있는 동안 상한을 넘치면
 * 어차피 무언가는 잃는다 — 최근 것이 판별(실시간 급증)에 더 가치 있으므로
 * 오래된 쪽을 버리고, 버린 수를 세서 요약 로그로 드러낸다.
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
    private final ConcurrentLinkedDeque<PersistableChat> queue = new ConcurrentLinkedDeque<>();
    private final AtomicInteger size = new AtomicInteger();
    private final AtomicLong dropped = new AtomicLong();
    private final int capacity;

    /**
     * 초과 처리(size 판정 → poll → dropped++)만 직렬화한다. 큐 add/addFirst 자체는
     * 락 밖이다 — 이 락이 지키는 것은 큐가 아니라 <b>"초과분을 누가 세느냐"</b>다.
     *
     * <p>없으면 offer와 restoreFront가 같은 초과분을 각각 센다: 한쪽이 "상한 초과"로
     * 판정한 뒤 poll하기 전에 다른 쪽이 그 초과까지 걷어내면, 앞쪽은 이미 상한
     * 안인데도 하나를 더 버린다 — 불필요 드롭 1 + dropped 오차 1(PR #53 P2, 스트레스
     * 300라운드 중 21라운드 실측). 핫패스(수신 스레드)에 락이지만 무경합이면 비용은
     * 무시할 수준이고, 경합 시 불필요 드롭과 카운터 오차를 막는다.
     *
     * <p>drain의 감산은 락 밖에 둔다. poll과 감산 사이 한 줄 동안 size가 큐보다 잠깐
     * 크게 보이는데, 그때 offer가 버리는 것은 "꺼낸 배치가 되돌아오면 초과"인 양이라
     * 상한 산정 자체는 안 어긋난다 — 배치가 저장에 성공하면 사후적으로는 안 버려도
     * 됐던 하나다. 이 수정 범위(offer↔restoreFront 이중 계상) 밖이라 여기 적어만 둔다.
     */
    private final Object evictLock = new Object();

    /** 스프링이 쓰는 생성자 — 생성자가 여럿이라 기본 생성자로 폴백한다. */
    public ChatBuffer() {
        this(DEFAULT_CAPACITY);
    }

    public ChatBuffer(int capacity) {
        this.capacity = capacity;
    }

    public void offer(PersistableChat chat) {
        queue.add(chat);
        synchronized (evictLock) {
            if (size.incrementAndGet() > capacity) {
                if (queue.poll() != null) {
                    size.decrementAndGet();
                    dropped.incrementAndGet();
                }
            }
        }
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
    void restoreFront(List<PersistableChat> chats) {
        for (int i = chats.size() - 1; i >= 0; i--) {
            queue.addFirst(chats.get(i));
        }
        synchronized (evictLock) {
            int now = size.addAndGet(chats.size());
            while (now > capacity && queue.pollFirst() != null) {
                now = size.decrementAndGet();
                dropped.incrementAndGet();
            }
        }
    }

    public List<PersistableChat> drain(int max) {
        List<PersistableChat> out = new ArrayList<>();
        PersistableChat chat;
        while (out.size() < max && (chat = queue.poll()) != null) {
            size.decrementAndGet();
            out.add(chat);
        }
        return out;
    }

    public long droppedCount() {
        return dropped.get();
    }

    public int size() {
        return size.get();
    }
}
