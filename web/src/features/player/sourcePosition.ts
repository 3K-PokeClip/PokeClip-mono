import { useCallback, useRef, useState } from 'react';
import type { LivePosition } from './livePosition';

// 재생기 자리를 그 자리를 잰 재생기의 소스 세대 · 위치 시대와 함께 든다(POK-200 c13). 세션이 소스를 바꾼 그리기에 옛
// 재생기가 마지막으로 잰 값은 새 소스의 자리가 아니다 — 실으면 새 소스가 첫 위치를 내기 전에 앞 소스의 자리로 넘김을 본다.
// 위치 시대는 세션이 재생기를 바꾸거나 안에서 옮길 때마다 오르는 정수다. 잴 때 찍어 두면, 커밋된 위치가 효과로 늦게
// 올라가도 세션이 그사이 시대가 올랐는지 가른다(시스템 시계와 무관).

/** 다음 자리 셈 — 이 재생기가 앞서 잰 값(없으면 null)을 받는다 */
type MeasurePosition = (own: LivePosition | null) => LivePosition | null;

/** 재생 세션이 재생기 자리에 거는 고리(PlaybackHooks 의 몫) */
export interface PositionHooks {
  /** 지금 위치 시대 — 재생기가 자리를 잴 때 찍는다. 없으면 0 */
  positionEpoch?: () => number;
}

interface Measured {
  generation: number;
  epoch: number;
  position: LivePosition;
}

/** 재생기가 내는 자리와 그 자리를 잰 위치 시대 — 지금 소스 세대의 값만 */
interface PositionView {
  livePosition: LivePosition | null;
  positionEpoch: number | null;
}

/**
 * 다음 값. 자리가 그대로면 앞 값(같은 객체 · 처음 잰 시대)을 둔다. 새 재생기는 시작할 때 값을 비우므로(measure(세대, () =>
 * null)) 앞 값은 늘 이 재생기의 것이다
 */
function nextMeasured(
  prev: Measured | null,
  generation: number,
  epoch: number,
  measure: MeasurePosition,
): Measured | null {
  const own = prev?.position ?? null;
  const position = measure(own);
  if (position === null) return null;
  return position === own ? prev : { generation, epoch, position };
}

/**
 * 지금 소스 세대(generation)의 재생기가 잰 자리와, 재생기 하나가 자기 세대로 값을 적는 함수(늘 같은 함수). 위치 시대는 적는
 * 함수를 부른 순간 hooks 에서 읽는다 — 갱신 함수는 그리기 때 늦게 돌 수 있다
 */
export function useSourcePosition(
  generation: number,
  hooks: PositionHooks,
): PositionView & { measure: (by: number, next: MeasurePosition) => void } {
  const [measured, setMeasured] = useState<Measured | null>(null);
  const hooksRef = useRef(hooks);
  hooksRef.current = hooks;
  const measure = useCallback((by: number, next: MeasurePosition) => {
    const epoch = hooksRef.current.positionEpoch?.() ?? 0;
    setMeasured((prev) => nextMeasured(prev, by, epoch, next));
  }, []);
  const current = measured?.generation === generation ? measured : null;
  return {
    livePosition: current?.position ?? null,
    positionEpoch: current?.epoch ?? null,
    measure,
  };
}
