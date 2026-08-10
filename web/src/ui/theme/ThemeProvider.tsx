import { createContext, useCallback, useEffect, useMemo, useState, type ReactNode } from 'react';

export type Theme = 'light' | 'dark' | 'system';
export type ResolvedTheme = 'light' | 'dark';

export interface ThemeContextValue {
  /** The user's selected preference ('system' follows the OS). */
  theme: Theme;
  /** The concrete theme currently applied to the DOM. */
  resolvedTheme: ResolvedTheme;
  setTheme: (theme: Theme) => void;
}

export const ThemeContext = createContext<ThemeContextValue | null>(null);

const STORAGE_KEY = 'pc-theme';

function getSystemTheme(): ResolvedTheme {
  if (typeof window === 'undefined' || !window.matchMedia) return 'dark';
  return window.matchMedia('(prefers-color-scheme: light)').matches ? 'light' : 'dark';
}

function readStoredTheme(): Theme | null {
  if (typeof window === 'undefined') return null;
  try {
    const v = window.localStorage.getItem(STORAGE_KEY);
    if (v === 'light' || v === 'dark' || v === 'system') return v;
  } catch {
    /* localStorage may be unavailable (private mode, SSR) */
  }
  return null;
}

export interface ThemeProviderProps {
  children: ReactNode;
  /** Theme used when nothing is stored. Default `'dark'` (dark-first). */
  defaultTheme?: Theme;
  /** Persist the user's choice to localStorage. Default `true`. */
  enablePersistence?: boolean;
  /** Element that receives the `data-theme` attribute. Default `document.documentElement`. */
  attributeTarget?: HTMLElement;
}

export function ThemeProvider({
  children,
  defaultTheme = 'dark',
  enablePersistence = true,
  attributeTarget,
}: ThemeProviderProps) {
  const [theme, setThemeState] = useState<Theme>(() => readStoredTheme() ?? defaultTheme);
  const [systemTheme, setSystemTheme] = useState<ResolvedTheme>(getSystemTheme);

  const resolvedTheme: ResolvedTheme = theme === 'system' ? systemTheme : theme;

  // Follow OS preference changes while `theme === 'system'`.
  useEffect(() => {
    if (typeof window === 'undefined' || !window.matchMedia) return;
    const mql = window.matchMedia('(prefers-color-scheme: light)');
    const onChange = () => setSystemTheme(mql.matches ? 'light' : 'dark');
    mql.addEventListener('change', onChange);
    return () => mql.removeEventListener('change', onChange);
  }, []);

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
          window.localStorage.setItem(STORAGE_KEY, next);
        } catch {
          /* ignore persistence failures */
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
