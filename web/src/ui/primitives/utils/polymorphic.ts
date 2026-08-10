import type { ComponentPropsWithoutRef, ElementType, ReactNode } from 'react';

/** Props for a polymorphic component that can render as any element via `as`. */
export type PolymorphicProps<C extends ElementType, Own = object> = Own & {
  as?: C;
  children?: ReactNode;
} & Omit<ComponentPropsWithoutRef<C>, 'as' | 'children' | keyof Own>;
