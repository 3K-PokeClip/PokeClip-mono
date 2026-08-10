import { useRef, type ReactNode } from 'react';
import { useIsomorphicLayoutEffect } from '../hooks/useIsomorphicLayoutEffect';

const FOCUSABLE =
  'a[href],button:not([disabled]),input:not([disabled]),select:not([disabled]),textarea:not([disabled]),[tabindex]:not([tabindex="-1"]),[contenteditable="true"]';

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
