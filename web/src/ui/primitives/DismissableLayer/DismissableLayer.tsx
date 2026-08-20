import { useEffect, useRef, type ReactNode, type RefObject } from 'react';

const layerStack: symbol[] = [];

/** 열려 있는 dismissable 레이어(모달·드로어·팝오버)가 있는지.
 *  토스트처럼 레이어 밖에서 Esc를 듣는 표면이 우선순위를 양보할 때 쓴다. */
export function hasOpenDismissableLayer(): boolean {
  return layerStack.length > 0;
}

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
      // 닫히면서 아래 요소가 눌리는 click-through 방지 — capture 단계라 타깃 도달 전에 소비된다
      e.preventDefault();
      e.stopPropagation();
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
