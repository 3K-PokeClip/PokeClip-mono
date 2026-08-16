import {
  createContext,
  forwardRef,
  useContext,
  useEffect,
  useRef,
  useState,
  type ComponentPropsWithoutRef,
  type KeyboardEvent,
  type ReactElement,
  type ReactNode,
} from 'react';
import clsx from 'clsx';
import { Portal } from '../../primitives/Portal';
import { Slot } from '../../primitives/Slot';
import { DismissableLayer } from '../../primitives/DismissableLayer';
import { useComposedRefs } from '../../primitives/hooks/useComposedRefs';
import styles from '../DropdownMenu/DropdownMenu.module.css';

interface Point {
  x: number;
  y: number;
}
interface ContextMenuContextValue {
  open: boolean;
  setOpen: (open: boolean) => void;
  point: Point;
  openAt: (p: Point) => void;
  contentRef: React.RefObject<HTMLDivElement | null>;
  triggerRef: React.RefObject<HTMLElement | null>;
}
const Ctx = createContext<ContextMenuContextValue | null>(null);
function useCtx(): ContextMenuContextValue {
  const c = useContext(Ctx);
  if (!c) throw new Error('ContextMenu subcomponents must be used within <ContextMenu>.');
  return c;
}

export interface ContextMenuProps {
  children: ReactNode;
}

function ContextMenuRoot({ children }: ContextMenuProps) {
  const [open, setOpen] = useState(false);
  const [point, setPoint] = useState<Point>({ x: 0, y: 0 });
  const contentRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLElement>(null);
  const openAt = (p: Point) => {
    setPoint(p);
    setOpen(true);
  };
  return (
    <Ctx.Provider value={{ open, setOpen, point, openAt, contentRef, triggerRef }}>
      {children}
    </Ctx.Provider>
  );
}

const ContextMenuTrigger = forwardRef<HTMLElement, { children: ReactElement }>(
  function ContextMenuTrigger({ children }, ref) {
    const ctx = useCtx();
    const composedRef = useComposedRefs<HTMLElement>(ref, ctx.triggerRef);
    return (
      <Slot
        ref={composedRef}
        onContextMenu={(e: React.MouseEvent) => {
          e.preventDefault();
          ctx.openAt({ x: e.clientX, y: e.clientY });
        }}
      >
        {children}
      </Slot>
    );
  },
);

const ContextMenuContent = forwardRef<HTMLDivElement, ComponentPropsWithoutRef<'div'>>(
  function ContextMenuContent({ className, children, ...rest }, ref) {
    const ctx = useCtx();
    const composedRef = useComposedRefs<HTMLDivElement>(ref, ctx.contentRef);
    const typeahead = useRef({ str: '', timer: 0 });

    useEffect(() => {
      if (!ctx.open) return;
      const el = ctx.contentRef.current;
      el?.querySelector<HTMLElement>('[role="menuitem"]:not([aria-disabled="true"])')?.focus();
      const trigger = ctx.triggerRef.current;
      return () => trigger?.focus();
      // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [ctx.open]);

    const items = (): HTMLElement[] =>
      Array.from(
        ctx.contentRef.current?.querySelectorAll<HTMLElement>(
          '[role="menuitem"]:not([aria-disabled="true"])',
        ) ?? [],
      );

    function onKeyDown(e: KeyboardEvent<HTMLDivElement>) {
      const list = items();
      if (list.length === 0) return;
      const idx = list.indexOf(document.activeElement as HTMLElement);
      if (e.key === 'ArrowDown') {
        e.preventDefault();
        list[(idx + 1) % list.length]?.focus();
      } else if (e.key === 'ArrowUp') {
        e.preventDefault();
        list[(idx - 1 + list.length) % list.length]?.focus();
      } else if (e.key === 'Home') {
        e.preventDefault();
        list[0]?.focus();
      } else if (e.key === 'End') {
        e.preventDefault();
        list[list.length - 1]?.focus();
      } else if (e.key === 'Tab') {
        ctx.setOpen(false);
      } else if (e.key.length === 1 && e.key.trim()) {
        window.clearTimeout(typeahead.current.timer);
        typeahead.current.str += e.key.toLowerCase();
        list
          .find((it) => it.textContent?.toLowerCase().trim().startsWith(typeahead.current.str))
          ?.focus();
        typeahead.current.timer = window.setTimeout(() => {
          typeahead.current.str = '';
        }, 500);
      }
    }

    if (!ctx.open) return null;
    const left = Math.min(ctx.point.x, window.innerWidth - 200);
    const top = Math.min(ctx.point.y, window.innerHeight - 8);
    return (
      <Portal>
        <DismissableLayer onDismiss={() => ctx.setOpen(false)}>
          <div
            ref={composedRef}
            role="menu"
            aria-orientation="vertical"
            className={clsx(styles.content, className)}
            style={{ position: 'fixed', top, left }}
            onKeyDown={onKeyDown}
            {...rest}
          >
            {children}
          </div>
        </DismissableLayer>
      </Portal>
    );
  },
);

interface ContextMenuItemProps extends Omit<ComponentPropsWithoutRef<'button'>, 'onSelect'> {
  onSelect?: () => void;
  disabled?: boolean;
  danger?: boolean;
}
const ContextMenuItem = forwardRef<HTMLButtonElement, ContextMenuItemProps>(
  function ContextMenuItem({ onSelect, disabled, danger, className, onClick, ...rest }, ref) {
    const ctx = useCtx();
    return (
      <button
        ref={ref}
        type="button"
        role="menuitem"
        tabIndex={-1}
        aria-disabled={disabled || undefined}
        data-danger={danger || undefined}
        className={clsx(styles.item, className)}
        onClick={(e) => {
          if (disabled) {
            e.preventDefault();
            return;
          }
          onClick?.(e);
          onSelect?.();
          ctx.setOpen(false);
        }}
        {...rest}
      />
    );
  },
);

const ContextMenuSeparator = forwardRef<HTMLDivElement, ComponentPropsWithoutRef<'div'>>(
  function ContextMenuSeparator({ className, ...rest }, ref) {
    return (
      <div ref={ref} role="separator" className={clsx(styles.separator, className)} {...rest} />
    );
  },
);

/** Right-click context menu positioned at the pointer, with menu keyboard semantics. */
export const ContextMenu = Object.assign(ContextMenuRoot, {
  Trigger: ContextMenuTrigger,
  Content: ContextMenuContent,
  Item: ContextMenuItem,
  Separator: ContextMenuSeparator,
});
