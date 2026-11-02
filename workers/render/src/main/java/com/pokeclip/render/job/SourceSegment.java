package com.pokeclip.render.job;

/**
 * 주문서 {@code sourceKeys[]} 한 줄. 조각 파일 하나(ADR-071). 조각 하나가 영상 1 + 소리 6트랙을 다 담은 완전한 fMP4다.
 *
 * @param sourceStartAtMs 이 조각 첫 화면의 방송 절대 시각(UTC epoch ms). 레시피 시각을 파일 안 시각으로 바꾸는 다리다
 */
public record SourceSegment(String bucket, String s3Key, long seq, long sourceStartAtMs, long durationMs) {

    public long endAtMs() {
        return sourceStartAtMs + durationMs;
    }
}
