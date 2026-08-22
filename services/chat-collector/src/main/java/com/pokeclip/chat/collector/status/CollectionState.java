package com.pokeclip.chat.collector.status;

import com.pokeclip.chat.collector.CollectionStatus;
import com.pokeclip.chat.collector.StopReason;

import java.util.Locale;

/**
 * 창구가 밖에 말하는 상태 여섯. 세션 상태({@link CollectionStatus.State})와 일부러 다른 열거다 —
 * 안쪽 상태는 구현 사정으로 바뀌고, 이 이름은 2번(web)·clip과의 약속이다.
 */
public enum CollectionState {
    ESTABLISHING, COLLECTING, RECONNECTING, STOPPED, ENDED, UNKNOWN;

    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * 세션 스냅숏에서 상태를 고른다. DISABLED는 등록부가 자리를 잡고 {@code establishing()}을
     * 찍기 전의 찰나라 「붙는 중」으로 본다 — 이 창구에서 DISABLED는 「꺼짐」이 아니다.
     */
    public static CollectionState of(CollectionStatus.Snapshot snapshot) {
        return switch (snapshot.state()) {
            case DISABLED, ESTABLISHING -> ESTABLISHING;
            case COLLECTING -> COLLECTING;
            case RECONNECTING -> RECONNECTING;
            case STOPPED -> STOPPED;
        };
    }

    /** 스트리머가 치지직 연동을 다시 해야 풀리는가. 허용 목록 — 사유가 늘어도 기본은 false다. */
    public static boolean needsRelink(StopReason reason) {
        return reason == StopReason.SESSION_AUTH_REJECTED
                || reason == StopReason.SUBSCRIBE_REJECTED
                || reason == StopReason.REVOKED;
    }

    /** 메모 표에 적힌 이름으로. 옛 이름·모르는 이름은 false — 모르면 「다시 연동하라」고 하지 않는다. */
    public static boolean needsRelink(String reasonName) {
        if (reasonName == null) {
            return false;
        }
        try {
            return needsRelink(StopReason.valueOf(reasonName));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
