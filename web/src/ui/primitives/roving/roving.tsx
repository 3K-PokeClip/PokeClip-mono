import {
  createContext,
  useCallback,
  useContext,
  useMemo,
  useRef,
  type KeyboardEvent,
  type ReactNode,
  type RefCallback,
} from 'react';

export type Orientation = 'horizontal' | 'vertical' | 'both';

interface RovingContextValue {
  activeValue: string | null;
  register: (value: string, el: HTMLElement | null) => void;
  onKeyNavigate: (e: KeyboardEvent) => void;
  setActiveValue: (value: string) => void;
}

const RovingContext = createContext<RovingContextValue | null>(null);

export interface RovingProviderProps {
  children: ReactNode;
  orientation?: Orientation;
  loop?: boolean;
  /** The active value (the single tab stop). Navigation activates items (automatic activation). */
  activeValue: string | null;
  onActiveChange?: (value: string) => void;
}

function isDisabled(el: HTMLElement): boolean {
  return (
    el.hasAttribute('disabled') ||
    el.getAttribute('aria-disabled') === 'true' ||
    el.hasAttribute('data-disabled')
  );
}

/** Manages roving-tabindex keyboard navigation for a group of items (Tabs, RadioGroup, …). */
export function RovingProvider({
  children,
  orientation = 'horizontal',
  loop = true,
  activeValue,
  onActiveChange,
}: RovingProviderProps) {
  const items = useRef(new Map<string, HTMLElement>());

  const register = useCallback((value: string, el: HTMLElement | null) => {
    if (el) items.current.set(value, el);
    else items.current.delete(value);
  }, []);

  const orderedEnabled = useCallback((): string[] => {
    return Array.from(items.current.entries())
      .filter(([, el]) => !isDisabled(el))
      .sort(([, a], [, b]) =>
        a.compareDocumentPosition(b) & Node.DOCUMENT_POSITION_FOLLOWING ? -1 : 1,
      )
      .map(([v]) => v);
  }, []);

  const setActiveValue = useCallback((value: string) => onActiveChange?.(value), [onActiveChange]);

  const onKeyNavigate = useCallback(
    (e: KeyboardEvent) => {
      const values = orderedEnabled();
      if (values.length === 0) return;
      const active = document.activeElement;
      const currentValue = Array.from(items.current.entries()).find(([, el]) => el === active)?.[0];
      const idx = currentValue ? values.indexOf(currentValue) : -1;
      const horiz = orientation === 'horizontal' || orientation === 'both';
      const vert = orientation === 'vertical' || orientation === 'both';
      let next = -1;
      if ((horiz && e.key === 'ArrowRight') || (vert && e.key === 'ArrowDown')) next = idx + 1;
      else if ((horiz && e.key === 'ArrowLeft') || (vert && e.key === 'ArrowUp')) next = idx - 1;
      else if (e.key === 'Home') next = 0;
      else if (e.key === 'End') next = values.length - 1;
      else return;
      e.preventDefault();
      if (next < 0) next = loop ? values.length - 1 : 0;
      if (next >= values.length) next = loop ? 0 : values.length - 1;
      const nextValue = values[next];
      if (nextValue == null) return;
      items.current.get(nextValue)?.focus();
      onActiveChange?.(nextValue);
    },
    [orderedEnabled, orientation, loop, onActiveChange],
  );

  const ctx = useMemo<RovingContextValue>(
    () => ({ activeValue, register, onKeyNavigate, setActiveValue }),
    [activeValue, register, onKeyNavigate, setActiveValue],
  );

  return <RovingContext.Provider value={ctx}>{children}</RovingContext.Provider>;
}

function useRovingContext(): RovingContextValue {
  const ctx = useContext(RovingContext);
  if (!ctx) throw new Error('Roving item must be used within a RovingProvider.');
  return ctx;
}

export interface RovingItemProps {
  ref: RefCallback<HTMLElement>;
  tabIndex: number;
  onKeyDown: (e: KeyboardEvent) => void;
  onFocus: () => void;
}

/** Wires a single item into the surrounding {@link RovingProvider}. */
export function useRovingItem(value: string): RovingItemProps {
  const ctx = useRovingContext();
  const ref = useCallback<RefCallback<HTMLElement>>((el) => ctx.register(value, el), [ctx, value]);
  return {
    ref,
    tabIndex: ctx.activeValue === value ? 0 : -1,
    onKeyDown: ctx.onKeyNavigate,
    onFocus: () => ctx.setActiveValue(value),
  };
}
