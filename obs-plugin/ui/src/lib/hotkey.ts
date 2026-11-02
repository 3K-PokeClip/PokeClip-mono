import type { HotkeyCombo } from './types';

// POK-494 — 독에서 누른 키를 OBS 단축키로 옮긴다. libobs가 키를 보는 방식대로 읽는다 — macOS는 물리 키(code),
// Windows 글자·기호는 가상 키(keyCode, windowsKeyInfo). 한글 입력 중이어도 M 자리는 OBS_KEY_M이다.
// 플러그인 mark-policy HotkeyComboRejection과 같은 규칙으로 미리 거른다.
// 기호 키는 libobs 키 이름이 플랫폼마다 다르다(OBS 32.2.1 obs-cocoa.m · obs-windows.c 대조) — 엉뚱한 이름을 걸면
// 「바꿨어요」 뒤에도 가상 키가 없어 영영 안 눌린다. [macOS, Windows] 순.
const TYPING: Record<string, [string, string, string]> = {
  Space: ['SPACE', 'SPACE', 'Space'],
  Minus: ['MINUS', 'MINUS', '-'],
  Equal: ['EQUAL', 'PLUS', '='],
  BracketLeft: ['BRACKETLEFT', 'BRACKETLEFT', '['],
  BracketRight: ['BRACKETRIGHT', 'BRACKETRIGHT', ']'],
  Semicolon: ['SEMICOLON', 'SEMICOLON', ';'],
  Quote: ['QUOTE', 'APOSTROPHE', "'"],
  Comma: ['COMMA', 'COMMA', ','],
  Period: ['PERIOD', 'PERIOD', '.'],
  Slash: ['SLASH', 'SLASH', '/'],
  Backslash: ['BACKSLASH', 'BACKSLASH', '\\'],
  Backquote: ['DEAD_GRAVE', 'ASCIITILDE', '`'],
};
const STANDALONE: Record<string, [string, string]> = {
  Insert: ['INSERT', 'Insert'],
  Home: ['HOME', 'Home'],
  End: ['END', 'End'],
  PageUp: ['PAGEUP', 'Page Up'],
  PageDown: ['PAGEDOWN', 'Page Down'],
  Pause: ['PAUSE', 'Pause'],
};
// macOS libobs 표에 없는 키 — 걸어도 안 눌리니 미리 거절한다(플러그인도 가상 키가 없으면 거절한다)
const MAC_MISSING = new Set(['INSERT', 'PAUSE', 'F21', 'F22', 'F23', 'F24']);
const MODIFIER_CODES = /^(Shift|Control|Alt|Meta|OS)(Left|Right)?$|^CapsLock$|^Fn/;

// Windows 기호 키의 가상 키 → libobs 이름(obs-windows.c obs_key_to_virtual_key의 거꾸로)
const WIN_OEM: Record<number, string> = {
  186: 'SEMICOLON', // VK_OEM_1
  187: 'PLUS', // VK_OEM_PLUS
  188: 'COMMA', // VK_OEM_COMMA
  189: 'MINUS', // VK_OEM_MINUS
  190: 'PERIOD', // VK_OEM_PERIOD
  191: 'SLASH', // VK_OEM_2
  192: 'ASCIITILDE', // VK_OEM_3
  219: 'BRACKETLEFT', // VK_OEM_4
  220: 'BACKSLASH', // VK_OEM_5
  221: 'BRACKETRIGHT', // VK_OEM_6
  222: 'APOSTROPHE', // VK_OEM_7
};

type KeyInfo = { name: string; text: string; typing: boolean };

// macOS libobs는 물리 위치(kVK_ANSI_*)로 묶어 code가 맞다. Windows libobs는 자판 배치를 따르는 가상 키(VK)로 묶는다 —
// QWERTZ에서 Z라고 적힌 키(물리 KeyY)는 VK_Z, 독일어 ß 키(물리 Minus)는 VK_OEM_4라 code로 고르면 다른 자리에 걸린다
// (2·3차 리뷰). Windows CEF의 keyCode가 그 가상 키 그대로라 글자·기호는 keyCode로 찾는다. 한글 IME가 키를 쥐면
// keyCode가 229(VK_PROCESSKEY)라 code로 — 한국어 두벌식은 QWERTY 배치라 같다. key(배치가 반영된 글자)는 미리 보기 표기에만.
function windowsKeyInfo(keyCode: number | undefined, key: string | undefined): KeyInfo | null {
  if (keyCode === undefined) return null;
  if (keyCode >= 65 && keyCode <= 90) {
    const letter = String.fromCharCode(keyCode);
    return { name: letter, text: letter, typing: true };
  }
  const name = WIN_OEM[keyCode];
  if (!name) return null;
  const usText = Object.values(TYPING).find((t) => t[1] === name)?.[2] ?? name;
  return { name, text: key && key.length === 1 && key.trim() ? key : usText, typing: true };
}

function keyInfo(code: string, mac: boolean, key?: string, keyCode?: number): KeyInfo | null {
  if (!mac) {
    const win = windowsKeyInfo(keyCode, key);
    if (win) return win;
  }
  let m = /^Key([A-Z])$/.exec(code);
  if (m) return { name: m[1], text: m[1], typing: true };
  m = /^Digit([0-9])$/.exec(code);
  if (m) return { name: m[1], text: m[1], typing: true };
  m = /^F([0-9]{1,2})$/.exec(code);
  if (m && Number(m[1]) >= 1 && Number(m[1]) <= 24) return { name: `F${m[1]}`, text: `F${m[1]}`, typing: false };
  m = /^Numpad([0-9])$/.exec(code);
  if (m) return { name: `NUM${m[1]}`, text: `Num ${m[1]}`, typing: false };
  const typing = TYPING[code];
  if (typing) return { name: mac ? typing[0] : typing[1], text: typing[2], typing: true };
  if (STANDALONE[code]) return { name: STANDALONE[code][0], text: STANDALONE[code][1], typing: false };
  return null;
}

export type CaptureStep =
  | { kind: 'modifiers' } // 조합 키만 누르는 중 — 아직 기다린다
  | { kind: 'cancel' }
  | { kind: 'reject'; reason: 'hotkey_bad_key' | 'hotkey_needs_modifier' }
  | { kind: 'combo'; combo: HotkeyCombo; label: string };

export interface KeyLike {
  code: string;
  key?: string; // 배치가 반영된 글자(Windows 기호 키 미리 보기 표기에만 쓴다)
  keyCode?: number; // Windows 가상 키(VK) — Windows 글자·기호 키 이름을 고른다
  ctrlKey: boolean;
  altKey: boolean;
  shiftKey: boolean;
  metaKey: boolean;
}

export function captureStep(e: KeyLike, mac: boolean): CaptureStep {
  if (e.code === 'Escape' && !e.ctrlKey && !e.altKey && !e.shiftKey && !e.metaKey) return { kind: 'cancel' };
  if (MODIFIER_CODES.test(e.code)) return { kind: 'modifiers' };
  const info = keyInfo(e.code, mac, e.key, e.keyCode);
  if (!info || (mac && MAC_MISSING.has(info.name))) return { kind: 'reject', reason: 'hotkey_bad_key' };
  const combo: HotkeyCombo = {
    key: `OBS_KEY_${info.name}`,
    control: e.ctrlKey,
    alt: e.altKey,
    shift: e.shiftKey,
    command: e.metaKey,
  };
  if (info.typing && !combo.control && !combo.alt && !combo.command) return { kind: 'reject', reason: 'hotkey_needs_modifier' };
  return { kind: 'combo', combo, label: comboLabel(combo, info.text, mac) };
}

// 플러그인 FormatHotkeyLabel과 같은 모양 — macOS ⌃⌥⇧⌘M · 그 밖 Ctrl+Alt+Shift+Win+M
export function comboLabel(c: HotkeyCombo, keyText: string, mac: boolean): string {
  if (mac) return `${c.control ? '⌃' : ''}${c.alt ? '⌥' : ''}${c.shift ? '⇧' : ''}${c.command ? '⌘' : ''}${keyText}`;
  const parts = [c.control && 'Ctrl', c.alt && 'Alt', c.shift && 'Shift', c.command && 'Win', keyText].filter(Boolean);
  return parts.join('+');
}

export function isMacPlatform(): boolean {
  return /Mac/i.test(navigator.platform || navigator.userAgent);
}
