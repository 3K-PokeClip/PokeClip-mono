import { useRef, type ReactNode } from 'react';
import { useIsomorphicLayoutEffect } from '../hooks/useIsomorphicLayoutEffect';

// tabindex="-1"은 태그와 상관없이 뺀다 — roving 탭(선택 안 된 탭은 -1)이 든 창이 열릴 때 첫 버튼으로 잡히면
// 그 탭이 포커스로 선택돼 버린다(POK-379: Mac에서 다운로드 창이 Windows 탭으로 열림)
const FOCUSABLE = [
  'a[href]',
  'button:not([disabled])',
  'input:not([disabled])',
  'select:not([disabled])',
  'textarea:not([disabled])',
  '[tabindex]',
  '[contenteditable="true"]',
]
  .map((selector) => `${selector}:not([tabindex="-1"])`)
  .join(',');

function getFocusable(container: HTMLElement): HTMLElement[] {
  return Array.from(container.querySelectorAll<HTMLElement>(FOCUSABLE)).filter(
    (el) => el.offsetWidth > 0 || el.offsetHeight > 0 || el === document.activeElement,
  );
}

export interface FocusScopeProps {
  children: ReactNode;
  /** Cycle Tab focus within the scope. Default `true`. */
  trapped?: boolean;
  /** Restore focus to the previously-focused element on unmount. Default `true`. */
  restoreFocus?: boolean;
  /** Move focus into the scope on mount. Default `true`. */
  autoFocus?: boolean;
}

/** Traps Tab focus within its children and restores focus on unmount. */
export function FocusScope({
  children,
  trapped = true,
  restoreFocus = true,
  autoFocus = true,
}: FocusScopeProps) {
  const ref = useRef<HTMLDivElement>(null);

  useIsomorphicLayoutEffect(() => {
    const container = ref.current;
    if (!container) return;
    const previouslyFocused = document.activeElement as HTMLElement | null;

    if (autoFocus) {
      const focusables = getFocusable(container);
      (focusables[0] ?? container).focus();
    }

    function onKeyDown(e: KeyboardEvent) {
      if (!trapped || e.key !== 'Tab' || !container) return;
      const focusables = getFocusable(container);
      if (focusables.length === 0) {
        e.preventDefault();
        container.focus();
        return;
      }
      const first = focusables[0]!;
      const last = focusables[focusables.length - 1]!;
      const active = document.activeElement;
      if (e.shiftKey && active === first) {
        e.preventDefault();
        last.focus();
      } else if (!e.shiftKey && active === last) {
        e.preventDefault();
        first.focus();
      } else if (!container.contains(active)) {
        e.preventDefault();
        first.focus();
      }
    }

    document.addEventListener('keydown', onKeyDown, true);
    return () => {
      document.removeEventListener('keydown', onKeyDown, true);
      if (restoreFocus && previouslyFocused && document.contains(previouslyFocused)) {
        previouslyFocused.focus();
      }
    };
  }, []);

  return (
    <div ref={ref} tabIndex={-1} style={{ outline: 'none' }}>
      {children}
    </div>
  );
}
