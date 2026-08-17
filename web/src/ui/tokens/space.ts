/** Spacing scale token references (4px grid). */
export const space = {
  0: 'var(--pc-space-0)',
  px: 'var(--pc-space-px)',
  1: 'var(--pc-space-1)',
  2: 'var(--pc-space-2)',
  3: 'var(--pc-space-3)',
  4: 'var(--pc-space-4)',
  5: 'var(--pc-space-5)',
  6: 'var(--pc-space-6)',
  8: 'var(--pc-space-8)',
  10: 'var(--pc-space-10)',
  12: 'var(--pc-space-12)',
  16: 'var(--pc-space-16)',
  20: 'var(--pc-space-20)',
  24: 'var(--pc-space-24)',
} as const;

export type SpaceToken = keyof typeof space;
