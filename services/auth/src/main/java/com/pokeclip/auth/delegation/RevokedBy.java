package com.pokeclip.auth.delegation;

/**
 * 위임을 누가 끊었나. 내보낸 것과 나간 것과 계정이 사라진 것은 다른 사건이다.
 *
 * <p>{@code WITHDRAWAL}은 10글자이고 칸이 {@code VARCHAR(16)}이라 표 변경이 없다.
 * V108의 컬럼 주석은 값 둘만 적고 있다 — 값을 세 개로 늘리는 마이그레이션이 필요 없어
 * 그 주석만 낡았고, 고치려면 주석 한 줄을 위한 마이그레이션이 따로 든다(POK-171에서 보고).
 */
public enum RevokedBy {
    STREAMER, EDITOR, WITHDRAWAL
}
