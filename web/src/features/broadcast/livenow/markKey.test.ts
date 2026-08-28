import { describe, expect, it } from 'vitest';
import { isMarkHotkey, isTypingTarget } from './markKey';

describe('isMarkHotkey — 수동 마킹 핫키', () => {
  it('맨 F8이면 마킹이다', () => {
    expect(isMarkHotkey({ key: 'F8' })).toBe(true);
  });

  it('다른 키는 아니다', () => {
    expect(isMarkHotkey({ key: 'F7' })).toBe(false);
    expect(isMarkHotkey({ key: 'f8' })).toBe(false);
    expect(isMarkHotkey({ key: 'ArrowLeft' })).toBe(false);
  });

  it('수정자 조합은 가로채지 않는다 — document 전역이라 브라우저 단축키와 겹친다', () => {
    for (const modifier of ['metaKey', 'ctrlKey', 'altKey', 'shiftKey'] as const) {
      expect(isMarkHotkey({ key: 'F8', [modifier]: true })).toBe(false);
    }
  });

  it('자동반복은 버린다 — 누르고 있으면 카드가 초당 수십 장 생긴다', () => {
    expect(isMarkHotkey({ key: 'F8', repeat: true })).toBe(false);
  });
});

describe('isTypingTarget — 입력 중인 곳인가', () => {
  function element(html: string): Element {
    const host = document.createElement('div');
    host.innerHTML = html;
    return host.firstElementChild!;
  }

  it('입력 요소에서 누른 키는 그쪽 것이다', () => {
    expect(isTypingTarget(element('<input />'))).toBe(true);
    expect(isTypingTarget(element('<textarea></textarea>'))).toBe(true);
    expect(isTypingTarget(element('<select></select>'))).toBe(true);
    expect(isTypingTarget(element('<div contenteditable="true"></div>'))).toBe(true);
    // 빈 값과 plaintext-only도 편집 영역이다 — ="true"로 좁히면 여기서 누른 키가 새어 나간다
    expect(isTypingTarget(element('<div contenteditable></div>'))).toBe(true);
    expect(isTypingTarget(element('<div contenteditable="plaintext-only"></div>'))).toBe(true);
    // 시크바처럼 키에 자기 동작이 있는 위젯도 같이 막는다
    expect(isTypingTarget(element('<div role="slider"></div>'))).toBe(true);
  });

  it('입력 요소 안쪽에서 올라온 이벤트도 막는다', () => {
    const wrapper = element('<div role="textbox"><span>안</span></div>');
    expect(isTypingTarget(wrapper.querySelector('span'))).toBe(true);
  });

  it('보통 요소·요소가 아닌 타깃은 통과시킨다', () => {
    expect(isTypingTarget(element('<button></button>'))).toBe(false);
    expect(isTypingTarget(element('<div contenteditable="false"></div>'))).toBe(false);
    expect(isTypingTarget(null)).toBe(false);
    expect(isTypingTarget(document)).toBe(false);
  });
});
