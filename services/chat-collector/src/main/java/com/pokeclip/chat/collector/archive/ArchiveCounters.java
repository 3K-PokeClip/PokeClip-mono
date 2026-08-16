package com.pokeclip.chat.collector.archive;

/** 아카이브 관측 카운터 여섯 + runId. 요약·판정 줄이 이 묶음으로 받는다(PersistCounters와 같은 이유). */
public interface ArchiveCounters {

    /** 창에 들어간 채팅 수 (received = archived + archiveBufferDropped). */
    long archivedCount();

    /** 바구니 상한 초과로 버린 채팅 수. */
    long archiveBufferDroppedCount();

    /** 올린 파일 수. */
    long uploadedCount();

    /** 대기 중 파일 수. */
    long pendingCount();

    /** 대기 줄 상한 초과 + 종료 시 못 올려 버린 파일 수. */
    long droppedObjectsCount();

    /** 그 파일들 안의 채팅 수. */
    long droppedMessagesCount();

    String runId();

    ArchiveCounters NONE = new ArchiveCounters() {
        @Override public long archivedCount() { return 0; }
        @Override public long archiveBufferDroppedCount() { return 0; }
        @Override public long uploadedCount() { return 0; }
        @Override public long pendingCount() { return 0; }
        @Override public long droppedObjectsCount() { return 0; }
        @Override public long droppedMessagesCount() { return 0; }
        @Override public String runId() { return "none"; }
    };
}
