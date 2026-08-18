package com.pokeclip.clip.broadcast;

/**
 * 10_데이터플로우가 정한 값 셋. ADR-016의 INITIAL은 여기 없다 —
 * 그것은 "행이 아직 없음"이지 저장되는 값이 아니다.
 */
public enum BroadcastStatus {
    LIVE("live"),
    ENDED("ended"),
    /** 이번 범위에서 쓰지 않는다. VOD 확정 처리가 붙을 때 쓴다. */
    VOD_READY("vod_ready");

    private final String dbValue;

    BroadcastStatus(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    public static BroadcastStatus fromDbValue(String value) {
        for (BroadcastStatus status : values()) {
            if (status.dbValue.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("모르는 방송 상태: " + value);
    }
}
