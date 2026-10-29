import { useCallback, useState } from 'react';
import type { LivePosition } from './livePosition';

// 재생기 자리를 그 자리를 잰 재생기의 소스 세대와 함께 든다(POK-200 c13). 세션이 소스를 바꾼 그리기에 옛 재생기가 마지막으로
// 잰 값은 새 소스의 자리가 아니다 — 실으면 새 소스가 첫 위치를 내기 전에 앞 소스의 자리로 넘김을 본다.

/** 다음 자리 셈 — 이 세대가 앞서 잰 값(없으면 null)을 받는다 */
export type MeasurePosition = (own: LivePosition | null) => LivePosition | null;

interface Measured {
  generation: number;
  position: LivePosition;
}

/** 앞 값이 다른 세대의 것이면 없던 것으로 보고 잰다. 자리가 그대로면 앞 값(같은 객체)을 둔다 */
function nextMeasured(
  prev: Measured | null,
  generation: number,
  measure: MeasurePosition,
): Measured | null {
  const own = prev?.generation === generation ? prev.position : null;
  const position = measure(own);
  if (position === null) return null;
  return position === own ? prev : { generation, position };
}

/**
 * 지금 소스 세대(generation)의 재생기가 잰 자리와, 재생기 하나가 자기 세대로 값을 적는 함수(늘 같은 함수)
 */
export function useSourcePosition(generation: number): {
  livePosition: LivePosition | null;
  measure: (by: number, next: MeasurePosition) => void;
} {
  const [measured, setMeasured] = useState<Measured | null>(null);
  const measure = useCallback(
    (by: number, next: MeasurePosition) => setMeasured((prev) => nextMeasured(prev, by, next)),
    [],
  );
  const livePosition = measured?.generation === generation ? measured.position : null;
  return { livePosition, measure };
}
