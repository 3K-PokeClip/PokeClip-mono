import { forwardRef, type ComponentPropsWithoutRef } from 'react';
import clsx from 'clsx';
import { useFieldControlProps } from '../Field/Field';
import styles from './Input.module.css';

export interface InputProps extends Omit<ComponentPropsWithoutRef<'input'>, 'size'> {
  size?: 'sm' | 'md' | 'lg';
  invalid?: boolean;
}

/** Single-line text field. Auto-wires ARIA when placed inside a `Field`. */
export const Input = forwardRef<HTMLInputElement, InputProps>(function Input(
  { size = 'md', invalid, disabled, className, ...rest },
  ref,
) {
  const field = useFieldControlProps();
  const resolvedInvalid = invalid ?? field['aria-invalid'];
  const resolvedDisabled = disabled ?? field.disabled;
  return (
    <input
      ref={ref}
      {...field}
      {...rest}
      data-size={size}
      data-invalid={resolvedInvalid || undefined}
      aria-invalid={resolvedInvalid || undefined}
      disabled={resolvedDisabled}
      className={clsx(styles.input, className)}
    />
  );
});
