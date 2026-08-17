import { forwardRef, type ComponentPropsWithoutRef, type CSSProperties } from 'react';
import clsx from 'clsx';
import type { SpaceToken } from '../../tokens/space';
import styles from './Stack.module.css';

export interface StackProps extends ComponentPropsWithoutRef<'div'> {
  direction?: 'row' | 'column';
  gap?: SpaceToken;
  align?: CSSProperties['alignItems'];
  justify?: CSSProperties['justifyContent'];
  wrap?: boolean;
}

/** Flexbox stack. Token-driven `gap`; `HStack`/`VStack` are direction presets. */
export const Stack = forwardRef<HTMLDivElement, StackProps>(function Stack(
  { direction = 'column', gap = 4, align, justify, wrap = false, className, style, ...rest },
  ref,
) {
  return (
    <div
      ref={ref}
      className={clsx(styles.stack, className)}
      style={{
        flexDirection: direction,
        gap: `var(--pc-space-${gap})`,
        alignItems: align,
        justifyContent: justify,
        flexWrap: wrap ? 'wrap' : undefined,
        ...style,
      }}
      {...rest}
    />
  );
});

export const HStack = forwardRef<HTMLDivElement, Omit<StackProps, 'direction'>>(
  function HStack(props, ref) {
    return <Stack ref={ref} direction="row" {...props} />;
  },
);

export const VStack = forwardRef<HTMLDivElement, Omit<StackProps, 'direction'>>(
  function VStack(props, ref) {
    return <Stack ref={ref} direction="column" {...props} />;
  },
);
