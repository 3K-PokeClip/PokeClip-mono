import { forwardRef, type ElementType, type ForwardedRef, type ReactElement } from 'react';
import type { PolymorphicProps } from '../../primitives/utils/polymorphic';

export type BoxProps<C extends ElementType = 'div'> = PolymorphicProps<C>;

/** Polymorphic layout primitive. Renders a `div` by default; use `as` to change the tag. */
export const Box = forwardRef(function Box<C extends ElementType = 'div'>(
  { as, ...rest }: BoxProps<C>,
  ref: ForwardedRef<Element>,
) {
  const Component = (as ?? 'div') as ElementType;
  return <Component ref={ref} {...rest} />;
}) as <C extends ElementType = 'div'>(
  props: BoxProps<C> & { ref?: ForwardedRef<Element> },
) => ReactElement;
