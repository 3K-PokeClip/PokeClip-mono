package com.pokeclip.chat.collector.persist;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
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

    private final ConcurrentLinkedQueue<PersistableChat> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger size = new AtomicInteger();
    private final AtomicLong dropped = new AtomicLong();
    private final int capacity;

    /** 스프링이 쓰는 생성자 — 생성자가 여럿이라 기본 생성자로 폴백한다. */
    public ChatBuffer() {
        this(DEFAULT_CAPACITY);
    }

    public ChatBuffer(int capacity) {
        this.capacity = capacity;
    }

    public void offer(PersistableChat chat) {
        queue.add(chat);
        if (size.incrementAndGet() > capacity) {
            if (queue.poll() != null) {
                size.decrementAndGet();
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
