package com.pokeclip.render.job;

/**
 * 주문서 {@code sourceKeys[]} 한 줄. 조각 파일 하나(ADR-071). 조각 하나가 영상 1 + 소리 6트랙을 다 담은 완전한 fMP4다.
 *
 * <p>옛 꼴(계획 없음)은 {@code segment} 역할만 있고 {@code sourceStartAtMs}가 늘 있으며 두 표지는 거짓이다. 새 꼴(계획
 * 있음 · PDT 설계 r7 5.2)은 {@code sourceStartAtMs}가 없고 역할이 셋이다.
 *
 * @param sourceStartAtMs       이 조각 첫 화면의 방송 절대 시각(UTC epoch ms). 옛 꼴에서 레시피 시각을 파일 안 시각으로 바꾸는
 *                              다리다. 새 꼴은 null
 * @param isDiscontinuity       장부 {@code is_discontinuity} — 앞 seq 조각과 같은 녹화기가 바로 이어 쓰지 않았다
 * @param followsLedgerPrevious 장부에서 이 조각의 바로 앞 행이 주문의 바로 앞 매체인가(준비 조각은 늘 거짓)
 */
public record SourceSegment(String bucket, String s3Key, SourceRef ref, Long sourceStartAtMs, long durationMs,
                            boolean isDiscontinuity, boolean followsLedgerPrevious) {

    /** 옛 꼴 한 줄. */
    public static SourceSegment legacy(String bucket, String s3Key, long seq, long sourceStartAtMs, long durationMs) {
        return new SourceSegment(bucket, s3Key, new SourceRef(seq, SourceRole.SEGMENT, null), sourceStartAtMs,
                durationMs, false, false);
    }

    public long seq() {
        return ref.seq();
    }

    /** 옛 꼴에서만 쓴다. */
    public long endAtMs() {
        return sourceStartAtMs + durationMs;
    }

    /** 회차 이웃 이어짐 J(r6 4.4) — 앞 매체와 같은 소리 묶음으로 잇는가. */
    public boolean joinsPrevious() {
        return followsLedgerPrevious && !isDiscontinuity;
    }
}
