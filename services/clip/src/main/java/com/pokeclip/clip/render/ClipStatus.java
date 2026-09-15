package com.pokeclip.clip.render;

/** 완성 영상의 상태 넷. 이름이 2번 화면의 거르기 값이다 — 바꾸면 화면이 같이 바뀐다. 업로드 상태는 POK-220이 더한다. */
public enum ClipStatus {
    QUEUED("queued"), RENDERING("rendering"), RENDERED("rendered"), FAILED("failed");

    private final String dbValue;

    ClipStatus(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    public boolean terminal() {
        return this == RENDERED || this == FAILED;
    }

    public static ClipStatus fromDbValue(String value) {
        for (ClipStatus status : values()) {
            if (status.dbValue.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("모르는 clip 상태: " + value);
    }
}
