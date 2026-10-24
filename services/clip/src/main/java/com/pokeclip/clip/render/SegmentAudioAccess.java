package com.pokeclip.clip.render;

import java.time.Instant;
import java.util.List;

/**
 * 원본 조각 주소 한 벌(POK-389). 편집기가 조각을 받아 소리 트랙을 하나씩 꺼내 미리 듣는다.
 *
 * <p>창고 좌표({@code s3Key})는 싣지 않는다: 화면은 주소만 있으면 받는다.
 */
public record SegmentAudioAccess(Instant expiresAt, List<Segment> segments) {

    /**
     * @param startAtMs 재생 축 시각(UTC epoch ms). 편집본의 {@code cut}과 같은 축이라 화면이 그대로 맞춘다
     * @param url       미리서명 주소. 조각 파일 하나 전체(모든 소리 트랙)를 준다
     */
    public record Segment(long seq, long startAtMs, int durationMs, String url) {
    }
}
