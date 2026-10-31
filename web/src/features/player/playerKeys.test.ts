import { describe, expect, it } from 'vitest';
import { isTypingTarget, playerShortcutForKey, seekIntentForKey } from './playerKeys';
import { SEEK_PAGE_SECONDS, SEEK_STEP_SECONDS } from './playerMath';

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

describe('playerShortcutForKey — 재생 막대 말풍선에 적힌 단축키(POK-518)', () => {
  it('Space · k는 재생, m은 음소거, f는 전체 화면이다', () => {
    expect(playerShortcutForKey({ key: ' ', code: 'Space' })).toBe('togglePlay');
    expect(playerShortcutForKey({ key: 'k', code: 'KeyK' })).toBe('togglePlay');
    expect(playerShortcutForKey({ key: 'm', code: 'KeyM' })).toBe('toggleSound');
    expect(playerShortcutForKey({ key: 'f', code: 'KeyF' })).toBe('fullscreen');
  });

  it('한글 입력 상태여도 같은 키다 — 글자가 아니라 키 자리를 본다', () => {
    expect(playerShortcutForKey({ key: 'ㅡ', code: 'KeyM' })).toBe('toggleSound');
    expect(playerShortcutForKey({ key: 'ㅏ', code: 'KeyK' })).toBe('togglePlay');
    expect(playerShortcutForKey({ key: 'ㄹ', code: 'KeyF' })).toBe('fullscreen');
  });

  it('수정자 조합과 자동반복은 안 듣는다 — 브라우저 단축키이고, 누르고 있으면 재생이 뒤집히기를 반복한다', () => {
    expect(playerShortcutForKey({ key: 'f', code: 'KeyF', metaKey: true })).toBeNull();
    expect(playerShortcutForKey({ key: 'k', code: 'KeyK', ctrlKey: true })).toBeNull();
    expect(playerShortcutForKey({ key: 'm', code: 'KeyM', altKey: true })).toBeNull();
    expect(playerShortcutForKey({ key: 'M', code: 'KeyM', shiftKey: true })).toBeNull();
    expect(playerShortcutForKey({ key: ' ', code: 'Space', repeat: true })).toBeNull();
  });

  it('다른 키는 처리하지 않는다', () => {
    expect(playerShortcutForKey({ key: 'j', code: 'KeyJ' })).toBeNull();
    expect(playerShortcutForKey({ key: 'Enter', code: 'Enter' })).toBeNull();
  });
});

describe('seekIntentForKey — 시크바와 전역 단축키가 공유하는 키맵', () => {
  it('화살표는 한 걸음 이동하고, 왼쪽/아래가 과거 방향이다', () => {
    const back = { kind: 'by', seconds: SEEK_STEP_SECONDS };
    const forward = { kind: 'by', seconds: -SEEK_STEP_SECONDS };
    expect(seekIntentForKey({ key: 'ArrowLeft' })).toEqual(back);
    expect(seekIntentForKey({ key: 'ArrowDown' })).toEqual(back);
    expect(seekIntentForKey({ key: 'ArrowRight' })).toEqual(forward);
    expect(seekIntentForKey({ key: 'ArrowUp' })).toEqual(forward);
  });

  it('다시보기의 End는 시크바 오른쪽 끝(녹화 끝)이다 — 라이브 복귀는 세션 다시보기가 무시해 키가 죽었다(POK-518)', () => {
    expect(seekIntentForKey({ key: 'End' }, true)).toEqual({ kind: 'toFraction', fraction: 1 });
  });

  it('PageUp/PageDown은 큰 걸음이다', () => {
    expect(seekIntentForKey({ key: 'PageDown' })).toEqual({
      kind: 'by',
      seconds: SEEK_PAGE_SECONDS,
    });
    expect(seekIntentForKey({ key: 'PageUp' })).toEqual({
      kind: 'by',
      seconds: -SEEK_PAGE_SECONDS,
    });
  });

  it('Home은 창의 가장 오래된 지점, End는 라이브 복귀다', () => {
    // 창 크기와 무관해야 하므로 초가 아니라 비율로 표현한다
    expect(seekIntentForKey({ key: 'Home' })).toEqual({ kind: 'toFraction', fraction: 0 });
    expect(seekIntentForKey({ key: 'End' })).toEqual({ kind: 'live' });
  });

  it('처리하지 않는 키는 null이다 — 호출부가 preventDefault를 걸지 않게', () => {
    expect(seekIntentForKey({ key: ' ' })).toBeNull();
    expect(seekIntentForKey({ key: 'Tab' })).toBeNull();
    expect(seekIntentForKey({ key: 'k' })).toBeNull();
  });

  // 회귀: 수정자 조합은 OS·브라우저 단축키다. 가로채면 호출부가 preventDefault까지 걸어
  // 뒤로 가기 자체가 죽는다. 전역 단축키(GlassPlayer)는 영상을 한 번 클릭하기만 하면
  // 활성화되므로, 시크바에 Tab 포커스를 넣는 경로보다 훨씬 자주 밟힌다.
  it('Cmd/Ctrl/Alt 조합은 가로채지 않는다 — 브라우저 뒤로 가기가 살아 있어야 한다', () => {
    expect(seekIntentForKey({ key: 'ArrowLeft', metaKey: true })).toBeNull();
    expect(seekIntentForKey({ key: 'ArrowLeft', ctrlKey: true })).toBeNull();
    expect(seekIntentForKey({ key: 'ArrowLeft', altKey: true })).toBeNull();
    expect(seekIntentForKey({ key: 'Home', ctrlKey: true })).toBeNull();
    expect(seekIntentForKey({ key: 'End', metaKey: true })).toBeNull();
  });

  it('Shift는 막지 않는다 — 플레이어에서 충돌하는 기본 동작이 없다', () => {
    const shiftArrow = { key: 'ArrowLeft', shiftKey: true };
    expect(seekIntentForKey(shiftArrow)).toEqual({ kind: 'by', seconds: SEEK_STEP_SECONDS });
  });

  // 회귀: 키를 누르고 있으면 OS 자동반복이 초당 수십 번 들어온다. 그대로 시킹하면
  // hls.js가 매번 백/포워드 버퍼를 비우고 프래그먼트를 다시 받아 재생이 끊긴다.
  // 드래그를 "놓을 때 한 번만" 커밋한 것과 같은 이유의 방어다.
  it('자동반복(repeat)은 무시한다 — 누르고 있어도 시크가 폭주하지 않는다', () => {
    expect(seekIntentForKey({ key: 'ArrowLeft', repeat: true })).toBeNull();
    expect(seekIntentForKey({ key: 'PageDown', repeat: true })).toBeNull();
    expect(seekIntentForKey({ key: 'End', repeat: true })).toBeNull();
  });
});
