import type { ComponentChildren, JSX } from 'preact';
import styles from './ui.module.css';

type ButtonProps = JSX.HTMLAttributes<HTMLButtonElement> & {
  variant?: 'solid' | 'soft' | 'ghost';
  size?: 'sm' | 'md';
  block?: boolean;
  loading?: boolean;
  disabled?: boolean;
  type?: 'button' | 'submit';
};

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
      {loading ? <span class={styles.spinner} aria-hidden /> : null}
      {children}
    </button>
  );
}

export type Tone = 'neutral' | 'accent' | 'point' | 'success' | 'warning' | 'danger';

export function Badge({ tone = 'neutral', dot, pulse, children }: { tone?: Tone; dot?: boolean; pulse?: boolean; children: ComponentChildren }) {
  return (
    <span class={styles.badge} data-tone={tone}>
      {dot ? <span class={styles.dot} data-pulse={pulse ? '' : undefined} aria-hidden /> : null}
      {children}
    </span>
  );
}
