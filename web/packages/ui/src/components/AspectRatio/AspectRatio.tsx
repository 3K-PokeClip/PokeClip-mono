import { forwardRef, type ComponentPropsWithoutRef } from 'react';
import clsx from 'clsx';
import styles from './AspectRatio.module.css';

export interface AspectRatioProps extends ComponentPropsWithoutRef<'div'> {
  /** width / height, e.g. 16/9. */
  ratio?: number;
}

/** Constrains children to a fixed aspect ratio. */
export const AspectRatio = forwardRef<HTMLDivElement, AspectRatioProps>(function AspectRatio(
  { ratio = 16 / 9, className, style, ...rest },
  ref,
) {
  return (
    <div
      ref={ref}
      className={clsx(styles.aspectRatio, className)}
      style={{ aspectRatio: String(ratio), ...style }}
      {...rest}
    />
  );
});
