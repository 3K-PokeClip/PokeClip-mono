import {
  forwardRef,
  useEffect,
  useRef,
  type ComponentPropsWithoutRef,
  type ReactNode,
} from 'react';
import clsx from 'clsx';
import { useComposedRefs } from '../../primitives/hooks/useComposedRefs';
import { useFieldControlProps } from '../Field/Field';
import styles from './Checkbox.module.css';

export interface CheckboxProps extends Omit<ComponentPropsWithoutRef<'input'>, 'type' | 'size'> {
  label?: ReactNode;
  indeterminate?: boolean;
  size?: 'sm' | 'md';
}

/** Checkbox with custom visuals over a real (focusable) native input. */
export const Checkbox = forwardRef<HTMLInputElement, CheckboxProps>(function Checkbox(
  { label, indeterminate = false, size = 'md', className, disabled, id: idProp, ...rest },
  ref,
) {
  const innerRef = useRef<HTMLInputElement>(null);
  const composed = useComposedRefs(ref, innerRef);
  const field = useFieldControlProps();

  useEffect(() => {
    if (innerRef.current) innerRef.current.indeterminate = indeterminate;
  }, [indeterminate]);

  const resolvedDisabled = disabled ?? field.disabled;
  return (
    <label
      className={clsx(styles.root, className)}
      data-size={size}
      data-disabled={resolvedDisabled || undefined}
    >
      <input
        ref={composed}
        type="checkbox"
        id={idProp ?? field.id}
        className={styles.input}
        aria-describedby={field['aria-describedby']}
        aria-invalid={field['aria-invalid']}
        disabled={resolvedDisabled}
        {...rest}
      />
      <span className={styles.box} aria-hidden="true">
        {indeterminate ? (
          <svg viewBox="0 0 16 16">
            <path d="M4 8h8" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
          </svg>
        ) : (
          <svg viewBox="0 0 16 16">
            <path
              d="M3.5 8.5l3 3 6-6.5"
              fill="none"
              stroke="currentColor"
              strokeWidth="2"
              strokeLinecap="round"
              strokeLinejoin="round"
            />
          </svg>
        )}
      </span>
      {label != null ? <span className={styles.label}>{label}</span> : null}
    </label>
  );
});
