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

  it('기호 키는 플랫폼별 OBS 이름으로 보낸다 — 이름이 틀리면 저장돼도 안 눌린다', () => {
    const ctrl = { ctrlKey: true };
    expect(captureStep(key('Quote', ctrl), true)).toMatchObject({ combo: { key: 'OBS_KEY_QUOTE' }, label: "⌃'" });
    expect(captureStep(key('Quote', ctrl), false)).toMatchObject({ combo: { key: 'OBS_KEY_APOSTROPHE' }, label: "Ctrl+'" });
    expect(captureStep(key('Backquote', ctrl), true)).toMatchObject({ combo: { key: 'OBS_KEY_DEAD_GRAVE' } });
    expect(captureStep(key('Backquote', ctrl), false)).toMatchObject({ combo: { key: 'OBS_KEY_ASCIITILDE' } });
    expect(captureStep(key('Equal', ctrl), true)).toMatchObject({ combo: { key: 'OBS_KEY_EQUAL' } });
    expect(captureStep(key('Equal', ctrl), false)).toMatchObject({ combo: { key: 'OBS_KEY_PLUS' }, label: 'Ctrl+=' });
  });

  it('Windows 글자 키는 가상 키(keyCode)를 따르고, macOS는 물리 위치(code)를 따른다', () => {
    // 독일어 QWERTZ — Z라고 적힌 키는 물리 KeyY. Windows libobs는 VK_Z로 묶으니 OBS_KEY_Z
    const qwertzZ = { ...key('KeyY', { ctrlKey: true }), key: 'z', keyCode: 90 };
    expect(captureStep(qwertzZ, false)).toMatchObject({ combo: { key: 'OBS_KEY_Z' }, label: 'Ctrl+Z' });
    expect(captureStep(qwertzZ, true)).toMatchObject({ combo: { key: 'OBS_KEY_Y' } });
    // 프랑스어 AZERTY — M은 물리 Semicolon 자리지만 VK_M
    const azertyM = { ...key('Semicolon', { ctrlKey: true }), key: 'm', keyCode: 77 };
    expect(captureStep(azertyM, false)).toMatchObject({ combo: { key: 'OBS_KEY_M' }, label: 'Ctrl+M' });
    expect(captureStep(azertyM, true)).toMatchObject({ combo: { key: 'OBS_KEY_SEMICOLON' } });
    // 한글 IME가 키를 쥐면 keyCode 229 — code(QWERTY 자리)로
    expect(captureStep({ ...key('KeyM', { ctrlKey: true, shiftKey: true }), key: 'ㅡ', keyCode: 229 }, false)).toMatchObject({
      combo: { key: 'OBS_KEY_M' },
    });
    expect(captureStep({ ...key('KeyM', { ctrlKey: true }), key: 'Process', keyCode: 229 }, false)).toMatchObject({
      combo: { key: 'OBS_KEY_M' },
    });
  });

  it('Windows 기호 키는 가상 키(VK_OEM_*)로 이름을 고른다 — 물리 자리로 고르면 다른 배치에서 다른 키에 걸린다', () => {
    // 독일어 — ß 키는 물리 Minus 자리지만 VK_OEM_4, 「-」 키는 물리 Slash 자리지만 VK_OEM_MINUS
    const sharpS = { ...key('Minus', { ctrlKey: true }), key: 'ß', keyCode: 219 };
    expect(captureStep(sharpS, false)).toMatchObject({ combo: { key: 'OBS_KEY_BRACKETLEFT' }, label: 'Ctrl+ß' });
    expect(captureStep(sharpS, true)).toMatchObject({ combo: { key: 'OBS_KEY_MINUS' } });
    expect(captureStep({ ...key('Slash', { ctrlKey: true }), key: '-', keyCode: 189 }, false)).toMatchObject({
      combo: { key: 'OBS_KEY_MINUS' },
      label: 'Ctrl+-',
    });
    // US — 배치와 자리가 같다. 표기는 US 글자
    expect(captureStep({ ...key('Equal', { ctrlKey: true }), key: '=', keyCode: 187 }, false)).toMatchObject({
      combo: { key: 'OBS_KEY_PLUS' },
      label: 'Ctrl+=',
    });
    // 기호도 Shift만으로는 거절
    expect(captureStep({ ...key('Minus', { shiftKey: true }), key: '?', keyCode: 219 }, false)).toEqual({
      kind: 'reject',
      reason: 'hotkey_needs_modifier',
    });
  });

  it('macOS libobs에 없는 키(Insert·Pause·F21~F24)는 macOS에서만 거절한다', () => {
    for (const code of ['Insert', 'Pause', 'F21', 'F24']) {
      expect(captureStep(key(code), true)).toEqual({ kind: 'reject', reason: 'hotkey_bad_key' });
      expect(captureStep(key(code), false)).toMatchObject({ kind: 'combo' });
    }
  });

  it('조합 키만 누르는 동안은 기다리고, Esc는 취소, 모르는 키는 거절', () => {
    expect(captureStep(key('ShiftLeft', { shiftKey: true }), true)).toEqual({ kind: 'modifiers' });
    expect(captureStep(key('MetaRight', { metaKey: true }), true)).toEqual({ kind: 'modifiers' });
    expect(captureStep(key('Escape'), true)).toEqual({ kind: 'cancel' });
    expect(captureStep(key('Tab', { ctrlKey: true }), true)).toEqual({ kind: 'reject', reason: 'hotkey_bad_key' });
    expect(captureStep(key('F25'), true)).toEqual({ kind: 'reject', reason: 'hotkey_bad_key' });
  });
});
