import { forwardRef, useEffect, useRef, useState, type KeyboardEvent, type ReactNode } from 'react';
import clsx from 'clsx';
import { Portal } from '../../primitives/Portal';
import { DismissableLayer } from '../../primitives/DismissableLayer';
import { useComposedRefs } from '../../primitives/hooks/useComposedRefs';
import { useControllableState } from '../../primitives/hooks/useControllableState';
import { useId } from '../../primitives/hooks/useId';
import { useFieldControlProps } from '../Field/Field';
import { useFloating } from '../../primitives/positioning';
import styles from './Select.module.css';

export interface SelectOption {
  value: string;
  label: ReactNode;
  disabled?: boolean;
}

export interface SelectProps {
  options: SelectOption[];
  value?: string;
  defaultValue?: string;
  onValueChange?: (value: string) => void;
  placeholder?: string;
  disabled?: boolean;
  invalid?: boolean;
  size?: 'sm' | 'md' | 'lg';
  className?: string;
  'aria-label'?: string;
}

const Chevron = () => (
  <svg className={styles.chevron} width="16" height="16" viewBox="0 0 16 16" aria-hidden="true">
    <path
      d="M4 6l4 4 4-4"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.6"
      strokeLinecap="round"
      strokeLinejoin="round"
    />
  </svg>
);

/** Single-select listbox (button + popup) using the aria-activedescendant pattern. */
export const Select = forwardRef<HTMLButtonElement, SelectProps>(function Select(
  {
    options,
    value,
    defaultValue,
    onValueChange,
    placeholder = '선택하세요',
    disabled,
    invalid,
    size = 'md',
    className,
    ...aria
  },
  ref,
) {
  const [val, setVal] = useControllableState<string | undefined>({
    value,
    defaultValue,
    onChange: onValueChange as ((v: string | undefined) => void) | undefined,
  });
  const [open, setOpen] = useState(false);
  const [activeIndex, setActiveIndex] = useState(0);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const listRef = useRef<HTMLDivElement>(null);
  const composedRef = useComposedRefs<HTMLButtonElement>(ref, triggerRef);
  const field = useFieldControlProps();
  const baseId = useId();
  const listId = `${baseId}-listbox`;
  const isDisabled = disabled ?? field.disabled;
  const isInvalid = invalid ?? field['aria-invalid'];
  const { coords } = useFloating(triggerRef, listRef, { side: 'bottom', align: 'start', open });

  const selected = options.find((o) => o.value === val);
  const optionId = (i: number) => `${baseId}-opt-${i}`;

  const nextEnabled = (from: number, dir: 1 | -1): number => {
    let i = from;
    for (let step = 0; step < options.length; step++) {
      i = (i + dir + options.length) % options.length;
      if (!options[i]?.disabled) return i;
    }
    return from;
  };

  useEffect(() => {
    if (!open) return;
    const selIdx = options.findIndex((o) => o.value === val);
    setActiveIndex(selIdx >= 0 ? selIdx : nextEnabled(-1, 1));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  const commit = (i: number) => {
    const opt = options[i];
    if (!opt || opt.disabled) return;
    setVal(opt.value);
    setOpen(false);
    triggerRef.current?.focus();
  };

  const onKeyDown = (e: KeyboardEvent<HTMLButtonElement>) => {
    if (!open) {
      if (e.key === 'ArrowDown' || e.key === 'Enter' || e.key === ' ' || e.key === 'ArrowUp') {
        e.preventDefault();
        setOpen(true);
      }
      return;
    }
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      setActiveIndex((i) => nextEnabled(i, 1));
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      setActiveIndex((i) => nextEnabled(i, -1));
    } else if (e.key === 'Home') {
      e.preventDefault();
      setActiveIndex(nextEnabled(-1, 1));
    } else if (e.key === 'End') {
      e.preventDefault();
      setActiveIndex(nextEnabled(0, -1));
    } else if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      commit(activeIndex);
    } else if (e.key === 'Escape') {
      e.preventDefault();
      setOpen(false);
    }
  };

  return (
    <>
      <button
        ref={composedRef}
        type="button"
        role="combobox"
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-controls={open ? listId : undefined}
        aria-activedescendant={open ? optionId(activeIndex) : undefined}
        aria-label={aria['aria-label']}
        aria-invalid={isInvalid || undefined}
        id={field.id}
        disabled={isDisabled}
        data-size={size}
        data-invalid={isInvalid || undefined}
        data-placeholder={selected ? undefined : ''}
        className={clsx(styles.trigger, className)}
        onClick={() => setOpen((o) => !o)}
        onKeyDown={onKeyDown}
      >
        <span className={styles.value}>{selected ? selected.label : placeholder}</span>
        <Chevron />
      </button>
      {open ? (
        <Portal>
          <DismissableLayer onDismiss={() => setOpen(false)} excludeRefs={[triggerRef]}>
            <div
              ref={listRef}
              role="listbox"
              id={listId}
              className={styles.listbox}
              style={{
                position: 'fixed',
                top: coords.top,
                left: coords.left,
                minWidth: triggerRef.current?.offsetWidth,
              }}
            >
              {options.map((opt, i) => (
                <div
                  key={opt.value}
                  id={optionId(i)}
                  role="option"
                  aria-selected={opt.value === val}
                  aria-disabled={opt.disabled || undefined}
                  data-active={i === activeIndex || undefined}
                  className={styles.option}
                  onMouseEnter={() => !opt.disabled && setActiveIndex(i)}
                  onClick={() => commit(i)}
                >
                  <span>{opt.label}</span>
                  {opt.value === val ? (
                    <svg
                      className={styles.check}
                      width="16"
                      height="16"
                      viewBox="0 0 16 16"
                      aria-hidden="true"
                    >
                      <path
                        d="M3.5 8.5l3 3 6-6.5"
                        fill="none"
                        stroke="currentColor"
                        strokeWidth="1.8"
                        strokeLinecap="round"
                        strokeLinejoin="round"
                      />
                    </svg>
                  ) : null}
                </div>
              ))}
            </div>
          </DismissableLayer>
        </Portal>
      ) : null}
    </>
  );
});
