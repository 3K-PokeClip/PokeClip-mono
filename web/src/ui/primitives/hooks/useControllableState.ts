import { useCallback, useRef, useState } from 'react';

export interface UseControllableStateParams<T> {
  /** Controlled value. When defined the component is controlled. */
  value?: T | undefined;
  /** Initial value when uncontrolled. */
  defaultValue?: T | undefined;
  /** Called whenever the value should change (both modes). */
  onChange?: ((value: T) => void) | undefined;
}

/** Unifies controlled and uncontrolled state into a single `[value, setValue]` pair. */
export function useControllableState<T>({
  value,
  defaultValue,
  onChange,
}: UseControllableStateParams<T>): [T, (next: T | ((prev: T) => T)) => void] {
  const [uncontrolled, setUncontrolled] = useState<T | undefined>(defaultValue);
  const isControlled = value !== undefined;
  const current = (isControlled ? value : uncontrolled) as T;

  const onChangeRef = useRef(onChange);
  onChangeRef.current = onChange;

  const setValue = useCallback(
    (next: T | ((prev: T) => T)) => {
      const resolved = typeof next === 'function' ? (next as (prev: T) => T)(current) : next;
      if (!isControlled) setUncontrolled(resolved);
      if (!Object.is(resolved, current)) onChangeRef.current?.(resolved);
    },
    [current, isControlled],
  );

  return [current, setValue];
}
