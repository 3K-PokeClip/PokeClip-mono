// ===== Theme runtime =====
export { ThemeProvider, ThemeContext } from './theme/ThemeProvider';
export type {
  Theme,
  ResolvedTheme,
  ThemeContextValue,
  ThemeProviderProps,
} from './theme/ThemeProvider';
export { useTheme } from './theme/useTheme';
export { getThemeInitScript, type ThemeInitScriptOptions } from './theme/getThemeInitScript';

// ===== Design token references =====
export * from './tokens';

// ===== Accessibility primitives (hooks + headless utilities) =====
export * from './primitives';

// ===== Components =====
export * from './components';
