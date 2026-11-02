// init 스크립트와 ThemeProvider가 같은 기준으로 판정하도록 키·기본값·유효값을 한곳에 둔다.

export type Theme = 'light' | 'dark' | 'system';
export type ResolvedTheme = 'light' | 'dark';

export const THEME_STORAGE_KEY = 'pc-theme';

/** 저장값이 없을 때 — 다크 퍼스트. 라이트는 사용자가 켠다. */
export const DEFAULT_THEME: Theme = 'dark';

export function isTheme(value: unknown): value is Theme {
  return value === 'light' || value === 'dark' || value === 'system';
}
