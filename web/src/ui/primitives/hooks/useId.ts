import { useId as useReactId } from 'react';

/** SSR-stable id with a `pc-` prefix; returns `providedId` when given. */
export function useId(providedId?: string): string {
  const generated = useReactId();
  return providedId ?? `pc-${generated.replace(/:/g, '')}`;
}
