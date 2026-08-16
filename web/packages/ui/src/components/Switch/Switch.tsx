import { forwardRef, type ComponentPropsWithoutRef, type ReactNode } from 'react';
import clsx from 'clsx';
import { useFieldControlProps } from '../Field/Field';
import styles from './Switch.module.css';

export interface SwitchProps extends Omit<ComponentPropsWithoutRef<'input'>, 'type' | 'size'> {
  label?: ReactNode;
  size?: 'sm' | 'md';
}

/** Toggle switch (`role="switch"`) with custom visuals over a native input. */
export const Switch = forwardRef<HTMLInputElement, SwitchProps>(function Switch(
  { label, size = 'md', className, disabled, id: idProp, ...rest },
  ref,
) {
  const field = useFieldControlProps();
  const resolvedDisabled = disabled ?? field.disabled;
  return (
    <label
      className={clsx(styles.root, className)}
      data-size={size}
      data-disabled={resolvedDisabled || undefined}
    >
      <input
        ref={ref}
        type="checkbox"
        role="switch"
        id={idProp ?? field.id}
        className={styles.input}
        aria-describedby={field['aria-describedby']}
        disabled={resolvedDisabled}
        {...rest}
      />
      <span className={styles.track} aria-hidden="true">
        <span className={styles.thumb} />
      </span>
      {label != null ? <span className={styles.label}>{label}</span> : null}
    </label>
  );
});
