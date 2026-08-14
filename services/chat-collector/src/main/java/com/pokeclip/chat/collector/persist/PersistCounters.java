package com.pokeclip.chat.collector.persist;

/**
 * 적재 관측 카운터 셋. 요약·판정 줄이 이 묶음으로 받는다 —
 * {@code LongSupplier} 셋을 나열하면 자리바꿈 실수를 컴파일러가 못 잡는다.
 *
 * <p>버퍼 상한 드롭({@code dropped})은 여기 없다 — 그 카운터의 소유는
 * {@link ChatBuffer}다(상한 초과 전용).
 */
public interface PersistCounters {

    /** 표에 저장된 행 수. */
    long persistedCount();

    /** 지문 충돌로 접힌 건수 — 재연결 중복이면 정상, 도배 병합이면 유실. */
    long conflictedCount();

    /** 영구 데이터 오류로 격리 폐기한 건수(NUL 본문 등). */
    long poisonedCount();
}
