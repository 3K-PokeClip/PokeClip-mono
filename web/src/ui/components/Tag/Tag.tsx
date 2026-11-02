import { forwardRef, type ComponentPropsWithoutRef } from 'react';
import clsx from 'clsx';
import styles from './Tag.module.css';

export interface TagProps extends ComponentPropsWithoutRef<'span'> {
  variant?: 'soft' | 'outline' | 'solid';
  size?: 'sm' | 'md';
  /** When provided, renders a remove button. */
  onRemove?: () => void;
  removeLabel?: string;
  /** Tab order of the remove button — pass -1 when the parent moves focus between chips with arrow keys. */
  removeTabIndex?: number;
}

/** Category / filter chip, optionally removable. */
export const Tag = forwardRef<HTMLSpanElement, TagProps>(function Tag(
  {
    variant = 'soft',
    size = 'md',
    onRemove,
    removeLabel = '제거',
    removeTabIndex,
    className,
    children,
    ...rest
  },
  ref,
) {
  return (
    <span
      ref={ref}
      data-variant={variant}
      data-size={size}
      className={clsx(styles.tag, className)}
      {...rest}
    >
      <span>{children}</span>
      {onRemove ? (
        <button
          type="button"
          className={styles.remove}
          aria-label={removeLabel}
          tabIndex={removeTabIndex}
          onClick={onRemove}
        >
          <svg width="12" height="12" viewBox="0 0 12 12" aria-hidden="true">
            <path
              d="M3 3l6 6M9 3l-6 6"
              stroke="currentColor"
              strokeWidth="1.6"
              strokeLinecap="round"
            />
          </svg>
        </button>
      ) : null}
    </span>
  );
});
