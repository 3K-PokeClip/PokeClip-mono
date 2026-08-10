/** Z-index layer token references. */
export const zIndex = {
  hide: 'var(--pc-z-hide)',
  base: 'var(--pc-z-base)',
  docked: 'var(--pc-z-docked)',
  dropdown: 'var(--pc-z-dropdown)',
  sticky: 'var(--pc-z-sticky)',
  banner: 'var(--pc-z-banner)',
  overlay: 'var(--pc-z-overlay)',
  modal: 'var(--pc-z-modal)',
  popover: 'var(--pc-z-popover)',
  toast: 'var(--pc-z-toast)',
  tooltip: 'var(--pc-z-tooltip)',
} as const;

export type ZIndexToken = keyof typeof zIndex;
