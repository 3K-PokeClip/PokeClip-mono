import { forwardRef, useEffect, useMemo, useRef, useState, type KeyboardEvent } from 'react';
import clsx from 'clsx';
import { Portal } from '../../primitives/Portal';
import { DismissableLayer } from '../../primitives/DismissableLayer';
import { useComposedRefs } from '../../primitives/hooks/useComposedRefs';
import { useControllableState } from '../../primitives/hooks/useControllableState';
import { useId } from '../../primitives/hooks/useId';
import { useFloating } from '../../primitives/positioning';
import { useFieldControlProps } from '../Field/Field';
import type { SelectOption } from '../Select/Select';
import inputStyles from '../Input/Input.module.css';
import listStyles from '../Select/Select.module.css';

export interface ComboboxProps {
  options: SelectOption[];
  value?: string;
  defaultValue?: string;
  onValueChange?: (value: string) => void;
  placeholder?: string;
  disabled?: boolean;
  invalid?: boolean;
  emptyMessage?: string;
  className?: string;
  'aria-label'?: string;
}

const labelText = (opt: SelectOption): string =>
  typeof opt.label === 'string' ? opt.label : opt.value;

/** Editable autocomplete combobox: type to filter, arrow to navigate, Enter to select. */
export const Combobox = forwardRef<HTMLInputElement, ComboboxProps>(function Combobox(
  {
    options,
    value,
    defaultValue,
    onValueChange,
    placeholder,
    disabled,
    invalid,
    emptyMessage = '결과 없음',
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
  const selectedLabel = useMemo(() => {
    const opt = options.find((o) => o.value === val);
    return opt ? labelText(opt) : '';
  }, [options, val]);

  const [text, setText] = useState(selectedLabel);
  const [open, setOpen] = useState(false);
  const [activeIndex, setActiveIndex] = useState(0);
  const inputRef = useRef<HTMLInputElement>(null);
  const listRef = useRef<HTMLDivElement>(null);
  const composedRef = useComposedRefs<HTMLInputElement>(ref, inputRef);
  const field = useFieldControlProps();
  const baseId = useId();
  const listId = `${baseId}-listbox`;
  const isDisabled = disabled ?? field.disabled;
  const isInvalid = invalid ?? field['aria-invalid'];
  const { coords } = useFloating(inputRef, listRef, { side: 'bottom', align: 'start', open });

  // Keep the input text in sync when the selected value changes externally.
  useEffect(() => {
    setText(selectedLabel);
  }, [selectedLabel]);

  const filtered = useMemo(() => {
    const q = text.trim().toLowerCase();
    if (!q) return options;
    return options.filter(
      (o) => labelText(o).toLowerCase().includes(q) || o.value.toLowerCase().includes(q),
    );
  }, [options, text]);

  const optionId = (i: number) => `${baseId}-opt-${i}`;
  const nextEnabled = (from: number, dir: 1 | -1): number => {
    if (filtered.length === 0) return -1;
    let i = from;
    for (let step = 0; step < filtered.length; step++) {
      i = (i + dir + filtered.length) % filtered.length;
      if (!filtered[i]?.disabled) return i;
    }
    return from;
  };

  const commit = (i: number) => {
    const opt = filtered[i];
    if (!opt || opt.disabled) return;
    setVal(opt.value);
    setText(labelText(opt));
    setOpen(false);
  };

  const onKeyDown = (e: KeyboardEvent<HTMLInputElement>) => {
    if (!open && (e.key === 'ArrowDown' || e.key === 'ArrowUp')) {
      e.preventDefault();
      setOpen(true);
      setActiveIndex(nextEnabled(-1, 1));
      return;
    }
    if (!open) return;
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      setActiveIndex((i) => nextEnabled(i, 1));
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      setActiveIndex((i) => nextEnabled(i, -1));
    } else if (e.key === 'Enter') {
      if (activeIndex >= 0) {
        e.preventDefault();
        commit(activeIndex);
      }
    } else if (e.key === 'Escape') {
      e.preventDefault();
      setOpen(false);
    }
  };

  return (
    <>
      <input
        ref={composedRef}
        type="text"
        role="combobox"
        aria-autocomplete="list"
        aria-expanded={open}
        aria-controls={open ? listId : undefined}
        aria-activedescendant={open && activeIndex >= 0 ? optionId(activeIndex) : undefined}
        aria-label={aria['aria-label']}
        aria-invalid={isInvalid || undefined}
        id={field.id}
        aria-describedby={field['aria-describedby']}
        disabled={isDisabled}
        placeholder={placeholder}
        value={text}
        data-invalid={isInvalid || undefined}
        className={clsx(inputStyles.input, className)}
        onChange={(e) => {
          setText(e.target.value);
          setOpen(true);
          setActiveIndex(0);
        }}
        onFocus={() => setOpen(true)}
        onKeyDown={onKeyDown}
        autoComplete="off"
      />
      {open ? (
        <Portal>
          <DismissableLayer onDismiss={() => setOpen(false)} excludeRefs={[inputRef]}>
            <div
              ref={listRef}
              role="listbox"
              id={listId}
              className={listStyles.listbox}
              style={{
                position: 'fixed',
                top: coords.top,
                left: coords.left,
                minWidth: inputRef.current?.offsetWidth,
              }}
            >
              {filtered.length === 0 ? (
                <div className={listStyles.empty}>{emptyMessage}</div>
              ) : (
                filtered.map((opt, i) => (
                  <div
                    key={opt.value}
                    id={optionId(i)}
                    role="option"
                    aria-selected={opt.value === val}
                    aria-disabled={opt.disabled || undefined}
                    data-active={i === activeIndex || undefined}
                    className={listStyles.option}
                    onMouseEnter={() => !opt.disabled && setActiveIndex(i)}
                    onMouseDown={(e) => e.preventDefault()}
                    onClick={() => commit(i)}
                  >
                    {opt.label}
                  </div>
                ))
              )}
            </div>
          </DismissableLayer>
        </Portal>
      ) : null}
    </>
  );
});
