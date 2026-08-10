import { forwardRef, type ComponentPropsWithoutRef } from 'react';
import clsx from 'clsx';
import type { SpaceToken } from '../../tokens/space';
import styles from './Card.module.css';

export interface CardProps extends ComponentPropsWithoutRef<'div'> {
  variant?: 'surface' | 'outline' | 'ghost';
  padding?: SpaceToken;
  interactive?: boolean;
}

/** Surface container for grouped content. */
export const Card = forwardRef<HTMLDivElement, CardProps>(function Card(
  { variant = 'surface', padding = 4, interactive = false, className, style, ...rest },
  ref,
) {
  return (
    <div
      ref={ref}
      data-variant={variant}
      data-interactive={interactive || undefined}
      className={clsx(styles.card, className)}
      style={{ padding: `var(--pc-space-${padding})`, ...style }}
      {...rest}
    />
  );
});
