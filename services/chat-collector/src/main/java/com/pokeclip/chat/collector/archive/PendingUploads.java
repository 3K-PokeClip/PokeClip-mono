package com.pokeclip.chat.collector.archive;

import java.util.ArrayDeque;

/**
 * 닫힌 파일이 S3에 올라갈 때까지 서는 줄. 아카이브 스레드가 넣고 빼지만 size·카운터는
 * 요약 로거(다른 스레드)가 읽으므로 전부 synchronized다.
 *
 * <p>상한을 넘으면 <b>가장 오래된 파일부터</b> 버리고 파일 수·그 안의 채팅 수를 센다 —
 * 메모리 상한이 거짓말이 안 되게. 최근 것이 판별에 더 가치 있다는 판단은 ChatBuffer와 같다.
 */
public final class PendingUploads {

    private final ArrayDeque<ArchiveObject> queue = new ArrayDeque<>();
    private final int capacity;
    private long droppedObjects;
    private long droppedMessages;

    public PendingUploads(int capacity) {
        this.capacity = capacity;
    }

    public synchronized void enqueue(ArchiveObject object) {
        queue.addLast(object);
        while (queue.size() > capacity) {
            ArchiveObject dropped = queue.pollFirst();
            droppedObjects++;
            droppedMessages += dropped.messageCount();
        }
    }

    /** 다음에 올릴 것. 없으면 null. 성공하면 removeHead()로 뺀다 — 실패하면 그대로 둔다. */
    public synchronized ArchiveObject peek() {
        return queue.peekFirst();
    }

    public synchronized void removeHead() {
        queue.pollFirst();
    }

    public synchronized int size() {
        return queue.size();
    }

    public synchronized long droppedObjects() {
        return droppedObjects;
    }

    public synchronized long droppedMessages() {
        return droppedMessages;
    }
}
