package com.pokeclip.clip.segment.api;

import com.pokeclip.clip.segment.SegmentWindow;
import com.pokeclip.clip.segment.StreamSegmentRow;

import java.util.List;

/**
 * 편집기가 받는 창. <b>{@code s3Key}가 없는 것이 이 record의 존재 이유다</b> —
 * {@link SegmentWindow}를 그대로 내보내면 안 되는 유일한 이유이기도 하다.
 *
 * <p>PRD 결정: 영상 출입증(POK-122)이 생기기 전에는 키를 줘도 화면이 그것으로 아무것도 못 하고
 * (버킷이 비공개다) <b>우리 버킷의 이름 규칙만 밖으로 나간다.</b> 화면은 조각을 {@code seq}로
 * 가리키고, 실제 바이트는 출입증이 생긴 뒤 그 문으로 받는다.
 *
 * <p>{@code availableFromMs}·{@code availableUntilMs}는 <b>조각 경계</b>라 요청 구간보다 넓을 수
 * 있다 — 그대로 재생 시작·끝점으로 쓰면 요청보다 긴 영상을 튼다(자르는 것은 호출자 몫).
 */
public record SegmentWindowResponse(boolean complete, long availableFromMs, long availableUntilMs,
                                    List<Item> segments) {

    /**
     * 내부 모델 {@link StreamSegmentRow}의 여섯 칸 중 <b>넷만</b> 나간다.
     * 빠지는 둘은 {@code s3Key}(위 참조)와 {@code uploadState}다 — 뒤엣것은 목록에 실린 조각이
     * 이미 전부 {@code uploaded}라 늘 같은 값이고, 실으면 화면이 그 값으로 무언가를 판단하려 든다.
     *
     * <p>{@code discontinuity}는 판정에 안 쓰이지만 <b>보존이 요구사항이다</b>(PRD 성공 기준 6) —
     * 재연결 지점을 화면이 알아야 그 경계에서 이어 붙이지 않는다.
     */
    public record Item(long seq, long startPtsMs, int durationMs, boolean discontinuity) {
    }

    public static SegmentWindowResponse from(SegmentWindow window) {
        return new SegmentWindowResponse(window.complete(), window.availableFromMs(), window.availableUntilMs(),
                window.segments().stream()
                        .map(row -> new Item(row.seq(), row.startPtsMs(), row.durationMs(), row.discontinuity()))
                        .toList());
    }
}
