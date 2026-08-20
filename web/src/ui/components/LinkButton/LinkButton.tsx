import { forwardRef, type AnchorHTMLAttributes, type ElementType, type ReactNode } from 'react';
import clsx from 'clsx';
import type { ButtonSize, ButtonVariant } from '../Button';
// Deliberately shares Button's stylesheet: a link-shaped button that drifts from
// the real button is worse than the coupling. Edits to Button.module.css move both.
import styles from '../Button/Button.module.css';

export interface LinkButtonProps extends Omit<
  AnchorHTMLAttributes<HTMLAnchorElement>,
  'className'
> {
  variant?: ButtonVariant;
  size?: ButtonSize;
  fullWidth?: boolean;
  iconStart?: ReactNode;
  iconEnd?: ReactNode;
  /**
   * Anchor component to render. Defaults to `a` so this package stays
   * framework-free — Next apps pass `as={Link}`, Storybook keeps the default.
   */
  as?: ElementType;
  className?: string;
}

/**
 * Navigation styled as a button. Use this instead of `Button` + a router push
 * whenever the action is "go somewhere": only a real anchor keeps middle-click,
 * open-in-new-tab, and navigation without JavaScript.
 *
 * No `disabled`/`loading` counterpart to `Button` — a disabled link is not a
 * thing. Render nothing, or render text, when the destination is unavailable.
 */
export const LinkButton = forwardRef<HTMLAnchorElement, LinkButtonProps>(function LinkButton(
  {
    variant = 'solid',
    size = 'md',
    fullWidth = false,
    iconStart,
    iconEnd,
    as: Anchor = 'a',
    className,
    children,
    ...rest
  },
  ref,
) {
  return (
    <Anchor
      ref={ref}
      className={clsx(styles.button, className)}
      data-variant={variant}
      data-size={size}
      data-full-width={fullWidth || undefined}
      {...rest}
    >
      {iconStart ? (
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
    </Anchor>
  );
});
