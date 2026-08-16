package com.pokeclip.chat.collector.archive;

import java.util.ArrayDeque;

/**
 * 닫힌 파일이 S3에 올라갈 때까지 서는 줄. 아카이브 스레드가 넣고 빼지만 size·카운터는
 * 요약 로거(다른 스레드)가 읽으므로 전부 synchronized다.
 *
 * <p>상한을 넘으면 <b>가장 오래된 파일부터</b> 버리고 파일 수·그 안의 채팅 수를 센다 —
 * 메모리 상한이 거짓말이 안 되게. 최근 것이 판별에 더 가치 있다는 판단은 ChatBuffer와 같다.
 *
 * <p><b>올린 수(uploaded)도 이 줄이 센다.</b> 판정 줄의 등식 {@code uploaded + pending + droppedObjects
 * = 닫힌 창 수}에서 세 항이 전부 이 락 안에서 움직여야 요약 로거가 어느 순간 읽어도 어긋나지 않는다 —
 * "줄에서 빼기"와 "올린 수 올리기"가 두 연산이면 그 사이에 읽힌 줄은 등식이 1 모자라고, 판정 줄이 그
 * 순간이면 영영 안 고쳐진다(/code-review 1라운드 K10).
 */
public final class PendingUploads {

    /** {@link #dropAll()}이 돌려주는 것 — 버린 파일 수와 그 안의 채팅 수. */
    public record Dropped(int objects, long messages) { }

    private final ArrayDeque<ArchiveObject> queue = new ArrayDeque<>();
    private final int capacity;
    private long uploaded;
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

    /** 다음에 올릴 것. 없으면 null. 성공하면 markHeadUploaded()로 뺀다 — 실패하면 그대로 둔다. */
    public synchronized ArchiveObject peek() {
        return queue.peekFirst();
    }

    /** 앞의 것을 올렸다 — 줄에서 빼고 올린 수를 한 락 안에서 올린다(클래스 주석). */
    public synchronized void markHeadUploaded() {
        queue.pollFirst();
        uploaded++;
    }

    /**
     * 종료에서 한 번 실패한 뒤 남은 것을 전부 버린다 — 비우며 세고 같은 드롭 카운터에 더해 등식이 유지되게.
     * 로그에 남길 수를 돌려준다.
     */
    public synchronized Dropped dropAll() {
        int objects = 0;
        long messages = 0;
        ArchiveObject o;
        while ((o = queue.pollFirst()) != null) {
            objects++;
            messages += o.messageCount();
        }
        droppedObjects += objects;
        droppedMessages += messages;
        return new Dropped(objects, messages);
    }

    public synchronized int size() {
        return queue.size();
    }

    public synchronized long uploaded() {
        return uploaded;
    }

    public synchronized long droppedObjects() {
        return droppedObjects;
    }

    public synchronized long droppedMessages() {
        return droppedMessages;
    }
}
