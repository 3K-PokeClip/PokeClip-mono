import { describe, expect, it } from 'vitest';
import { captureStep } from './hotkey';

const key = (code: string, mods: Partial<{ ctrlKey: boolean; altKey: boolean; shiftKey: boolean; metaKey: boolean }> = {}) => ({
  code,
  ctrlKey: false,
  altKey: false,
  shiftKey: false,
  metaKey: false,
  ...mods,
});

describe('captureStep', () => {
  it('글자 키는 조합 키와 함께 OBS 키 이름으로 옮긴다', () => {
    expect(captureStep(key('KeyM', { ctrlKey: true, shiftKey: true }), true)).toEqual({
      kind: 'combo',
      combo: { key: 'OBS_KEY_M', control: true, alt: false, shift: true, command: false },
      label: '⌃⇧M',
    });
    expect(captureStep(key('KeyK', { metaKey: true }), false)).toMatchObject({ label: 'Win+K' });
    expect(captureStep(key('Digit5', { altKey: true }), false)).toMatchObject({ combo: { key: 'OBS_KEY_5' }, label: 'Alt+5' });
  });

  it('F 키·숫자패드·이동 키는 혼자 써도 된다', () => {
    expect(captureStep(key('F9'), false)).toMatchObject({ kind: 'combo', combo: { key: 'OBS_KEY_F9' }, label: 'F9' });
    expect(captureStep(key('Numpad3'), true)).toMatchObject({ combo: { key: 'OBS_KEY_NUM3' }, label: 'Num 3' });
    expect(captureStep(key('Pause'), false)).toMatchObject({ combo: { key: 'OBS_KEY_PAUSE' } });
  });

  it('글자·숫자·기호를 혼자(또는 Shift만) 누르면 거절한다', () => {
    expect(captureStep(key('KeyM'), true)).toEqual({ kind: 'reject', reason: 'hotkey_needs_modifier' });
    expect(captureStep(key('Slash', { shiftKey: true }), true)).toEqual({ kind: 'reject', reason: 'hotkey_needs_modifier' });
    expect(captureStep(key('Space'), false)).toEqual({ kind: 'reject', reason: 'hotkey_needs_modifier' });
  });

  it('조합 키만 누르는 동안은 기다리고, Esc는 취소, 모르는 키는 거절', () => {
    expect(captureStep(key('ShiftLeft', { shiftKey: true }), true)).toEqual({ kind: 'modifiers' });
    expect(captureStep(key('MetaRight', { metaKey: true }), true)).toEqual({ kind: 'modifiers' });
    expect(captureStep(key('Escape'), true)).toEqual({ kind: 'cancel' });
    expect(captureStep(key('Tab', { ctrlKey: true }), true)).toEqual({ kind: 'reject', reason: 'hotkey_bad_key' });
    expect(captureStep(key('F25'), true)).toEqual({ kind: 'reject', reason: 'hotkey_bad_key' });
  });
});
