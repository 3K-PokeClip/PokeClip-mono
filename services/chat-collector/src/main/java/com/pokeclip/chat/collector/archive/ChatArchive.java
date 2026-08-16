package com.pokeclip.chat.collector.archive;

import java.time.Duration;

/**
 * 러너가 보는 아카이브. 꺼져 있으면 {@link #NONE}이 끼워진다 — 러너는 켜짐/꺼짐을 모른다.
 * close가 둘로 갈라진 이유: 러너가 persister.close()와 <b>나란히</b> 닫기 위해서다 —
 * beginClose()는 마지막 flush를 제출만 하고 즉시 돌아오고, awaitClosed()가 기다린다.
 */
public interface ChatArchive {

    void offer(ArchivableChat chat);

    void beginClose();

    void awaitClosed(Duration budget);

    ArchiveCounters counters();

    ChatArchive NONE = new ChatArchive() {
        @Override public void offer(ArchivableChat chat) { }
        @Override public void beginClose() { }
        @Override public void awaitClosed(Duration budget) { }
        @Override public ArchiveCounters counters() { return ArchiveCounters.NONE; }
    };
}
