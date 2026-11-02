import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { getThemeInitScript, type ThemeInitScriptOptions } from './getThemeInitScript';

// 문자열로 <head>에 들어가는 스크립트를 jsdom에서 그대로 실행해 <html data-theme>을 본다.
function run(options?: ThemeInitScriptOptions) {
  new Function(getThemeInitScript(options))();
  return document.documentElement.dataset.theme;
}

function stubOsLight(light: boolean) {
  vi.stubGlobal(
    'matchMedia',
    vi.fn(() => ({ matches: light })),
  );
}

beforeEach(() => {
  localStorage.clear();
  delete document.documentElement.dataset.theme;
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('getThemeInitScript', () => {
  it('저장값이 없으면 기본값 dark', () => {
    expect(run()).toBe('dark');
  });

  it('저장된 light·dark를 그대로 적용한다', () => {
    localStorage.setItem('pc-theme', 'light');
    expect(run()).toBe('light');
    localStorage.setItem('pc-theme', 'dark');
    expect(run()).toBe('dark');
  });

  it('잘못된 저장값은 기본값으로 — 그대로 data-theme에 넣지 않는다', () => {
    localStorage.setItem('pc-theme', 'sepia');
    expect(run()).toBe('dark');
  });

  it('system은 OS 테마로 푼다', () => {
    localStorage.setItem('pc-theme', 'system');
    stubOsLight(true);
    expect(run()).toBe('light');
    stubOsLight(false);
    expect(run()).toBe('dark');
  });

  it('matchMedia가 없으면 system을 dark로 푼다', () => {
    localStorage.setItem('pc-theme', 'system');
    expect(run()).toBe('dark');
  });

  it('저장소 접근이 막혀도 기본값으로 data-theme을 세운다', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    expect(run()).toBe('dark');
    stubOsLight(true);
    expect(run({ defaultTheme: 'system' })).toBe('light');
  });
});
