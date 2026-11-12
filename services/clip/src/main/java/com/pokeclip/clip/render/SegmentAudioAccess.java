package com.pokeclip.clip.render;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * 원본 조각 주소 한 벌(POK-389). 편집기가 조각을 받아 소리 트랙을 하나씩 꺼내 미리 듣는다.
 *
 * <p>창고 좌표({@code s3Key})는 싣지 않는다: 화면은 주소만 있으면 받는다.
 *
 * <p><b>끝 소리 확보(POK-196 · 설계 r7 9.6).</b> 뿌리 {@code audioCapture} 는 늘 싣고, {@code attached} 일 때만 확보 조각을
 * {@code audioCaptureSegment} 에 따로 싣는다(kty 2026-11-12 K-2). {@code segments} 는 그대로 창과 겹치는 올라간 조각이다 — 확보
 * 조각을 거기 섞지 않아 목록 뜻 · 바이트가 PR-B1 이전과 같다. 빈 칸은 싣지 않는다.
 *
 * @param audioCapture 끝 소리 확보 상태 — {@code attached}(확보 조각을 실음) · {@code not_ready}(아직 준비 안 됨 — 다시 물으면
 *                     바뀔 수 있다) · {@code skipped}(없이 진행). 렌더 주문과 같은 판정이다
 * @param audioCaptureSegment 확보 조각 — 컷 끝 조각(k)의 장부 다음 행. {@code attached} 일 때만
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SegmentAudioAccess(Instant expiresAt, List<Segment> segments, String audioCapture,
                                 Segment audioCaptureSegment) {

    /**
     * @param startAtMs 재생 축 시각(UTC epoch ms). 편집본의 {@code cut}과 같은 축이라 화면이 그대로 맞춘다
     * @param url       미리서명 주소. 조각 파일 하나 전체(모든 소리 트랙)를 준다
     */
    public record Segment(long seq, long startAtMs, int durationMs, String url) {

        Segment withUrl(String signed) {
            return new Segment(seq, startAtMs, durationMs, signed);
        }
    }
}
