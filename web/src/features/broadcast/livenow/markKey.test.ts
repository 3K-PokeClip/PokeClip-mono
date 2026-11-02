import { describe, expect, it } from 'vitest';
import { blocksMarkHotkey, isMarkHotkey } from './markKey';

describe('blocksMarkHotkey — 그 자리에서 F8을 양보하나', () => {
  const inside = (html: string, wrapper: string) => {
    const host = document.createElement('div');
    host.innerHTML = wrapper;
    (host.firstElementChild as Element).innerHTML = html;
    return host.querySelector('input, [role="slider"]');
  };

  it('글자를 입력하는 곳이면 양보한다', () => {
    expect(blocksMarkHotkey(inside('<input />', '<div></div>'))).toBe(true);
  });

  it('플레이어 밖 슬라이더도 양보한다', () => {
    expect(blocksMarkHotkey(inside('<div role="slider"></div>', '<div></div>'))).toBe(true);
  });

  it('플레이어 안 슬라이더는 양보하지 않는다 — 시크바를 누르고 바로 F8을 누르는 흐름이다', () => {
    const player = '<div data-controls="visible"></div>';
    expect(blocksMarkHotkey(inside('<div role="slider"></div>', player))).toBe(false);
    expect(blocksMarkHotkey(inside('<input type="range" />', player))).toBe(false);
  });

  it('입력하는 곳이 아니면 양보하지 않는다', () => {
    expect(blocksMarkHotkey(null)).toBe(false);
    expect(blocksMarkHotkey(document.body)).toBe(false);
  });
});

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
