import type { HotkeyCombo } from './types';

// POK-494 — 독에서 누른 키를 OBS 단축키로 옮긴다. 자판 배치와 무관한 물리 키(KeyboardEvent.code)로 읽는다 —
// 한글 입력 중이어도 M 자리는 OBS_KEY_M이다. 플러그인 mark-policy HotkeyComboRejection과 같은 규칙으로 미리 거른다.
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

type KeyInfo = { name: string; text: string; typing: boolean };

// 글자 키 — macOS libobs는 물리 위치(kVK_ANSI_*)로 묶어 code가 맞다. Windows libobs는 자판 배치를 따르는 가상 키(VK)로
// 묶어, QWERTZ에서 Z라고 적힌 키(물리 KeyY)는 VK_Z다 — Windows는 배치가 반영된 key 글자를 쓴다(2차 리뷰).
// key가 영문 글자가 아니면(한글 입력 「ㅡ」·IME 「Process」 등) code로 — 한국어 두벌식은 QWERTY 배치라 같다.
function keyInfo(code: string, mac: boolean, key?: string): KeyInfo | null {
  let m = /^Key([A-Z])$/.exec(code);
  if (m) {
    const letter = !mac && key && /^[a-zA-Z]$/.test(key) ? key.toUpperCase() : m[1];
    return { name: letter, text: letter, typing: true };
  }
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
  key?: string; // 배치가 반영된 글자(Windows 글자 키에만 쓴다)
  ctrlKey: boolean;
  altKey: boolean;
  shiftKey: boolean;
  metaKey: boolean;
}

export function captureStep(e: KeyLike, mac: boolean): CaptureStep {
  if (e.code === 'Escape' && !e.ctrlKey && !e.altKey && !e.shiftKey && !e.metaKey) return { kind: 'cancel' };
  if (MODIFIER_CODES.test(e.code)) return { kind: 'modifiers' };
  const info = keyInfo(e.code, mac, e.key);
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
