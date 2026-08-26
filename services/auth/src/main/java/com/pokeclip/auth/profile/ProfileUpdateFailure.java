package com.pokeclip.auth.profile;

/**
 * 응답 본문에 이름 그대로 실린다 — 화면이 이유를 말할 수 있어야 한다(카드 완료 조건).
 * AuthFailure와 정책이 반대다: 그쪽은 전부 401 한 가지로 뭉개 사유를 감추고, 여기는 사용자가
 * 직접 고칠 수 있는 실패라 감출 이익이 없다(StreamKeyExceptionHandler와 같은 계열).
 */
public enum ProfileUpdateFailure {

    NAME_BLANK,
    NAME_TOO_LONG,
    PHOTO_NOT_AN_IMAGE,
    PHOTO_TOO_LARGE,
    PHOTO_STORAGE_DISABLED
}
