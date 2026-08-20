package com.pokeclip.chat.collector.broadcast;

/**
 * 편지 하나를 판정한 결과. <b>네 값의 뜻은 곧 큐에서 지울지 말지다</b> —
 * 러너는 {@code RETRY_LATER}만 큐에 남기고 나머지 셋은 지운다.
 *
 * <p>그래서 판정을 고를 때의 질문은 하나다: <b>이 편지를 다시 받아야 하는가.</b>
 * 다시 받아도 결과가 같을 것이면 지운다 — 안 지우면 FIFO라 같은 방송의 뒤 편지가 전부 막힌다.
 */
public enum ProcessResult {

    /** 처리했다. 다시 받을 필요가 없다. */
    PROCESSED,

    /** 낡아서 무시했다. 다시 받아도 여전히 낡았다. */
    IGNORED_STALE,

    /** 지금은 못 했지만 다음에는 될 수 있다. <b>이 값만 편지를 큐에 남긴다.</b> */
    RETRY_LATER,

    /** 우리가 읽을 수 없는 편지다. 다시 받아도 못 읽는다. */
    UNREADABLE
}
