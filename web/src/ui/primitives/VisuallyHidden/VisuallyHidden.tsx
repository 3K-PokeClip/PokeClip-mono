import { forwardRef, type ComponentPropsWithoutRef } from 'react';
import clsx from 'clsx';
import styles from './VisuallyHidden.module.css';

export type VisuallyHiddenProps = ComponentPropsWithoutRef<'span'>;

/** Visually hides its children while keeping them accessible to screen readers. */
export const VisuallyHidden = forwardRef<HTMLSpanElement, VisuallyHiddenProps>(
  function VisuallyHidden({ className, ...rest }, ref) {
    return <span ref={ref} className={clsx(styles.root, className)} {...rest} />;
  },
);
