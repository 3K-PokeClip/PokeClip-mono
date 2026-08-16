import {
  Children,
  createContext,
  forwardRef,
  isValidElement,
  useContext,
  type ComponentPropsWithoutRef,
  type ReactNode,
} from 'react';
import clsx from 'clsx';
import { useControllableState } from '../../primitives/hooks/useControllableState';
import { useComposedRefs } from '../../primitives/hooks/useComposedRefs';
import { useId } from '../../primitives/hooks/useId';
import { RovingProvider, useRovingItem } from '../../primitives/roving';
import styles from './RadioGroup.module.css';

interface RadioGroupContextValue {
  value: string | undefined;
  setValue: (v: string) => void;
  name: string;
  disabled: boolean;
}
const RadioGroupContext = createContext<RadioGroupContextValue | null>(null);
function useRadioGroup(): RadioGroupContextValue {
  const c = useContext(RadioGroupContext);
  if (!c) throw new Error('Radio must be used within a RadioGroup.');
  return c;
}

export interface RadioGroupProps extends Omit<
  ComponentPropsWithoutRef<'div'>,
  'onChange' | 'defaultValue'
> {
  value?: string;
  defaultValue?: string;
  onValueChange?: (value: string) => void;
  name?: string;
  orientation?: 'horizontal' | 'vertical';
  disabled?: boolean;
}

const RadioGroupRoot = forwardRef<HTMLDivElement, RadioGroupProps>(function RadioGroup(
  {
    value,
    defaultValue,
    onValueChange,
    name: nameProp,
    orientation = 'vertical',
    disabled = false,
    className,
    children,
    ...rest
  },
  ref,
) {
  const [val, setVal] = useControllableState<string | undefined>({
    value,
    defaultValue,
    onChange: onValueChange as ((v: string | undefined) => void) | undefined,
  });
  const name = useId(nameProp);
  const values = Children.toArray(children)
    .filter(isValidElement)
    .map((c) => (c.props as { value?: string }).value)
    .filter((v): v is string => Boolean(v));
  const activeValue = val ?? values[0] ?? null;

  return (
    <RadioGroupContext.Provider value={{ value: val, setValue: (v) => setVal(v), name, disabled }}>
      <RovingProvider
        activeValue={activeValue}
        onActiveChange={(v) => setVal(v)}
        orientation={orientation}
      >
        <div
          ref={ref}
          role="radiogroup"
          aria-orientation={orientation}
          data-orientation={orientation}
          className={clsx(styles.group, className)}
          {...rest}
        >
          {children}
        </div>
      </RovingProvider>
    </RadioGroupContext.Provider>
  );
});

export interface RadioProps extends Omit<ComponentPropsWithoutRef<'input'>, 'type' | 'onChange'> {
  value: string;
  label?: ReactNode;
}

export const Radio = forwardRef<HTMLInputElement, RadioProps>(function Radio(
  { value, label, disabled, className, ...rest },
  ref,
) {
  const group = useRadioGroup();
  const roving = useRovingItem(value);
  const composedRef = useComposedRefs<HTMLInputElement>(ref, roving.ref);
  const checked = group.value === value;
  const isDisabled = Boolean(disabled) || group.disabled;
  return (
    <label className={clsx(styles.root, className)} data-disabled={isDisabled || undefined}>
      <input
        {...rest}
        ref={composedRef}
        type="radio"
        name={group.name}
        value={value}
        checked={checked}
        disabled={isDisabled}
        data-disabled={isDisabled || undefined}
        tabIndex={roving.tabIndex}
        onChange={() => group.setValue(value)}
        onKeyDown={roving.onKeyDown}
        onFocus={roving.onFocus}
        className={styles.input}
      />
      <span className={styles.dot} aria-hidden="true" />
      {label != null ? <span>{label}</span> : null}
    </label>
  );
});

export const RadioGroup = Object.assign(RadioGroupRoot, { Item: Radio });
