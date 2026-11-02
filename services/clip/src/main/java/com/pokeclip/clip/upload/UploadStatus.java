package com.pokeclip.clip.upload;

/**
 * 업로드 한 줄의 상태. {@code failed}와 {@code checking}의 차이가 이 카드의 핵심이다.
 *
 * <ul>
 *   <li>{@code failed}: 유튜브에 영상이 <b>없는 것이 확실하다</b>(이어 올리기를 시작도 못 했거나, 시작했지만 끝나지 않은 것을 확인했다).
 *       자리를 비워 다시 주문할 수 있다</li>
 *   <li>{@code checking}: 올라갔는지 <b>모른다</b>. 자동으로 다시 올리면 채널에 같은 영상이 둘 뜰 수 있어 사람이 확인한다</li>
 * </ul>
 */
public enum UploadStatus {
    QUEUED("queued"), UPLOADING("uploading"), CHECKING("checking"), UPLOADED("uploaded"), FAILED("failed");

    private final String dbValue;

    UploadStatus(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    /** 일꾼이 더 손대지 않는 상태. {@code checking}도 여기다: 사람만 푼다. */
    public boolean settled() {
        return this == CHECKING || this == UPLOADED || this == FAILED;
    }

    static UploadStatus of(String dbValue) {
        for (UploadStatus status : values()) {
            if (status.dbValue.equals(dbValue)) {
                return status;
            }
        }
        throw new IllegalStateException("모르는 업로드 상태: " + dbValue);
    }
}
