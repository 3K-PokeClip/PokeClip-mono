import { forwardRef, type ButtonHTMLAttributes, type ReactNode } from 'react';
import clsx from 'clsx';
import { VisuallyHidden } from '../../primitives/VisuallyHidden';
import styles from './Button.module.css';

export type ButtonVariant = 'solid' | 'soft' | 'outline' | 'ghost' | 'danger';
export type ButtonSize = 'sm' | 'md' | 'lg';

export interface ButtonProps extends Omit<ButtonHTMLAttributes<HTMLButtonElement>, 'className'> {
  variant?: ButtonVariant;
  size?: ButtonSize;
  /** Shows a spinner, blocks clicks, and sets `aria-busy`. */
  loading?: boolean;
  fullWidth?: boolean;
  iconStart?: ReactNode;
  iconEnd?: ReactNode;
  className?: string;
}

/** Primary interactive control. Variants/sizes are driven by `data-*` attributes. */
export const Button = forwardRef<HTMLButtonElement, ButtonProps>(function Button(
  {
    variant = 'solid',
    size = 'md',
    loading = false,
    fullWidth = false,
    disabled,
    iconStart,
    iconEnd,
    type = 'button',
    className,
    children,
    ...rest
  },
  ref,
) {
  const isDisabled = disabled || loading;
  return (
    <button
      ref={ref}
      type={type}
      className={clsx(styles.button, className)}
      data-variant={variant}
      data-size={size}
      data-loading={loading || undefined}
      data-full-width={fullWidth || undefined}
      disabled={isDisabled}
      aria-busy={loading || undefined}
      {...rest}
    >
      {loading ? (
        <span className={styles.spinner} aria-hidden="true">
          <span className={styles.spinnerCircle} />
        </span>
      ) : null}
      {iconStart && !loading ? (
        <span className={styles.icon} aria-hidden="true">
          {iconStart}
        </span>
      ) : null}
      {children != null ? <span className={styles.label}>{children}</span> : null}
      {iconEnd ? (
        <span className={styles.icon} aria-hidden="true">
          {iconEnd}
        </span>
      ) : null}
      {loading ? <VisuallyHidden>로딩 중</VisuallyHidden> : null}
    </button>
  );
});
