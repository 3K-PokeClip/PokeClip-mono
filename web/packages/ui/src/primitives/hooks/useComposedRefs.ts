import { useCallback } from 'react';
import type { RefCallback, RefObject } from 'react';

type PossibleRef<T> = RefCallback<T> | RefObject<T | null> | null | undefined;

function setRef<T>(ref: PossibleRef<T>, value: T | null): void {
  if (typeof ref === 'function') ref(value);
  else if (ref != null) (ref as { current: T | null }).current = value;
}

/** Merge multiple refs into one callback ref. */
export function composeRefs<T>(...refs: Array<PossibleRef<T>>): RefCallback<T> {
  return (node) => {
    for (const ref of refs) setRef(ref, node);
  };
}

/** Hook form of {@link composeRefs}, memoised across renders. */
export function useComposedRefs<T>(...refs: Array<PossibleRef<T>>): RefCallback<T> {
  // eslint-disable-next-line react-hooks/exhaustive-deps
  return useCallback(composeRefs(...refs), refs);
}
