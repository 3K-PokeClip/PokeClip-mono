import { forwardRef, type ComponentPropsWithoutRef } from 'react';
import clsx from 'clsx';
import { VisuallyHidden } from '../../primitives/VisuallyHidden';
import styles from './Spinner.module.css';

export interface SpinnerProps extends ComponentPropsWithoutRef<'span'> {
  size?: 'sm' | 'md' | 'lg';
  label?: string;
}

/** Indeterminate loading indicator with an accessible status label. */
export const Spinner = forwardRef<HTMLSpanElement, SpinnerProps>(function Spinner(
  { size = 'md', label = '로딩 중', className, ...rest },
  ref,
) {
  return (
    <span
      ref={ref}
      role="status"
      data-size={size}
      className={clsx(styles.spinner, className)}
      {...rest}
    >
      <span className={styles.circle} aria-hidden="true" />
      <VisuallyHidden>{label}</VisuallyHidden>
    </span>
  );
});
