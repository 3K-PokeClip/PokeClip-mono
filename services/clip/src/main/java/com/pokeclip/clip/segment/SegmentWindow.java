package com.pokeclip.clip.segment;

import java.util.List;

/**
 * 요청 구간에 대해 <b>지금 실제로 볼 수 있는 것</b>이 무엇인가에 대한 답.
 * 정의는 PRD 결정 표 「창의 정의(2026-08-24 정정)」다.
 *
 * <p>{@code segments}는 겹친 조각 전부가 아니라 <b>가장 앞의 「연속 uploaded」 구간 하나</b>다 —
 * 가운데가 빈 목록을 주면 화면이 그것을 이어 붙여 영상이 튄다.
 *
 * <p>{@code availableFromMs}·{@code availableUntilMs}는 <b>조각 경계</b>라 요청 구간보다
 * 넓을 수 있다. 요청 {@code [5000,9000)}에 조각 {@code [4000,8000)+[8000,12000)}가 걸리면
 * 각각 4000·12000이다 — 그대로 재생 시작·끝점으로 쓰면 안 된다(자르는 것은 호출자 몫).
 *
 * <p>{@code segments}에는 {@code s3Key}가 들어 있다(렌더 소비자 몫) — 사람용 응답에는
 * 그대로 실으면 안 된다.
 */
public record SegmentWindow(boolean complete, long availableFromMs, long availableUntilMs,
                            List<StreamSegmentRow> segments) {
}
