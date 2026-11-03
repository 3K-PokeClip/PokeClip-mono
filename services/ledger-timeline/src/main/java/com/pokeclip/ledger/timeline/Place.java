package com.pokeclip.ledger.timeline;

/**
 * F의 결과 — 장부 시각이 매체의 어디에 닿는가(계약 세그먼트인덱스 6-6-6).
 *
 * <p>원점 표지({@code offsetOrigin})는 싣지 않는다. 축을 고른 쪽이 안다 — 편집기 출처는 늘 {@code FILE_HEAD},
 * 판 2 PDT는 늘 {@code VIDEO_START}, 카드 축은 고른 행의 판으로 정한다(설계 r7 3.2 표 · 3.5).
 */
public sealed interface Place {

    /** 조각 {@code seq} 안 {@code offsetMs} 자리. */
    record Media(long seq, long offsetMs) implements Place {
    }

    /**
     * 끊긴 이웃 사이 공백(F 4항). 앞 끝 {@code (beforeSeq, dur)}과 뒤 시작 {@code (afterSeq, 0)}을 함께 준다.
     * 공백을 어떻게 처분할지는 소비자마다 다르다(6-6-6 소비자 표).
     */
    record Gap(long beforeSeq, long afterSeq, long intoGapMs, long gapMs) implements Place {
    }

    /** 첫 행 앞(F 5항 「밖」). */
    record BeforeFirst() implements Place {
    }

    /** 마지막 행 뒤. 「밖」 과 「아직」(뒤 행이 아직 없음)은 가르지 않는다 — 부르는 쪽이 회차 완결 술어로 가른다. */
    record AfterLast() implements Place {
    }
}
