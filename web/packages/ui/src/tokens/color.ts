/** Semantic color token references. Values live in CSS (theme-*.css); these
 *  are `var()` reference strings for use in inline styles / JS. */
export const color = {
  bg: {
    canvas: 'var(--pc-color-bg-canvas)',
    surface: 'var(--pc-color-bg-surface)',
    surfaceHover: 'var(--pc-color-bg-surface-hover)',
    surfaceRaised: 'var(--pc-color-bg-surface-raised)',
    inset: 'var(--pc-color-bg-inset)',
    overlay: 'var(--pc-color-bg-overlay)',
  },
  text: {
    primary: 'var(--pc-color-text-primary)',
    secondary: 'var(--pc-color-text-secondary)',
    muted: 'var(--pc-color-text-muted)',
    disabled: 'var(--pc-color-text-disabled)',
    onAccent: 'var(--pc-color-text-on-accent)',
    onDanger: 'var(--pc-color-text-on-danger)',
  },
  border: {
    subtle: 'var(--pc-color-border-subtle)',
    default: 'var(--pc-color-border-default)',
    strong: 'var(--pc-color-border-strong)',
  },
  accent: {
    base: 'var(--pc-color-accent)',
    hover: 'var(--pc-color-accent-hover)',
    active: 'var(--pc-color-accent-active)',
    subtle: 'var(--pc-color-accent-subtle)',
    subtleHover: 'var(--pc-color-accent-subtle-hover)',
    text: 'var(--pc-color-accent-text)',
  },
  point: {
    base: 'var(--pc-color-point)',
    hover: 'var(--pc-color-point-hover)',
    active: 'var(--pc-color-point-active)',
    subtle: 'var(--pc-color-point-subtle)',
    text: 'var(--pc-color-point-text)',
  },
  focusRing: 'var(--pc-color-focus-ring)',
  danger: 'var(--pc-color-danger)',
  success: 'var(--pc-color-success)',
  warning: 'var(--pc-color-warning)',
  info: 'var(--pc-color-info)',
} as const;
