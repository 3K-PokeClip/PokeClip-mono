/** Elevation shadow token references. */
export const shadow = {
  sm: 'var(--pc-shadow-sm)',
  md: 'var(--pc-shadow-md)',
  lg: 'var(--pc-shadow-lg)',
  xl: 'var(--pc-shadow-xl)',
  glowAccent: 'var(--pc-shadow-glow-accent)',
} as const;

export type ShadowToken = keyof typeof shadow;
