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
