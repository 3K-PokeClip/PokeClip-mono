import { forwardRef, type ComponentPropsWithoutRef } from 'react';
import clsx from 'clsx';
import { useFieldControlProps } from '../Field/Field';
import styles from './Textarea.module.css';

export interface TextareaProps extends ComponentPropsWithoutRef<'textarea'> {
  invalid?: boolean;
}

/** Multi-line text field. Auto-wires ARIA when placed inside a `Field`. */
export const Textarea = forwardRef<HTMLTextAreaElement, TextareaProps>(function Textarea(
  { invalid, disabled, className, ...rest },
  ref,
) {
  const field = useFieldControlProps();
  const resolvedInvalid = invalid ?? field['aria-invalid'];
  const resolvedDisabled = disabled ?? field.disabled;
  return (
    <textarea
      ref={ref}
      {...field}
      {...rest}
      data-invalid={resolvedInvalid || undefined}
      aria-invalid={resolvedInvalid || undefined}
      disabled={resolvedDisabled}
      className={clsx(styles.textarea, className)}
    />
  );
});
