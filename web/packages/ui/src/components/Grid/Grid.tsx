import { forwardRef, type ComponentPropsWithoutRef, type CSSProperties } from 'react';
import clsx from 'clsx';
import type { SpaceToken } from '../../tokens/space';
import styles from './Grid.module.css';

export interface GridProps extends ComponentPropsWithoutRef<'div'> {
  columns?: number | string;
  rows?: number | string;
  gap?: SpaceToken;
  align?: CSSProperties['alignItems'];
  justify?: CSSProperties['justifyItems'];
}

const track = (v: number | string | undefined) =>
  v === undefined ? undefined : typeof v === 'number' ? `repeat(${v}, minmax(0, 1fr))` : v;

/** CSS grid container with token-driven `gap`. */
export const Grid = forwardRef<HTMLDivElement, GridProps>(function Grid(
  { columns = 1, rows, gap = 4, align, justify, className, style, ...rest },
  ref,
) {
  return (
    <div
      ref={ref}
      className={clsx(styles.grid, className)}
      style={{
        gridTemplateColumns: track(columns),
        gridTemplateRows: track(rows),
        gap: `var(--pc-space-${gap})`,
        alignItems: align,
        justifyItems: justify,
        ...style,
      }}
      {...rest}
    />
  );
});
