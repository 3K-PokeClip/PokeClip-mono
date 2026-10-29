import { useCallback, useState } from 'react';
import type { LivePosition } from './livePosition';

// 재생기 자리를 그 자리를 잰 재생기의 소스 세대 · 잰 시각과 함께 든다(POK-200 c13). 세션이 소스를 바꾼 그리기에 옛 재생기가
// 마지막으로 잰 값은 새 소스의 자리가 아니다 — 실으면 새 소스가 첫 위치를 내기 전에 앞 소스의 자리로 넘김을 본다. 잰 시각은
// 세션이 소스를 바꾸거나 안에서 옮긴 뒤에 잰 위치인지 가르는 데 쓴다(커밋된 위치가 효과로 늦게 올라가도).

/** 다음 자리 셈 — 이 세대가 앞서 잰 값(없으면 null)을 받는다 */
type MeasurePosition = (own: LivePosition | null) => LivePosition | null;

interface Measured {
  generation: number;
  measuredAtMs: number;
  position: LivePosition;
}

/** 재생기가 내는 자리와 그 자리를 잰 시각(epoch ms) — 지금 소스 세대의 값만 */
interface PositionView {
  livePosition: LivePosition | null;
  positionMeasuredAtMs: number | null;
}

/**
 * 앞 값이 다른 세대의 것이면 없던 것으로 보고 잰다. 자리가 그대로면 앞 값(같은 객체 · 처음 잰 시각)을 둔다
 */
function nextMeasured(
  prev: Measured | null,
  generation: number,
  measuredAtMs: number,
  measure: MeasurePosition,
): Measured | null {
  const own = prev?.generation === generation ? prev.position : null;
  const position = measure(own);
  if (position === null) return null;
  return position === own ? prev : { generation, measuredAtMs, position };
}

/**
 * 지금 소스 세대(generation)의 재생기가 잰 자리와, 재생기 하나가 자기 세대로 값을 적는 함수(늘 같은 함수). 잰 시각은 적는
 * 함수를 부른 순간의 시각이다 — 갱신 함수는 그리기 때 늦게 돌 수 있다
 */
export function useSourcePosition(generation: number): PositionView & {
  measure: (by: number, next: MeasurePosition) => void;
} {
  const [measured, setMeasured] = useState<Measured | null>(null);
  const measure = useCallback((by: number, next: MeasurePosition) => {
    const measuredAtMs = Date.now();
    setMeasured((prev) => nextMeasured(prev, by, measuredAtMs, next));
  }, []);
  const current = measured?.generation === generation ? measured : null;
  return {
    livePosition: current?.position ?? null,
    positionMeasuredAtMs: current?.measuredAtMs ?? null,
    measure,
  };
}
