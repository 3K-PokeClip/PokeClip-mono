// 편집기 실행취소·다시실행 스택 (시안 1d 헤더의 ↺ ↻).
// 레시피(ADR-009 — 구간·크롭·트랙·볼륨·자막 스타일·제목)만 히스토리에 담는다.
// 재생 위치·활성 도구·줌 같은 "보는 방식"은 되돌릴 대상이 아니라서 밖에 둔다 —
// 되감기를 실행취소로 되돌리면 사용자가 잃은 편집을 찾지 못한다.
//
// 순수 모듈로 분리한 이유는 timelineMath와 같다: 스택 규칙(다시실행 소거·상한)은
// 화면을 그리지 않고 검증하는 편이 정확하다.

/** 스택 상한 — 넘치면 가장 오래된 것부터 버린다 */
export const HISTORY_LIMIT = 50;

export interface History<T> {
  readonly past: readonly T[];
  readonly present: T;
  readonly future: readonly T[];
}

export function createHistory<T>(present: T): History<T> {
  return { past: [], present, future: [] };
}

/**
 * 새 상태를 쌓는다. **다시실행 더미는 지운다** — 되돌린 뒤 다른 편집을 하면
 * 앞으로 갈 길이 갈라지는데, 남겨두면 ↻가 사용자가 만든 적 없는 상태로 데려간다.
 */
export function pushHistory<T>(history: History<T>, next: T, limit = HISTORY_LIMIT): History<T> {
  const past = [...history.past, history.present];
  return {
    past: past.length > limit ? past.slice(past.length - limit) : past,
    present: next,
    future: [],
  };
}

export function canUndo<T>(history: History<T>): boolean {
  return history.past.length > 0;
}

export function canRedo<T>(history: History<T>): boolean {
  return history.future.length > 0;
}

/** 되돌릴 것이 없으면 같은 객체를 그대로 반환한다 — 호출부가 무해하게 눌러도 리렌더가 없다 */
export function undoHistory<T>(history: History<T>): History<T> {
  const previous = history.past[history.past.length - 1];
  if (previous === undefined) return history;
  return {
    past: history.past.slice(0, -1),
    present: previous,
    future: [history.present, ...history.future],
  };
}

/** redo도 같은 규약 — 앞으로 갈 곳이 없으면 그대로 */
export function redoHistory<T>(history: History<T>): History<T> {
  const next = history.future[0];
  if (next === undefined) return history;
  return {
    past: [...history.past, history.present],
    present: next,
    future: history.future.slice(1),
  };
}
