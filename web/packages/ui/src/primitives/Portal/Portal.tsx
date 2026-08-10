import { useState, type ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { useIsomorphicLayoutEffect } from '../hooks/useIsomorphicLayoutEffect';

export interface PortalProps {
  children: ReactNode;
  /** Mount target. Defaults to `document.body`. */
  container?: Element | null;
}

/** Renders children into a DOM node outside the parent hierarchy (SSR-safe). */
export function Portal({ children, container }: PortalProps) {
  const [mounted, setMounted] = useState(false);
  useIsomorphicLayoutEffect(() => setMounted(true), []);
  if (!mounted) return null;
  return createPortal(children, container ?? document.body);
}
