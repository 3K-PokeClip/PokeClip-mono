import {
  Children,
  cloneElement,
  forwardRef,
  isValidElement,
  useState,
  type ComponentPropsWithoutRef,
  type ReactElement,
} from 'react';
import clsx from 'clsx';
import styles from './Avatar.module.css';

export type AvatarSize = 'xs' | 'sm' | 'md' | 'lg' | 'xl';

export interface AvatarProps extends Omit<ComponentPropsWithoutRef<'span'>, 'children'> {
  src?: string;
  alt?: string;
  /** Name used to derive initials when no image is available. */
  name?: string;
  size?: AvatarSize;
}

function initials(name?: string): string {
  if (!name) return '';
  const parts = name.trim().split(/\s+/).filter(Boolean);
  const first = parts[0] ?? '';
  if (parts.length <= 1) return first.slice(0, 2).toUpperCase();
  const last = parts[parts.length - 1] ?? '';
  return (first.charAt(0) + last.charAt(0)).toUpperCase();
}

/** User avatar with graceful image-error fallback to initials. */
export const Avatar = forwardRef<HTMLSpanElement, AvatarProps>(function Avatar(
  { src, alt, name, size = 'md', className, ...rest },
  ref,
) {
  const [errored, setErrored] = useState(false);
  const showImg = Boolean(src) && !errored;
  return (
    <span ref={ref} data-size={size} className={clsx(styles.avatar, className)} {...rest}>
      {showImg ? (
        <img
          className={styles.img}
          src={src}
          alt={alt ?? name ?? ''}
          onError={() => setErrored(true)}
        />
      ) : (
        <span aria-hidden={alt || name ? undefined : true}>{initials(name)}</span>
      )}
    </span>
  );
});

const SIZE_PX: Record<AvatarSize, number> = { xs: 24, sm: 32, md: 40, lg: 56, xl: 80 };
const SIZE_FONT: Record<AvatarSize, number> = { xs: 9, sm: 11, md: 12, lg: 15, xl: 20 };

export interface AvatarGroupProps extends ComponentPropsWithoutRef<'div'> {
  /** Show at most this many avatars, collapsing the rest into a "+N". */
  max?: number;
  size?: AvatarSize;
}

/** Overlapping stack of avatars with optional overflow count. */
export const AvatarGroup = forwardRef<HTMLDivElement, AvatarGroupProps>(function AvatarGroup(
  { max, size = 'md', className, children, ...rest },
  ref,
) {
  const items = Children.toArray(children).filter(isValidElement) as ReactElement<AvatarProps>[];
  const shown = typeof max === 'number' ? items.slice(0, max) : items;
  const overflow = items.length - shown.length;
  return (
    <div ref={ref} className={clsx(styles.group, className)} {...rest}>
      {shown.map((child, i) => cloneElement(child, { key: i, size: child.props.size ?? size }))}
      {overflow > 0 ? (
        <span
          className={styles.more}
          style={{ width: SIZE_PX[size], height: SIZE_PX[size], fontSize: SIZE_FONT[size] }}
        >
          +{overflow}
        </span>
      ) : null}
    </div>
  );
});
