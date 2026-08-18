package com.pokeclip.clip.broadcast;

public enum ProcessResult {
    /** 명부에 반영했다. */
    PROCESSED,
    /** 이미 받은 편지다. 처리하지 않고 넘어간다 — 오류가 아니다. */
    DUPLICATE,
    /** 이미 반영한 것보다 낮은 순서 번호였다(POK-88). 태스크 5에서 쓴다. */
    IGNORED_STALE
}
