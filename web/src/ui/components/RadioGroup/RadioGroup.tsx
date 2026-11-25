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
        // 화살표 · Home · End는 roving(onKeyDown)이 초점을 옮기며 바로 고른다. 초점만 받았다고 고르지는 않는다(roving.onFocus를
        // 걸지 않는다) — 값이 밖에서 바뀌는 controlled 그룹(설정 › 편집 환경의 패널 위치는 다른 탭이 바꾼다)에서 탭으로 돌아와
        // 브라우저가 옛 라디오에 초점을 돌려주면 옛 값으로 덮어썼다(PR #550 리뷰). Tab으로 들어올 때 고르지 않는 것은 APG와 같다
        onKeyDown={roving.onKeyDown}
        className={styles.input}
      />
      <span className={styles.dot} aria-hidden="true" />
      {label != null ? <span>{label}</span> : null}
    </label>
  );
});

export const RadioGroup = Object.assign(RadioGroupRoot, { Item: Radio });
