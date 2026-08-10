import { forwardRef, type ComponentPropsWithoutRef } from 'react';
import clsx from 'clsx';
import styles from './Progress.module.css';

export interface ProgressProps extends ComponentPropsWithoutRef<'div'> {
  /** 0..max. `null` renders an indeterminate bar. */
  value?: number | null;
  max?: number;
  size?: 'sm' | 'md';
  label?: string;
}

/** Determinate or indeterminate progress bar. */
export const Progress = forwardRef<HTMLDivElement, ProgressProps>(function Progress(
  { value = null, max = 100, size = 'md', label, className, ...rest },
  ref,
) {
  const indeterminate = value === null;
  const pct = indeterminate ? 0 : Math.min(100, Math.max(0, (value / max) * 100));
  return (
    <div
      ref={ref}
      role="progressbar"
      aria-label={label}
      aria-valuemin={0}
      aria-valuemax={indeterminate ? undefined : max}
      aria-valuenow={indeterminate ? undefined : (value ?? undefined)}
      data-size={size}
      data-indeterminate={indeterminate || undefined}
      className={clsx(styles.track, className)}
      {...rest}
    >
      <div className={styles.fill} style={{ width: indeterminate ? undefined : `${pct}%` }} />
    </div>
  );
});
