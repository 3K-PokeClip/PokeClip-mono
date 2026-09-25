package com.pokeclip.render.job;

/** 계약1 4절 {@code error.code} 닫힌 목록 중 일꾼이 내는 것. 이름이 그대로 보고에 실린다. */
public enum ErrorCode {
    SCHEMA_VERSION,
    ENVELOPE_VALIDATION,
    VALIDATION,
    SOURCE_MISSING,
    SOURCE_EXPIRED,
    SOURCE_RANGE,
    SOURCE_MISMATCH,
    RESULT_VALIDATION,
    INTERNAL
}
