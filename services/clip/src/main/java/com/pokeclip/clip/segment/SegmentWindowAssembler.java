package com.pokeclip.clip.segment;

import java.util.ArrayList;
import java.util.List;

/**
 * 겹침 조회 결과에서 <b>가장 앞의 「연속 uploaded」 구간 하나</b>를 골라 창을 조립한다.
 * 규칙 정본은 PRD 결정 표 「창의 정의(2026-08-24 정정)」.
 *
 * <p>DB도 Spring도 안 쓴다 — 소비자가 둘(편집기 미리보기·렌더 잡)이고 둘 다 이 판정만 필요하다.
 */
public final class SegmentWindowAssembler {

    /** 계약-세그먼트인덱스 3절의 세 상태 중 「지금 볼 수 있음」은 이것 하나뿐이다. */
    private static final String UPLOADED = "uploaded";

    private SegmentWindowAssembler() {
    }

    /**
     * @param overlapping 요청 구간과 겹치는 조각 <b>전부</b>(상태 무관, seq 오름차순)
     */
    public static SegmentWindow assemble(List<StreamSegmentRow> overlapping, long startMs, long endMs) {
        List<StreamSegmentRow> taken = new ArrayList<>();
        for (StreamSegmentRow row : overlapping) {
            if (!UPLOADED.equals(row.uploadState())) {
                // 머리의 pending·failed는 건너뛰고, 취하기 시작한 뒤라면 거기서 끊는다.
                // failed도 「아직 아님」이다 — 계약 3절이 failed → uploaded 역전이를 명시한다.
                if (taken.isEmpty()) {
                    continue;
                }
                break;
            }
            // 연속의 축은 seq다. is_discontinuity(재연결)는 pts에 공백을 만들지만
            // 조각이 빠진 게 아니므로 판정에 안 쓴다.
            if (!taken.isEmpty() && row.seq() != taken.getLast().seq() + 1) {
                break;
            }
            taken.add(row);
        }
        if (taken.isEmpty()) {
            // 아무것도 못 취한 창은 완전일 수 없다. 규칙 5의 식을 그대로 쓰면
            // from=until=startMs라 퇴화 구간(startMs >= endMs)에서 참이 된다(실측).
            return new SegmentWindow(false, startMs, startMs, List.of());
        }
        long availableFromMs = taken.getFirst().startPtsMs();
        long availableUntilMs = taken.getLast().startPtsMs() + taken.getLast().durationMs();
        // startMs < endMs가 규칙 5 앞에 선다 — 퇴화 구간(startMs >= endMs)은 조각이 실려도
        // 완전일 수 없다. 위의 빈 창이 false인 것과 같은 근거이고, 같은 식이 양쪽에 서 있다.
        //
        // 운영 호출자(previewWindow)가 구간을 앞에서 검증하므로 지금은 도달 불가다. 그래도
        // 여기 두는 이유는 이 메서드가 public static이고 소비자가 둘이기 때문이다 —
        // previewWindow는 requesterSubject를 요구해 렌더 잡(POK-125)이 그대로 못 부른다(감사 2차).
        // 새 진입점이 생기면 구간 검증은 안 따라오는데, 렌더 잡은 complete로 발행 여부를 가른다.
        boolean complete = startMs < endMs && availableFromMs <= startMs && availableUntilMs >= endMs;
        return new SegmentWindow(complete, availableFromMs, availableUntilMs, List.copyOf(taken));
    }
}
