import { useEffect, useRef, type ReactNode, type RefObject } from 'react';

const layerStack: symbol[] = [];

export interface DismissableLayerProps {
  children: ReactNode;
  onDismiss?: () => void;
  /** Disable outside-pointer dismissal (Esc still works). */
  disableOutsidePointer?: boolean;
  /** Targets that should not count as "outside" (e.g. the trigger). */
  excludeRefs?: Array<RefObject<HTMLElement | null>>;
}

/** Dismisses the topmost layer on Escape or outside pointer-down. Maintains a layer stack. */
export function DismissableLayer({
  children,
  onDismiss,
  disableOutsidePointer = false,
  excludeRefs,
}: DismissableLayerProps) {
  const ref = useRef<HTMLDivElement>(null);
  const idRef = useRef<symbol>(Symbol('layer'));

  useEffect(() => {
    const id = idRef.current;
    layerStack.push(id);
    const isTopmost = () => layerStack[layerStack.length - 1] === id;

    function onKeyDown(e: KeyboardEvent) {
      if (e.key === 'Escape' && isTopmost()) {
        e.stopPropagation();
        onDismiss?.();
      }
    }
    function onPointerDown(e: PointerEvent) {
      if (disableOutsidePointer || !isTopmost()) return;
      const target = e.target as Node;
      if (ref.current?.contains(target)) return;
      if (excludeRefs?.some((r) => r.current?.contains(target))) return;
      onDismiss?.();
    }

    document.addEventListener('keydown', onKeyDown);
    document.addEventListener('pointerdown', onPointerDown, true);
    return () => {
      document.removeEventListener('keydown', onKeyDown);
      document.removeEventListener('pointerdown', onPointerDown, true);
      const i = layerStack.indexOf(id);
      if (i >= 0) layerStack.splice(i, 1);
    };
  }, [onDismiss, disableOutsidePointer, excludeRefs]);

  return (
    <div ref={ref} style={{ display: 'contents' }}>
      {children}
    </div>
  );
}
