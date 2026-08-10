import {
  createContext,
  forwardRef,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ComponentPropsWithoutRef,
} from 'react';
import clsx from 'clsx';
import { useId } from '../../primitives/hooks/useId';
import styles from './Field.module.css';

interface FieldContextValue {
  id: string;
  descriptionId: string;
  errorId: string;
  invalid: boolean;
  required: boolean;
  disabled: boolean;
  describedBy: string | undefined;
  registerDescription: (present: boolean) => void;
  registerError: (present: boolean) => void;
}

const FieldContext = createContext<FieldContextValue | null>(null);

export function useFieldContext(): FieldContextValue | null {
  return useContext(FieldContext);
}

export interface FieldControlProps {
  id?: string;
  'aria-describedby'?: string;
  'aria-invalid'?: true;
  'aria-required'?: true;
  disabled?: true;
}

/** Props a form control spreads to wire itself to a surrounding `Field`. Empty when standalone. */
export function useFieldControlProps(): FieldControlProps {
  const f = useFieldContext();
  if (!f) return {};
  return {
    id: f.id,
    'aria-describedby': f.describedBy,
    'aria-invalid': f.invalid || undefined,
    'aria-required': f.required || undefined,
    disabled: f.disabled || undefined,
  };
}

export interface FieldProps extends ComponentPropsWithoutRef<'div'> {
  invalid?: boolean;
  required?: boolean;
  disabled?: boolean;
}

const FieldRoot = forwardRef<HTMLDivElement, FieldProps>(function Field(
  { invalid = false, required = false, disabled = false, id: idProp, className, children, ...rest },
  ref,
) {
  const id = useId(idProp);
  const descriptionId = `${id}-desc`;
  const errorId = `${id}-error`;
  const [hasDesc, setHasDesc] = useState(false);
  const [hasError, setHasError] = useState(false);

  const describedBy =
    [hasDesc ? descriptionId : null, hasError ? errorId : null].filter(Boolean).join(' ') ||
    undefined;

  const ctx = useMemo<FieldContextValue>(
    () => ({
      id,
      descriptionId,
      errorId,
      invalid,
      required,
      disabled,
      describedBy,
      registerDescription: setHasDesc,
      registerError: setHasError,
    }),
    [id, descriptionId, errorId, invalid, required, disabled, describedBy],
  );

  return (
    <FieldContext.Provider value={ctx}>
      <div
        ref={ref}
        className={clsx(styles.field, className)}
        data-disabled={disabled || undefined}
        {...rest}
      >
        {children}
      </div>
    </FieldContext.Provider>
  );
});

const FieldLabel = forwardRef<HTMLLabelElement, ComponentPropsWithoutRef<'label'>>(
  function FieldLabel({ className, children, ...rest }, ref) {
    const f = useFieldContext();
    return (
      <label ref={ref} htmlFor={f?.id} className={clsx(styles.label, className)} {...rest}>
        {children}
        {f?.required ? (
          <span className={styles.required} aria-hidden="true">
            {' *'}
          </span>
        ) : null}
      </label>
    );
  },
);

const FieldDescription = forwardRef<HTMLParagraphElement, ComponentPropsWithoutRef<'p'>>(
  function FieldDescription({ className, ...rest }, ref) {
    const f = useFieldContext();
    const register = f?.registerDescription;
    useEffect(() => {
      register?.(true);
      return () => register?.(false);
    }, [register]);
    return (
      <p
        ref={ref}
        id={f?.descriptionId}
        className={clsx(styles.description, className)}
        {...rest}
      />
    );
  },
);

const FieldError = forwardRef<HTMLParagraphElement, ComponentPropsWithoutRef<'p'>>(
  function FieldError({ className, children, ...rest }, ref) {
    const f = useFieldContext();
    const register = f?.registerError;
    const show = Boolean(children);
    useEffect(() => {
      register?.(show);
      return () => register?.(false);
    }, [register, show]);
    if (!show) return null;
    return (
      <p ref={ref} id={f?.errorId} role="alert" className={clsx(styles.error, className)} {...rest}>
        {children}
      </p>
    );
  },
);

/** Form field wrapper. Wires label/description/error ids + ARIA to the control via context. */
export const Field = Object.assign(FieldRoot, {
  Label: FieldLabel,
  Description: FieldDescription,
  Error: FieldError,
});
