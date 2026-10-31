import { SEEK_PAGE_SECONDS, SEEK_STEP_SECONDS } from './playerMath';

// 시킹 키맵 — 시크바(포커스 시)와 플레이어 전역 단축키가 공유한다 (POK-32).
// 두 곳에 키 분기를 적으면 언젠가 한쪽만 바뀌어 "키보드는 되는데 마우스는 안 되는"
// 계약3 4절 2번이 막으려던 종류의 불일치가 되살아난다.

/** 시크 의도 — 어느 훅으로 갈지는 호출부(PlayerSimulation 계약)가 정한다 */
export type SeekIntent =
  /** 상대 이동. 양수가 과거 방향이다 (PlayerSimulation.seekBy와 같은 부호 규약) */
  | { kind: 'by'; seconds: number }
  /** 창 안의 절대 위치 0..1 — 우측이 라이브 엣지 */
  | { kind: 'toFraction'; fraction: number }
  /** 명시적 라이브 복귀 (계약3 4절 3번) */
  | { kind: 'live' };

/** 키 이벤트에서 실제로 보는 것만 — React 합성 이벤트와 네이티브 KeyboardEvent 둘 다 만족한다 */
export type SeekKeyEvent = {
  key: string;
  repeat?: boolean;
  metaKey?: boolean;
  ctrlKey?: boolean;
  altKey?: boolean;
};

/**
 * 키 이벤트 → 시크 의도. 처리하지 않으면 null (호출부가 preventDefault를 걸지 않게).
 *
 * 키 문자열이 아니라 이벤트를 받는다 — 수정자·자동반복 판정이 키맵 안에 있어야
 * 시크바와 전역 단축키가 함께 고쳐진다. 키만 넘기면 호출부마다 가드를 다시 적어야 한다.
 *
 * WAI-ARIA slider 패턴을 따른다 — Up/Down도 값 조정 키이고, Up = 값 증가 = 라이브 엣지 쪽이다.
 * Home은 "창의 가장 오래된 지점"이라 창 크기와 무관한 toFraction(0)으로 표현한다.
 * 다시보기(replay)의 End는 시크바 오른쪽 끝이다: 라이브 복귀로 보내면 재생 세션의 다시보기가 무시해 키가 죽는다(POK-518).
 */
export function seekIntentForKey(event: SeekKeyEvent, replay = false): SeekIntent | null {
  // 수정자 조합은 OS·브라우저 단축키다 — Cmd+←/Alt+←(뒤로 가기), Ctrl+Home(문서 처음).
  // 가로채면 시킹으로 처리한 뒤 preventDefault까지 걸어 뒤로 가기를 무력화한다.
  // Shift는 막지 않는다 — 플레이어에서 충돌하는 기본 동작이 없다.
  if (event.metaKey || event.ctrlKey || event.altKey) return null;
  // 자동반복은 버린다 — 화살표를 누르고 있으면 초당 수십 번 시크해 LL-HLS가 그때마다
  // 버퍼를 비우고 프래그먼트를 다시 받는다. 드래그를 "놓을 때 한 번만" 커밋하는 것과 같은 이유다.
  if (event.repeat) return null;
  switch (event.key) {
    case 'ArrowLeft':
    case 'ArrowDown':
      return { kind: 'by', seconds: SEEK_STEP_SECONDS };
    case 'ArrowRight':
    case 'ArrowUp':
      return { kind: 'by', seconds: -SEEK_STEP_SECONDS };
    case 'PageDown':
      return { kind: 'by', seconds: SEEK_PAGE_SECONDS };
    case 'PageUp':
      return { kind: 'by', seconds: -SEEK_PAGE_SECONDS };
    case 'Home':
      return { kind: 'toFraction', fraction: 0 };
    case 'End':
      return replay ? { kind: 'toFraction', fraction: 1 } : { kind: 'live' };
    default:
      return null;
  }
}

/** 말풍선 표기와 aria-keyshortcuts 값을 같은 키 목록에서 만든다 */
const keyLabel = (...keys: string[]) => ({ hint: keys.join(' / '), aria: keys.join(' ') });

/** 재생 막대에 알리는 단축키. 키맵과 한곳에 둔다: 따로 적으면 말풍선 · 낭독과 실제 키가 어긋난다(POK-518) */
export const PLAYER_KEY_LABELS = {
  play: keyLabel('Space', 'k'),
  sound: keyLabel('m'),
  fullscreen: keyLabel('f'),
  live: keyLabel('End'),
};

export type PlayerShortcut = 'togglePlay' | 'toggleSound' | 'fullscreen';

/**
 * 키 이벤트 → 재생 막대 단축키. 글자(key)가 아니라 키 자리(code)를 본다: 한글 입력 상태면 m이 「ㅡ」로 와서 글자로 비교하면 듣지
 * 못한다. 페이지 전체에서 듣는 키라 Shift 조합까지 놓아준다(isMarkHotkey와 같은 이유).
 */
export function playerShortcutForKey(
  event: SeekKeyEvent & { code: string; shiftKey?: boolean },
): PlayerShortcut | null {
  if (event.metaKey || event.ctrlKey || event.altKey || event.shiftKey) return null;
  if (event.repeat) return null;
  switch (event.code) {
    case 'Space':
    case 'KeyK':
      return 'togglePlay';
    case 'KeyM':
      return 'toggleSound';
    case 'KeyF':
      return 'fullscreen';
    default:
      return null;
  }
}

/**
 * 글자를 입력하는 중인 곳인가 — 전역 리스너라 제목 수정·검색창까지 닿는다.
 * GlassPlayer의 컨테이너 가드에 textbox를 더한 것이다.
 *
 * contenteditable을 값으로 좁히지 않는 이유 — `<div contenteditable>`(빈 값)과
 * `plaintext-only`도 편집 영역이라 `="true"`로 받으면 그 안에서 누른 키가 가드를 통과한다.
 * 대신 명시적 false만 뺀다.
 */
export function isTypingTarget(target: EventTarget | null): boolean {
  if (!(target instanceof Element)) return false;
  return (
    target.closest(
      'input, select, textarea, [contenteditable]:not([contenteditable="false"]), [role="slider"], [role="textbox"]',
    ) !== null
  );
}

/** 안에서 누른 키가 그쪽 것인 겹창 */
const OVERLAY = '[role="dialog"], [role="alertdialog"], [role="menu"], [role="listbox"]';
/** Space로 누르는 것들 */
const SPACE_ACTIVATES =
  'a[href], button, summary, [role="button"], [role="tab"], [role="checkbox"], [role="radio"], [role="switch"], [role="option"], [role="menuitem"]';

/**
 * 그 자리에서 누른 키가 플레이어 것이 아닌가. 플레이어 안 슬라이더는 입력 칸이 아니라서 듣는다: 시크바를 누르고 바로 Space를 누르거나
 * 음량을 끈 뒤 m을 누르는 흐름이다. 포커스가 있는 버튼의 Space는 그 버튼을 누르는 키다.
 */
export function isForeignKeyTarget(
  target: EventTarget | null,
  code: string,
  player: Element | null,
): boolean {
  if (!(target instanceof Element)) return false;
  if (target.closest(OVERLAY) !== null) return true;
  if (isTypingTarget(target) && !player?.contains(target)) return true;
  return code === 'Space' && target.closest(SPACE_ACTIVATES) !== null;
}
