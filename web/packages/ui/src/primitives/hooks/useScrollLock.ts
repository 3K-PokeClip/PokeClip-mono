import { useIsomorphicLayoutEffect } from './useIsomorphicLayoutEffect';

let lockCount = 0;
let originalOverflow = '';
let originalPaddingRight = '';

/** Locks body scroll while `active`, compensating for scrollbar width. Ref-counted. */
export function useScrollLock(active = true): void {
  useIsomorphicLayoutEffect(() => {
    if (!active) return;
    if (lockCount === 0) {
      const body = document.body;
      const scrollbarWidth = window.innerWidth - document.documentElement.clientWidth;
      originalOverflow = body.style.overflow;
      originalPaddingRight = body.style.paddingRight;
      body.style.overflow = 'hidden';
      if (scrollbarWidth > 0) body.style.paddingRight = `${scrollbarWidth}px`;
    }
    lockCount += 1;
    return () => {
      lockCount -= 1;
      if (lockCount === 0) {
        document.body.style.overflow = originalOverflow;
        document.body.style.paddingRight = originalPaddingRight;
      }
    };
  }, [active]);
}
