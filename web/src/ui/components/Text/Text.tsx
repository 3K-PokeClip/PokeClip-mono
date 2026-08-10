import {
  forwardRef,
  type CSSProperties,
  type ElementType,
  type ForwardedRef,
  type ReactElement,
} from 'react';
import clsx from 'clsx';
import type { PolymorphicProps } from '../../primitives/utils/polymorphic';
import type { FontSizeToken, FontWeightToken } from '../../tokens/typography';
import styles from './Text.module.css';

export type TextTone = 'primary' | 'secondary' | 'muted' | 'accent' | 'danger' | 'success';

export interface TextOwnProps {
  size?: FontSizeToken;
  weight?: FontWeightToken;
  tone?: TextTone;
  align?: CSSProperties['textAlign'];
  truncate?: boolean;
  className?: string;
  style?: CSSProperties;
}

export type TextProps<C extends ElementType = 'span'> = PolymorphicProps<C, TextOwnProps>;

const TONE: Record<TextTone, string> = {
  primary: 'var(--pc-color-text-primary)',
  secondary: 'var(--pc-color-text-secondary)',
  muted: 'var(--pc-color-text-muted)',
  accent: 'var(--pc-color-accent-text)',
  danger: 'var(--pc-color-danger)',
  success: 'var(--pc-color-success)',
};

const WEIGHT: Record<FontWeightToken, number> = {
  regular: 400,
  medium: 500,
  semibold: 600,
  bold: 700,
};

/** Polymorphic typographic primitive. Token-driven size/weight/tone. */
export const Text = forwardRef(function Text<C extends ElementType = 'span'>(
  {
    as,
    size = 'md',
    weight,
    tone = 'primary',
    align,
    truncate = false,
    className,
    style,
    ...rest
  }: TextProps<C>,
  ref: ForwardedRef<Element>,
) {
  const Component = (as ?? 'span') as ElementType;
  return (
    <Component
      ref={ref}
      className={clsx(styles.text, truncate && styles.truncate, className)}
      style={{
        fontSize: `var(--pc-font-size-${size})`,
        fontWeight: weight ? WEIGHT[weight] : undefined,
        color: TONE[tone],
        textAlign: align,
        ...style,
      }}
      {...rest}
    />
  );
}) as <C extends ElementType = 'span'>(
  props: TextProps<C> & { ref?: ForwardedRef<Element> },
) => ReactElement;
