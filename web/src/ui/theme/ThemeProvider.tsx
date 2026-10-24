import { createContext, useCallback, useEffect, useMemo, useState, type ReactNode } from 'react';
import {
  DEFAULT_THEME,
  THEME_STORAGE_KEY,
  isTheme,
  type ResolvedTheme,
  type Theme,
} from './themeConfig';

export type { ResolvedTheme, Theme } from './themeConfig';

export interface ThemeContextValue {
  /** The user's selected preference ('system' follows the OS). */
  theme: Theme;
  /** The concrete theme currently applied to the DOM. */
  resolvedTheme: ResolvedTheme;
  setTheme: (theme: Theme) => void;
}

export const ThemeContext = createContext<ThemeContextValue | null>(null);

function getSystemTheme(): ResolvedTheme {
  if (typeof window === 'undefined' || !window.matchMedia) return 'dark';
  return window.matchMedia('(prefers-color-scheme: light)').matches ? 'light' : 'dark';
}

function readStoredTheme(): Theme | null {
  if (typeof window === 'undefined') return null;
  try {
    const v = window.localStorage.getItem(THEME_STORAGE_KEY);
    if (isTheme(v)) return v;
  } catch {
    /* localStorage may be unavailable (private mode, SSR) */
  }
  return null;
}

export interface ThemeProviderProps {
  children: ReactNode;
  /** Theme used when nothing is stored. Default `'dark'` (dark-first). */
  defaultTheme?: Theme;
  /** Read, persist and cross-tab sync the choice via localStorage. Default `true`. */
  enablePersistence?: boolean;
  /** Element that receives the `data-theme` attribute. Default `document.documentElement`. */
  attributeTarget?: HTMLElement;
}

export function ThemeProvider({
  children,
  defaultTheme = DEFAULT_THEME,
  enablePersistence = true,
  attributeTarget,
}: ThemeProviderProps) {
  const [theme, setThemeState] = useState<Theme>(
    () => (enablePersistence ? readStoredTheme() : null) ?? defaultTheme,
  );
  const [systemTheme, setSystemTheme] = useState<ResolvedTheme>(getSystemTheme);

  const resolvedTheme: ResolvedTheme = theme === 'system' ? systemTheme : theme;

  // Follow OS preference changes while `theme === 'system'`.
  useEffect(() => {
    if (typeof window === 'undefined' || !window.matchMedia) return;
    const mql = window.matchMedia('(prefers-color-scheme: light)');
    const onChange = () => setSystemTheme(mql.matches ? 'light' : 'dark');
    // Safari < 14 only has the deprecated addListener; partial mocks may have neither.
    if (typeof mql.addEventListener === 'function') {
      mql.addEventListener('change', onChange);
      return () => mql.removeEventListener('change', onChange);
    }
    if (typeof mql.addListener === 'function') {
      mql.addListener(onChange);
      return () => mql.removeListener(onChange);
    }
  }, []);

  // Follow changes made in other tabs (the `storage` event never fires in the tab that wrote).
  useEffect(() => {
    if (!enablePersistence) return;
    const onStorage = (e: StorageEvent) => {
      // key === null: the whole storage was cleared.
      if (e.key !== null && e.key !== THEME_STORAGE_KEY) return;
      setThemeState(isTheme(e.newValue) ? e.newValue : defaultTheme);
    };
    window.addEventListener('storage', onStorage);
    return () => window.removeEventListener('storage', onStorage);
  }, [enablePersistence, defaultTheme]);

  // Reflect the resolved theme onto the DOM attribute.
  useEffect(() => {
    const el = attributeTarget ?? document.documentElement;
    el.dataset.theme = resolvedTheme;
  }, [resolvedTheme, attributeTarget]);

  const setTheme = useCallback(
    (next: Theme) => {
      setThemeState(next);
      if (enablePersistence) {
        try {
          window.localStorage.setItem(THEME_STORAGE_KEY, next);
        } catch {
          /* ignore persistence failures — the current tab still switches */
        }
      }
    },
    [enablePersistence],
  );

  const value = useMemo<ThemeContextValue>(
    () => ({ theme, resolvedTheme, setTheme }),
    [theme, resolvedTheme, setTheme],
  );

  return <ThemeContext.Provider value={value}>{children}</ThemeContext.Provider>;
}
