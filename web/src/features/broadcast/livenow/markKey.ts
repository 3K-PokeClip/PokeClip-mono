import { isTypingTarget } from '@/features/player/playerKeys';

// 수동 마킹 핫키(F8) 판정 — ADR-011이 「자동 탐지 신뢰도가 미달인 채널은 핫키 모드」로
// 정해 두어 이 키가 소형 채널의 유일한 하이라이트 경로다.
//
// playerKeys와 같은 이유로 키 문자열이 아니라 이벤트를 받는다: 수정자·자동반복 가드가
// 판정 안에 있어야 호출부마다 다시 적지 않는다. 다만 이쪽은 document 리스너로 듣기 때문에
// 가드가 하나 더 있다 — 입력 중인 곳에서 누른 키는 그쪽 것이다(isTypingTarget).

/** 키 이벤트에서 실제로 보는 것만 — 네이티브 KeyboardEvent와 React 합성 이벤트 둘 다 만족한다 */
export type MarkKeyEvent = {
  key: string;
  repeat?: boolean;
  metaKey?: boolean;
  ctrlKey?: boolean;
  altKey?: boolean;
  shiftKey?: boolean;
};

/** 시안 1b의 마킹 버튼에 박힌 핫키 표기와 같은 값 */
export const MARK_HOTKEY = 'F8';

/**
 * 이 키 입력이 수동 마킹인가.
 *
 * seekIntentForKey와 달리 Shift도 막는다 — 저쪽은 플레이어 안에서만 듣지만 이쪽은
 * document 전역이라, 우리가 모르는 조합까지 삼키면 브라우저·확장 단축키를 가로챈다.
 */
export function isMarkHotkey(event: MarkKeyEvent): boolean {
  if (event.metaKey || event.ctrlKey || event.altKey || event.shiftKey) return false;
  // 자동반복은 버린다 — 누르고 있으면 초당 수십 장의 카드가 생긴다
  if (event.repeat) return false;
  return event.key === MARK_HOTKEY;
}

/**
 * 그 자리에서 누른 마킹 핫키를 양보하나. 플레이어 안 슬라이더(시크바 · 음량)는 글자를 치는 곳이 아니다: 시크바를 누르고 바로 F8을
 * 누르면 포커스가 거기 남아 있다. 전체 화면 말풍선이 F8을 알려 주니 그 흐름에서 키가 죽으면 안 된다
 */
export function blocksMarkHotkey(target: EventTarget | null): boolean {
  if (!isTypingTarget(target)) return false;
  return (target as Element).closest('[data-controls]') === null;
}
