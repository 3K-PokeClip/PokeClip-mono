import { forwardRef, type ComponentPropsWithoutRef } from 'react';
import clsx from 'clsx';
import styles from './Container.module.css';

const SIZES = { sm: '640px', md: '768px', lg: '1024px', xl: '1280px', full: '100%' } as const;

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
