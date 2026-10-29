import type { HotkeyCombo } from './types';

// POK-494 — 독에서 누른 키를 OBS 단축키로 옮긴다. 자판 배치와 무관한 물리 키(KeyboardEvent.code)로 읽는다 —
// 한글 입력 중이어도 M 자리는 OBS_KEY_M이다. 플러그인 mark-policy HotkeyComboRejection과 같은 규칙으로 미리 거른다.
const TYPING: Record<string, string> = {
  Space: 'SPACE',
  Minus: 'MINUS',
  Equal: 'EQUAL',
  BracketLeft: 'BRACKETLEFT',
  BracketRight: 'BRACKETRIGHT',
  Semicolon: 'SEMICOLON',
  Quote: 'APOSTROPHE',
  Comma: 'COMMA',
  Period: 'PERIOD',
  Slash: 'SLASH',
  Backslash: 'BACKSLASH',
  Backquote: 'QUOTELEFT',
};
const TYPING_TEXT: Record<string, string> = {
  SPACE: 'Space',
  MINUS: '-',
  EQUAL: '=',
  BRACKETLEFT: '[',
  BRACKETRIGHT: ']',
  SEMICOLON: ';',
  APOSTROPHE: "'",
  COMMA: ',',
  PERIOD: '.',
  SLASH: '/',
  BACKSLASH: '\\',
  QUOTELEFT: '`',
};
const STANDALONE: Record<string, [string, string]> = {
  Insert: ['INSERT', 'Insert'],
  Home: ['HOME', 'Home'],
  End: ['END', 'End'],
  PageUp: ['PAGEUP', 'Page Up'],
  PageDown: ['PAGEDOWN', 'Page Down'],
  Pause: ['PAUSE', 'Pause'],
};
const MODIFIER_CODES = /^(Shift|Control|Alt|Meta|OS)(Left|Right)?$|^CapsLock$|^Fn/;

type KeyInfo = { name: string; text: string; typing: boolean };

function keyInfo(code: string): KeyInfo | null {
  let m = /^Key([A-Z])$/.exec(code);
  if (m) return { name: m[1], text: m[1], typing: true };
  m = /^Digit([0-9])$/.exec(code);
  if (m) return { name: m[1], text: m[1], typing: true };
  m = /^F([0-9]{1,2})$/.exec(code);
  if (m && Number(m[1]) >= 1 && Number(m[1]) <= 24) return { name: `F${m[1]}`, text: `F${m[1]}`, typing: false };
  m = /^Numpad([0-9])$/.exec(code);
  if (m) return { name: `NUM${m[1]}`, text: `Num ${m[1]}`, typing: false };
  if (TYPING[code]) return { name: TYPING[code], text: TYPING_TEXT[TYPING[code]], typing: true };
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
  ctrlKey: boolean;
  altKey: boolean;
  shiftKey: boolean;
  metaKey: boolean;
}

export function captureStep(e: KeyLike, mac: boolean): CaptureStep {
  if (e.code === 'Escape' && !e.ctrlKey && !e.altKey && !e.shiftKey && !e.metaKey) return { kind: 'cancel' };
  if (MODIFIER_CODES.test(e.code)) return { kind: 'modifiers' };
  const info = keyInfo(e.code);
  if (!info) return { kind: 'reject', reason: 'hotkey_bad_key' };
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
