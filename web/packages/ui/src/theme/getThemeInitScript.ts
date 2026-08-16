export interface ThemeInitScriptOptions {
  defaultTheme?: 'light' | 'dark' | 'system';
  storageKey?: string;
}

/**
 * Returns a self-executing script string to inline in `<head>` BEFORE first
 * paint, so the correct `data-theme` is applied with no flash (FOUC) on load
 * or SSR hydration — the provider then reads the same attribute on mount.
 *
 * @example
 * // Next.js / SSR:
 * <script dangerouslySetInnerHTML={{ __html: getThemeInitScript() }} />
 */
export function getThemeInitScript(options: ThemeInitScriptOptions = {}): string {
  const { defaultTheme = 'dark', storageKey = 'pc-theme' } = options;
  const key = JSON.stringify(storageKey);
  const fallback = JSON.stringify(defaultTheme);
  return (
    '(function(){try{' +
    'var d=document.documentElement;' +
    'var s=localStorage.getItem(' +
    key +
    ')||' +
    fallback +
    ';' +
    "var m=window.matchMedia('(prefers-color-scheme: light)').matches?'light':'dark';" +
    "d.dataset.theme=(s==='system')?m:s;" +
    '}catch(e){}})()'
  );
}
