import { useContext } from 'react';
import { ThemeContext, type ThemeContextValue } from './ThemeProvider';

/** Access the current theme and setter. Must be used within a `<ThemeProvider>`. */
export function useTheme(): ThemeContextValue {
  const ctx = useContext(ThemeContext);
  if (!ctx) {
    throw new Error('useTheme must be used within a <ThemeProvider>.');
  }
  return ctx;
}
