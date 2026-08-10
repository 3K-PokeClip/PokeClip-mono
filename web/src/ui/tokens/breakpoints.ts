/** Responsive breakpoints (px). The one place TS holds raw numeric values —
 *  JS/matchMedia cannot read CSS custom properties directly. */
export const breakpoints = {
  sm: 480,
  md: 768,
  lg: 1024,
  xl: 1280,
  '2xl': 1536,
} as const;

export type Breakpoint = keyof typeof breakpoints;

/** `min-width` media-query string for a breakpoint. */
export function mediaUp(bp: Breakpoint): string {
  return `(min-width: ${breakpoints[bp]}px)`;
}
