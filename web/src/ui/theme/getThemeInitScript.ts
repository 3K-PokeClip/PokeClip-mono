import { DEFAULT_THEME, THEME_STORAGE_KEY, type Theme } from './themeConfig';

export interface ThemeInitScriptOptions {
  defaultTheme?: Theme;
  storageKey?: string;
}

/**
 * Returns a self-executing script string to inline in `<head>` BEFORE first
 * paint, so the correct `data-theme` is applied with no flash (FOUC) on load
 * or SSR hydration — the provider then reads the same attribute on mount.
 *
 * Mirrors `ThemeProvider`'s rules (see `themeConfig.ts`): only `light | dark |
 * system` are accepted, anything else falls back to the default, and the
 * applied value is always `light` or `dark` — even when storage access throws
 * or `matchMedia` is missing.
 *
 * @example
 * // Next.js / SSR:
 * <script dangerouslySetInnerHTML={{ __html: getThemeInitScript() }} />
 */
export function getThemeInitScript(options: ThemeInitScriptOptions = {}): string {
  const { defaultTheme = DEFAULT_THEME, storageKey = THEME_STORAGE_KEY } = options;
  const key = JSON.stringify(storageKey);
  const fallback = JSON.stringify(defaultTheme);
  return (
    '(function(){' +
    'var d=document.documentElement,s=null;' +
    'try{s=localStorage.getItem(' +
    key +
    ')}catch(e){}' +
    "if(s!=='light'&&s!=='dark'&&s!=='system')s=" +
    fallback +
    ';' +
    "if(s==='system'){var m=false;" +
    "try{m=!!(window.matchMedia&&window.matchMedia('(prefers-color-scheme: light)').matches)}catch(e){}" +
    "s=m?'light':'dark'}" +
    'd.dataset.theme=s;' +
    '})()'
  );
}
