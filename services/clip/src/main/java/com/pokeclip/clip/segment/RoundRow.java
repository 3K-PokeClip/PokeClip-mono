package com.pokeclip.clip.segment;

/**
 * 컷 판정({@link CutPlanner})의 입력 행 — 장부 한 줄을 판정에 쓰는 칸만 담는다(POK-196 · 설계 r7 9.2). 회차 귀속 행이고, 끝 소리
 * 확보 후보(다음 seq 행 — 회차로 자르지 않는다)도 같은 꼴로 받는다.
 *
 * <p><b>{@link RoundRows.Row} 와의 관계.</b> 둘 다 장부 한 줄이지만 쓰는 축이 다르다 — {@code RoundRows.Row} 는 카드 · 마크(재생
 * 위치 {@code start_pts_ms} · 업로드 여부 불리언), 이것은 편집기 출처 컷 · 소리 주소(파일명 시각 · {@code is_discontinuity} · 장부
 * 이웃 · 업로드 상태 문자열 · {@code s3_key})다. 한쪽을 다른 쪽으로 바꾸지 않는다 — 이 꼴은 장부 이웃 표시가 목록을 다 모은 뒤에야
 * 정해지므로 읽는 쪽({@link StreamSegmentReader} · 렌더 주문 읽기)이 직접 만든다.
 *
 * <p>시각은 epoch ms다. {@code wallMs}는 {@code start_wall_utc}(µs)를 ms로 내린 값이고 편집기 출처 축이다. {@code pdtMs}는
 * {@code playback_pdt}(없으면 null)인데 컷 판정은 이 칸을 쓰지 않는다 — 판 2 의 PDT 는 편집기 {@code /get} 과 장 수준 잔차가
 * 있고 판 NULL 의 PDT 는 래칫으로 앞서기 때문이다(경계 표 5판 B6). 판정이 이 칸을 안 본다는 것을 시험이 어긋난 PDT 를 넣어 잰다.
 *
 * <p>{@code followsLedgerPrevious}는 「장부에서 이 행의 바로 앞 행이 목록의 바로 앞 행인가」 다(사이에 비귀속 행이 끼면 거짓 ·
 * 목록 첫 행은 거짓). 만드는 쪽(장부 읽기)이 정한다. 같은 녹화로 이어졌는가(J)는
 * {@code followsLedgerPrevious ∧ ¬isDiscontinuity} 다.
 *
 * <p>{@code s3Key}가 들어 있다 — 사람용 응답에 그대로 실으면 안 된다(주문서 전용).
 *
 * @param uploadState {@code pending} · {@code uploaded} · {@code failed}(계약-세그먼트인덱스 3절)
 * @param timingVersion null(옛 식) 또는 2
 */
public record RoundRow(long seq, long wallMs, Long pdtMs, int durationMs, boolean isDiscontinuity,
                       boolean followsLedgerPrevious, String uploadState, Integer timingVersion, String s3Key) {

    static final String UPLOADED = "uploaded";
    static final String PENDING = "pending";
    static final String FAILED = "failed";

    /** 앞 행과 같은 녹화로 이어졌다(J — 렌더 계획 검사와 같은 식). */
    public boolean continuesPrevious() {
        return followsLedgerPrevious && !isDiscontinuity;
    }

    public boolean uploaded() {
        return UPLOADED.equals(uploadState);
    }

    public boolean v2() {
        return timingVersion != null && timingVersion == 2;
    }
}
