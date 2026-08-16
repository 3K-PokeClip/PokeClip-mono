/** Typography token references. */
export const fontFamily = {
  sans: 'var(--pc-font-sans)',
  mono: 'var(--pc-font-mono)',
} as const;

export const fontSize = {
  '2xs': 'var(--pc-font-size-2xs)',
  xs: 'var(--pc-font-size-xs)',
  sm: 'var(--pc-font-size-sm)',
  md: 'var(--pc-font-size-md)',
  lg: 'var(--pc-font-size-lg)',
  xl: 'var(--pc-font-size-xl)',
  '2xl': 'var(--pc-font-size-2xl)',
  '3xl': 'var(--pc-font-size-3xl)',
  '4xl': 'var(--pc-font-size-4xl)',
} as const;

export const lineHeight = {
  tight: 'var(--pc-line-tight)',
  snug: 'var(--pc-line-snug)',
  normal: 'var(--pc-line-normal)',
  relaxed: 'var(--pc-line-relaxed)',
} as const;

export const fontWeight = {
  regular: 'var(--pc-weight-regular)',
  medium: 'var(--pc-weight-medium)',
  semibold: 'var(--pc-weight-semibold)',
  bold: 'var(--pc-weight-bold)',
} as const;

export type FontSizeToken = keyof typeof fontSize;
export type FontWeightToken = keyof typeof fontWeight;
export type LineHeightToken = keyof typeof lineHeight;
