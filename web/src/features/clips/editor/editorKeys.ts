// 편집기 단축키맵 — 시안 1d 타임라인 하단 범례(Space · I/O · ←→ · ⌘Z)와 한 몸이다.
// 키 분기와 범례를 따로 적으면 언젠가 한쪽만 바뀌어 "화면에 적힌 키가 안 먹는" 상태가 된다.
// playerKeys.ts와 같은 이유로 키 문자열이 아니라 이벤트를 받는다 —
// 수정자·자동반복 판정이 키맵 안에 있어야 전역 핸들러가 가드를 다시 적지 않는다.

/** 화살표 한 걸음 — 시안 범례의 "1초" */
export const EDITOR_SEEK_STEP_SECONDS = 1;

/** Shift를 누른 화살표 — 시안 범례의 "Shift = 5초" */
export const EDITOR_SEEK_SHIFT_STEP_SECONDS = 5;

/** 편집 의도 — 어느 액션으로 갈지는 호출부(훅)가 정한다 */
export type EditorIntent =
  | { kind: 'togglePlay' }
  /** 상대 이동. 양수가 앞(미래) 방향이다 */
  | { kind: 'seekBy'; seconds: number }
  /** 플레이헤드를 구간 시작점으로 (I) */
  | { kind: 'markIn' }
  /** 플레이헤드를 구간 끝점으로 (O) */
  | { kind: 'markOut' }
  | { kind: 'undo' }
  | { kind: 'redo' };

/** 이벤트에서 실제로 보는 것만 — React 합성 이벤트와 네이티브 KeyboardEvent 둘 다 만족한다 */
export type EditorKeyEvent = {
  key: string;
  /**
   * 물리 글쇠(`KeyI`·`KeyO`·`KeyZ`). 문자 키는 입력기·레이아웃을 타서
   * 한글 입력 상태에서는 key가 `ㅑ`·`ㅐ`·`ㅋ`로 들어온다 — 대상 사용자가
   * 한국어라 그 상태가 기본값에 가깝다. code가 있으면 그쪽을 먼저 믿는다.
   */
  code?: string;
  repeat?: boolean;
  metaKey?: boolean;
  ctrlKey?: boolean;
  altKey?: boolean;
  shiftKey?: boolean;
};

/**
 * 키 이벤트 → 편집 의도. 처리하지 않으면 null (호출부가 preventDefault를 걸지 않게).
 *
 * ⌘Z를 먼저 본다 — 되돌리기만이 수정자를 쓰는 단축키라서, 일반적인
 * "수정자 조합이면 OS 단축키니 넘긴다" 가드보다 앞에 와야 한다.
 */
export function editorIntentForKey(event: EditorKeyEvent): EditorIntent | null {
  // 자동반복은 버린다 — 키를 누르고 있으면 되돌리기가 순식간에 스택을 비워
  // 사용자가 어디까지 돌아갔는지 볼 새도 없이 편집이 사라진다. 시킹도 같은 이유(playerKeys).
  if (event.repeat) return null;

  const hasUndoModifier = (event.metaKey ?? false) || (event.ctrlKey ?? false);
  if (hasUndoModifier && !(event.altKey ?? false) && matchesLetter(event, 'z')) {
    return event.shiftKey ? { kind: 'redo' } : { kind: 'undo' };
  }

  // 나머지 수정자 조합은 OS·브라우저 단축키다 — 가로채면 뒤로 가기 같은 기본 동작을 무력화한다.
  // Shift는 막지 않는다: 화살표의 5초 이동이 Shift를 쓴다.
  if ((event.metaKey ?? false) || (event.ctrlKey ?? false) || (event.altKey ?? false)) return null;

  const step = event.shiftKey ? EDITOR_SEEK_SHIFT_STEP_SECONDS : EDITOR_SEEK_STEP_SECONDS;
  switch (event.key) {
    case ' ':
    case 'Spacebar':
      return { kind: 'togglePlay' };
    case 'ArrowLeft':
      return { kind: 'seekBy', seconds: -step };
    case 'ArrowRight':
      return { kind: 'seekBy', seconds: step };
    default:
      break;
  }
  if (matchesLetter(event, 'i')) return { kind: 'markIn' };
  if (matchesLetter(event, 'o')) return { kind: 'markOut' };
  return null;
}

/** 물리 글쇠를 먼저 보고, 없을 때만 문자로 떨어진다 (테스트·구형 이벤트 대비) */
function matchesLetter(event: EditorKeyEvent, letter: 'i' | 'o' | 'z'): boolean {
  if (event.code !== undefined && event.code !== '') {
    return event.code === `Key${letter.toUpperCase()}`;
  }
  return event.key.toLowerCase() === letter;
}

export interface ShortcutLegendItem {
  keys: string;
  label: string;
}

/** 타임라인 하단 범례 — 위 키맵과 같은 파일에 두어 어긋날 수 없게 한다 (시안 1d) */
export const SHORTCUT_LEGEND: readonly ShortcutLegendItem[] = [
  { keys: 'Space', label: '재생' },
  { keys: 'I / O', label: '시작·끝점' },
  { keys: '← →', label: '1초 · Shift = 5초' },
  { keys: '⌘Z', label: '이전' },
  { keys: '⇧⌘Z', label: '앞으로' },
];
