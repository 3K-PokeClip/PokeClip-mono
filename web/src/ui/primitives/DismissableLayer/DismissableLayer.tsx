import { useEffect, useRef, type ReactNode, type RefObject } from 'react';

const layerStack: symbol[] = [];

/**
 * 이 표식을 단 요소 안에서 눌린 포인터는 "바깥"으로 치지 않는다.
 *
 * 토스트처럼 **레이어보다 위에 뜨도록 일부러 만든 표면**을 위한 계약이다. 표식이
 * 없으면 열린 모달·드로어 입장에서는 그냥 바깥이라, 토스트의 액션이나 닫기를 누른
 * 클릭 한 번이 그 레이어를 닫아 사용자의 입력을 날린다.
 */
export const OUTSIDE_POINTER_EXEMPT_ATTR = 'data-outside-pointer-exempt';

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
      if ((target as Element).closest?.(`[${OUTSIDE_POINTER_EXEMPT_ATTR}]`)) return;
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
