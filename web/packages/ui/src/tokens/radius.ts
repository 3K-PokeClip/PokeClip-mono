/** Corner radius token references. */
export const radius = {
  none: 'var(--pc-radius-none)',
  xs: 'var(--pc-radius-xs)',
  sm: 'var(--pc-radius-sm)',
  md: 'var(--pc-radius-md)',
  lg: 'var(--pc-radius-lg)',
  xl: 'var(--pc-radius-xl)',
  '2xl': 'var(--pc-radius-2xl)',
  pill: 'var(--pc-radius-pill)',
  circle: 'var(--pc-radius-circle)',
} as const;

export type RadiusToken = keyof typeof radius;
