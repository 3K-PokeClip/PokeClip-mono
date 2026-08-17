/** Motion token references — durations + easings. */
export const duration = {
  instant: 'var(--pc-duration-instant)',
  fast: 'var(--pc-duration-fast)',
  normal: 'var(--pc-duration-normal)',
  slow: 'var(--pc-duration-slow)',
  slower: 'var(--pc-duration-slower)',
} as const;

export const easing = {
  standard: 'var(--pc-ease-standard)',
  decelerate: 'var(--pc-ease-decelerate)',
  accelerate: 'var(--pc-ease-accelerate)',
  emphasized: 'var(--pc-ease-emphasized)',
} as const;

export type DurationToken = keyof typeof duration;
export type EasingToken = keyof typeof easing;
