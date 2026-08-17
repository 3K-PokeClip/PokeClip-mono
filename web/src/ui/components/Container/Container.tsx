import { forwardRef, type ComponentPropsWithoutRef } from 'react';
import clsx from 'clsx';
import styles from './Container.module.css';

// 콘텐츠 스케일 유닛(--pc-u) 기반 — 뷰포트에 비례해 넓어진다 (시안 px × u)
const SIZES = {
  sm: 'calc(640 * var(--pc-u))',
  md: 'calc(768 * var(--pc-u))',
  lg: 'calc(1024 * var(--pc-u))',
  xl: 'calc(1280 * var(--pc-u))',
  full: '100%',
} as const;

export interface ContainerProps extends ComponentPropsWithoutRef<'div'> {
  size?: keyof typeof SIZES;
  padded?: boolean;
}

/** Horizontally-centred max-width container. */
export const Container = forwardRef<HTMLDivElement, ContainerProps>(function Container(
  { size = 'lg', padded = true, className, style, ...rest },
  ref,
) {
  return (
    <div
      ref={ref}
      className={clsx(styles.container, className)}
      style={{
        maxWidth: SIZES[size],
        paddingInline: padded ? 'var(--pc-space-4)' : undefined,
        ...style,
      }}
      {...rest}
    />
  );
});
