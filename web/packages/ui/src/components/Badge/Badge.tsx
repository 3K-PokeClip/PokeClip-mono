import { forwardRef, type ComponentPropsWithoutRef } from 'react';
import clsx from 'clsx';
import styles from './Badge.module.css';

export type BadgeTone = 'neutral' | 'accent' | 'point' | 'success' | 'warning' | 'danger' | 'info';

export interface BadgeProps extends ComponentPropsWithoutRef<'span'> {
  tone?: BadgeTone;
  variant?: 'solid' | 'soft' | 'outline';
  size?: 'sm' | 'md';
}

/** Compact status/label pill. */
export const Badge = forwardRef<HTMLSpanElement, BadgeProps>(function Badge(
  { tone = 'neutral', variant = 'soft', size = 'md', className, ...rest },
  ref,
) {
  return (
    <span
      ref={ref}
      data-tone={tone}
      data-variant={variant}
      data-size={size}
      className={clsx(styles.badge, className)}
      {...rest}
    />
  );
});
