package com.pokeclip.chat.collector.archive;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * 수신 스레드 → 아카이브 스레드 바구니. <b>수신 스레드는 offer만 부른다.</b>
 * persist.ChatBuffer와 같은 구조 — 한 모니터 안에서 큐 조작과 상한 판정을 같이 한다
 * (그쪽 주석의 PR #56 P1 사고가 이유다). restoreFront가 없는 것은 창에 넣는 일이
 * 메모리 작업이라 실패가 없기 때문이다.
 *
 * <p>상한을 두는 이유: 파일 대기 줄만 막으면 그 앞이 뚫려 있다 — 업로드가 매달리거나
 * 백오프 60초 동안 이 바구니가 무한히 커져 <b>수신이 죽는다</b>(PRD 결정 ①).
 * 넘치면 오래된 것부터 버리고 센다 — 등식 received = archived + archiveBufferDropped.
 */
public final class ArchiveBuffer {

    private final ArrayDeque<ArchivableChat> queue = new ArrayDeque<>();
    private final int capacity;
    private long dropped;

    public ArchiveBuffer(int capacity) {
        this.capacity = capacity;
    }

    public synchronized void offer(ArchivableChat chat) {
        queue.addLast(chat);
        while (queue.size() > capacity) {
            queue.pollFirst();
            dropped++;
        }
    }

    public synchronized List<ArchivableChat> drain(int max) {
        List<ArchivableChat> out = new ArrayList<>();
        ArchivableChat chat;
        while (out.size() < max && (chat = queue.pollFirst()) != null) {
            out.add(chat);
        }
        return out;
    }

    public synchronized int size() {
        return queue.size();
    }

    public synchronized long droppedCount() {
        return dropped;
    }
}
