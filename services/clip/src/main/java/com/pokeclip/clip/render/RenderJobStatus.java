package com.pokeclip.clip.render;

/** 주문의 상태 넷(계약1 4절 상태머신). PROGRESS·RETRY_SCHEDULED는 {@link #STARTED}를 유지한다. */
public enum RenderJobStatus {
    QUEUED("queued"), STARTED("started"), SUCCEEDED("succeeded"), FAILED("failed");

    private final String dbValue;

    RenderJobStatus(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    public boolean terminal() {
        return this == SUCCEEDED || this == FAILED;
    }

    public static RenderJobStatus fromDbValue(String value) {
        for (RenderJobStatus status : values()) {
            if (status.dbValue.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("모르는 job 상태: " + value);
    }
}
