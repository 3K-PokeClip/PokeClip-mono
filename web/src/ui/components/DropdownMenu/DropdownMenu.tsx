import {
  createContext,
  forwardRef,
  useContext,
  useEffect,
  useRef,
  type ComponentPropsWithoutRef,
  type KeyboardEvent,
  type ReactElement,
  type ReactNode,
  type RefObject,
} from 'react';
import clsx from 'clsx';
import { Portal } from '../../primitives/Portal';
import { Slot } from '../../primitives/Slot';
import { DismissableLayer } from '../../primitives/DismissableLayer';
import { useComposedRefs } from '../../primitives/hooks/useComposedRefs';
import { useControllableState } from '../../primitives/hooks/useControllableState';
import { useId } from '../../primitives/hooks/useId';
import {
  useFloating,
  useMeasureOnAttach,
  type Align,
  type Side,
} from '../../primitives/positioning';
import styles from './DropdownMenu.module.css';

interface MenuContextValue {
  open: boolean;
  setOpen: (open: boolean) => void;
  triggerRef: RefObject<HTMLElement | null>;
  contentRef: RefObject<HTMLDivElement | null>;
  contentId: string;
}
const MenuContext = createContext<MenuContextValue | null>(null);
function useMenu(): MenuContextValue {
  const c = useContext(MenuContext);
  if (!c) throw new Error('DropdownMenu subcomponents must be used within <DropdownMenu>.');
  return c;
}

export interface DropdownMenuProps {
  children: ReactNode;
  open?: boolean;
  defaultOpen?: boolean;
  onOpenChange?: (open: boolean) => void;
}

function DropdownMenuRoot({
  children,
  open,
  defaultOpen = false,
  onOpenChange,
}: DropdownMenuProps) {
  const [isOpen, setOpen] = useControllableState<boolean>({
    value: open,
    defaultValue: defaultOpen,
    onChange: onOpenChange,
  });
  const triggerRef = useRef<HTMLElement>(null);
  const contentRef = useRef<HTMLDivElement>(null);
  const contentId = useId();
  return (
    <MenuContext.Provider value={{ open: isOpen, setOpen, triggerRef, contentRef, contentId }}>
      {children}
    </MenuContext.Provider>
  );
}

const DropdownMenuTrigger = forwardRef<HTMLElement, { children: ReactElement }>(
  function DropdownMenuTrigger({ children }, ref) {
    const ctx = useMenu();
    const composedRef = useComposedRefs<HTMLElement>(ref, ctx.triggerRef);
    return (
      <Slot
        ref={composedRef}
        onClick={() => ctx.setOpen(!ctx.open)}
        aria-haspopup="menu"
        aria-expanded={ctx.open}
        aria-controls={ctx.open ? ctx.contentId : undefined}
      >
        {children}
      </Slot>
    );
  },
);

interface DropdownMenuContentProps extends ComponentPropsWithoutRef<'div'> {
  side?: Side;
  align?: Align;
}
const DropdownMenuContent = forwardRef<HTMLDivElement, DropdownMenuContentProps>(
  function DropdownMenuContent(
    { className, children, side = 'bottom', align = 'start', ...rest },
    ref,
  ) {
    const ctx = useMenu();
    const { coords, update } = useFloating(ctx.triggerRef, ctx.contentRef, {
      side,
      align,
      open: ctx.open,
    });
    const measureRef = useMeasureOnAttach(update);
    const composedRef = useComposedRefs<HTMLDivElement>(ref, ctx.contentRef, measureRef);
    const typeahead = useRef({ str: '', timer: 0 });

    useEffect(() => {
      if (!ctx.open) return;
      const first = ctx.contentRef.current?.querySelector<HTMLElement>(
        '[role="menuitem"]:not([aria-disabled="true"])',
      );
      first?.focus();
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
        const match = list.find((it) =>
          it.textContent?.toLowerCase().trim().startsWith(typeahead.current.str),
        );
        match?.focus();
        typeahead.current.timer = window.setTimeout(() => {
          typeahead.current.str = '';
        }, 500);
      }
    }

    if (!ctx.open) return null;
    return (
      <Portal>
        <DismissableLayer onDismiss={() => ctx.setOpen(false)} excludeRefs={[ctx.triggerRef]}>
          <div
            ref={composedRef}
            role="menu"
            id={ctx.contentId}
            aria-orientation="vertical"
            className={clsx(styles.content, className)}
            style={{ position: 'fixed', top: coords.top, left: coords.left }}
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

interface DropdownMenuItemProps extends Omit<ComponentPropsWithoutRef<'button'>, 'onSelect'> {
  onSelect?: () => void;
  disabled?: boolean;
  danger?: boolean;
}
const DropdownMenuItem = forwardRef<HTMLButtonElement, DropdownMenuItemProps>(
  function DropdownMenuItem({ onSelect, disabled, danger, className, onClick, ...rest }, ref) {
    const ctx = useMenu();
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

const DropdownMenuSeparator = forwardRef<HTMLDivElement, ComponentPropsWithoutRef<'div'>>(
  function DropdownMenuSeparator({ className, ...rest }, ref) {
    return (
      <div ref={ref} role="separator" className={clsx(styles.separator, className)} {...rest} />
    );
  },
);

const DropdownMenuLabel = forwardRef<HTMLDivElement, ComponentPropsWithoutRef<'div'>>(
  function DropdownMenuLabel({ className, ...rest }, ref) {
    return <div ref={ref} className={clsx(styles.label, className)} {...rest} />;
  },
);

/** Menu button: opens a menu with roving arrow-key focus, typeahead, and dismissal. */
export const DropdownMenu = Object.assign(DropdownMenuRoot, {
  Trigger: DropdownMenuTrigger,
  Content: DropdownMenuContent,
  Item: DropdownMenuItem,
  Separator: DropdownMenuSeparator,
  Label: DropdownMenuLabel,
});
