import { describe, expect, it } from 'vitest';
import {
  EDITOR_SEEK_SHIFT_STEP_SECONDS,
  EDITOR_SEEK_STEP_SECONDS,
  SHORTCUT_LEGEND,
  editorIntentForKey,
} from './editorKeys';

describe('editorIntentForKey', () => {
  it('Space는 재생을 토글한다', () => {
    expect(editorIntentForKey({ key: ' ' })).toEqual({ kind: 'togglePlay' });
  });

  it('I·O는 구간 시작·끝점을 찍는다 — 대문자도 같다', () => {
    expect(editorIntentForKey({ key: 'i' })).toEqual({ kind: 'markIn' });
    expect(editorIntentForKey({ key: 'O' })).toEqual({ kind: 'markOut' });
  });

  it('화살표는 1초, Shift를 더하면 5초 움직인다', () => {
    expect(editorIntentForKey({ key: 'ArrowRight' })).toEqual({
      kind: 'seekBy',
      seconds: EDITOR_SEEK_STEP_SECONDS,
    });
    expect(editorIntentForKey({ key: 'ArrowLeft', shiftKey: true })).toEqual({
      kind: 'seekBy',
      seconds: -EDITOR_SEEK_SHIFT_STEP_SECONDS,
    });
  });

  it('⌘Z는 되돌리기, ⇧⌘Z는 다시실행 — Ctrl도 같다', () => {
    expect(editorIntentForKey({ key: 'z', metaKey: true })).toEqual({ kind: 'undo' });
    expect(editorIntentForKey({ key: 'z', metaKey: true, shiftKey: true })).toEqual({
      kind: 'redo',
    });
    expect(editorIntentForKey({ key: 'Z', ctrlKey: true })).toEqual({ kind: 'undo' });
  });

  it('되돌리기 밖의 수정자 조합은 넘긴다 — OS 단축키를 가로채지 않는다', () => {
    expect(editorIntentForKey({ key: 'ArrowLeft', metaKey: true })).toBeNull();
    expect(editorIntentForKey({ key: ' ', ctrlKey: true })).toBeNull();
    expect(editorIntentForKey({ key: 'z', metaKey: true, altKey: true })).toBeNull();
  });

  it('자동반복은 버린다 — 눌러둔 ⌘Z가 스택을 통째로 비우지 않게', () => {
    expect(editorIntentForKey({ key: 'z', metaKey: true, repeat: true })).toBeNull();
    expect(editorIntentForKey({ key: 'ArrowRight', repeat: true })).toBeNull();
  });

  it('한글 입력 상태에서도 I·O·⌘Z가 먹는다 — 물리 글쇠로 본다', () => {
    // 한글 입력기가 켜져 있으면 key가 자모로 온다. 대상 사용자가 한국어라 흔한 상태다.
    expect(editorIntentForKey({ key: 'ㅑ', code: 'KeyI' })).toEqual({ kind: 'markIn' });
    expect(editorIntentForKey({ key: 'ㅐ', code: 'KeyO' })).toEqual({ kind: 'markOut' });
    expect(editorIntentForKey({ key: 'ㅋ', code: 'KeyZ', metaKey: true })).toEqual({
      kind: 'undo',
    });
  });

  it('물리 글쇠가 다르면 글자가 같아도 안 먹는다', () => {
    expect(editorIntentForKey({ key: 'i', code: 'KeyK' })).toBeNull();
  });

  it('모르는 키는 null이다', () => {
    expect(editorIntentForKey({ key: 'q' })).toBeNull();
    expect(editorIntentForKey({ key: 'Enter' })).toBeNull();
  });
});

describe('SHORTCUT_LEGEND', () => {
  it('범례가 키맵이 실제로 처리하는 키를 적는다', () => {
    expect(SHORTCUT_LEGEND.map((item) => item.keys)).toEqual([
      'Space',
      'I / O',
      '← →',
      '⌘Z',
      '⇧⌘Z',
    ]);
  });
});
