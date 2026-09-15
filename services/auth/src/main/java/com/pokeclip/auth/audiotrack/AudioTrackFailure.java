package com.pokeclip.auth.audiotrack;

/** 트랙 이름 창구의 거절 사유. 본문 {@code {"reason": "<이름>"}}으로 나간다(위임·연동 창구와 같은 모양). */
public enum AudioTrackFailure {
    /** 칸이 여섯이 아니다. 트랙 수는 고정이라 화면이 항상 여섯을 보낸다 — 아니면 화면 쪽 실수다. */
    LABELS_SIZE,
    /** 이름이 코드 포인트 32자를 넘는다. 표시 이름과 같은 셈법이다(JS {@code [...s].length}). */
    LABEL_TOO_LONG,
    /** 그 스트리머의 이름을 볼 자격이 없거나 그런 회원이 없다 — 둘을 가르지 않는다. */
    STREAMER_NOT_FOUND
}
