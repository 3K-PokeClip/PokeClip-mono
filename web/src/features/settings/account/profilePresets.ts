import { OUTPUT_PX } from './cropImage';

// 「사진 대신 기본 아바타」 6종 (디자인 1p ①). 사진을 지우는 자리가 아니라
// 사진 대신 고르는 자리다 — 파괴적 표현을 쓰지 않으려는 시안의 선택이다.

export interface PresetAvatar {
  /** CSS 토큰 이름(`--pc-…`) 또는 리터럴 색. */
  bg: string;
  fg: string;
}

export const PRESET_AVATARS: readonly PresetAvatar[] = [
  { bg: '--pc-color-accent', fg: '#ffffff' },
  { bg: '--pc-color-point', fg: '#ffffff' },
  // 이 하나만 토큰에 없는 색이다 — 시안이 지정한 황토색을 그대로 쓴다
  { bg: '#e6a83c', fg: '#1b1206' },
  { bg: '--pc-color-success', fg: '#ffffff' },
  { bg: '--pc-color-info', fg: '#ffffff' },
  { bg: '--pc-color-bg-inset', fg: '--pc-color-text-primary' },
];

/** DOM에 얹을 색 — 토큰이면 var()로 감싼다. */
export function cssColor(token: string): string {
  return token.startsWith('--') ? `var(${token})` : token;
}

/** 캔버스에 칠할 색 — 토큰이면 지금 테마의 실제 값으로 푼다. */
function resolvedColor(token: string): string {
  if (!token.startsWith('--')) return token;
  const value = getComputedStyle(document.documentElement).getPropertyValue(token).trim();
  return value === '' ? '#000000' : value;
}

/**
 * 고른 기본 아바타를 크롭에 얹을 정사각 그림으로 만든다.
 * 캔버스를 얻지 못하면 `null` — 호출부가 크롭에 들어가지 않는다.
 */
export function presetAvatarDataUrl(preset: PresetAvatar, glyph: string): string | null {
  const canvas = document.createElement('canvas');
  canvas.width = OUTPUT_PX;
  canvas.height = OUTPUT_PX;
  const ctx = canvas.getContext('2d');
  if (!ctx) return null;

  ctx.fillStyle = resolvedColor(preset.bg);
  ctx.fillRect(0, 0, OUTPUT_PX, OUTPUT_PX);
  ctx.fillStyle = resolvedColor(preset.fg);
  ctx.font = `800 ${OUTPUT_PX * 0.42}px ${getComputedStyle(document.documentElement).getPropertyValue('--pc-font-sans') || 'sans-serif'}`;
  ctx.textAlign = 'center';
  ctx.textBaseline = 'middle';
  ctx.fillText(glyph, OUTPUT_PX / 2, OUTPUT_PX / 2);
  return canvas.toDataURL('image/png');
}

/**
 * 표시 이름의 첫 「글자」. 코드 포인트 단위(Array.from)로는 부족하다 — 국기(지역 표시 쌍),
 * 피부색 수식자, ZWJ 가족 이모지는 여러 코드 포인트가 모여 한 글자를 이루므로 첫 것만
 * 떼면 깨진 글자가 그려진다. Intl.Segmenter가 있으면 그래핌 단위로 끊는다.
 */
export function firstGrapheme(name: string): string {
  const text = name.trim();
  if (text === '') return '';
  if (typeof Intl !== 'undefined' && 'Segmenter' in Intl) {
    const [first] = new Intl.Segmenter('ko', { granularity: 'grapheme' }).segment(text);
    if (first !== undefined) return first.segment;
  }
  return Array.from(text)[0] ?? '';
}
