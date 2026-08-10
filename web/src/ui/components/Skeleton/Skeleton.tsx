import { forwardRef, type ComponentPropsWithoutRef } from 'react';
import clsx from 'clsx';
import type { RadiusToken } from '../../tokens/radius';
import styles from './Skeleton.module.css';

export interface SkeletonProps extends ComponentPropsWithoutRef<'span'> {
  width?: number | string;
  height?: number | string;
  radius?: RadiusToken;
  circle?: boolean;
}

const dim = (v: number | string | undefined) =>
  v === undefined ? undefined : typeof v === 'number' ? `${v}px` : v;

/** Placeholder shimmer shown while content loads. */
export const Skeleton = forwardRef<HTMLSpanElement, SkeletonProps>(function Skeleton(
  { width, height = 16, radius = 'sm', circle = false, className, style, ...rest },
  ref,
) {
  return (
    <span
      ref={ref}
      aria-hidden="true"
      className={clsx(styles.skeleton, className)}
      style={{
        width: circle ? dim(height) : (dim(width) ?? '100%'),
        height: dim(height),
        borderRadius: circle ? 'var(--pc-radius-circle)' : `var(--pc-radius-${radius})`,
        ...style,
      }}
      {...rest}
    />
  );
});
