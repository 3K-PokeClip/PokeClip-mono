import type { JSX } from 'preact';
import styles from './ui.module.css';

type ButtonProps = JSX.HTMLAttributes<HTMLButtonElement> & {
  variant?: 'solid' | 'soft' | 'ghost';
  size?: 'sm' | 'md';
  block?: boolean;
  loading?: boolean;
  disabled?: boolean;
  type?: 'button' | 'submit';
};

// web ui/components/Button 규칙을 독 크기(md 36px · sm 30px)로 옮긴 것
export function Button({ variant = 'solid', size = 'md', block, loading, disabled, children, type = 'button', ...rest }: ButtonProps) {
  return (
    <button
      {...rest}
      type={type}
      class={styles.button}
      data-variant={variant}
      data-size={size}
      data-block={block ? '' : undefined}
      disabled={disabled || loading}
      aria-busy={loading ? 'true' : undefined}
    >
      {loading ? <span class={styles.spinner} aria-hidden="true" /> : null}
      {children}
    </button>
  );
}
