import {
  createContext,
  forwardRef,
  useContext,
  useRef,
  type ComponentPropsWithoutRef,
  type ReactElement,
  type ReactNode,
  type RefObject,
} from 'react';
import clsx from 'clsx';
import { Portal } from '../../primitives/Portal';
import { Slot } from '../../primitives/Slot';
import { FocusScope } from '../../primitives/FocusScope';
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
import styles from './Popover.module.css';

interface PopoverContextValue {
  open: boolean;
  setOpen: (open: boolean) => void;
  triggerRef: RefObject<HTMLElement | null>;
  contentRef: RefObject<HTMLDivElement | null>;
  contentId: string;
  side: Side;
  align: Align;
}
const PopoverContext = createContext<PopoverContextValue | null>(null);
function usePopover(): PopoverContextValue {
  const c = useContext(PopoverContext);
  if (!c) throw new Error('Popover subcomponents must be used within <Popover>.');
  return c;
}

export interface PopoverProps {
  children: ReactNode;
  open?: boolean;
  defaultOpen?: boolean;
  onOpenChange?: (open: boolean) => void;
  side?: Side;
  align?: Align;
}

function PopoverRoot({
  children,
  open,
  defaultOpen = false,
  onOpenChange,
  side = 'bottom',
  align = 'center',
}: PopoverProps) {
  const [isOpen, setOpen] = useControllableState<boolean>({
    value: open,
    defaultValue: defaultOpen,
    onChange: onOpenChange,
  });
  const triggerRef = useRef<HTMLElement>(null);
  const contentRef = useRef<HTMLDivElement>(null);
  const contentId = useId();
  return (
    <PopoverContext.Provider
      value={{ open: isOpen, setOpen, triggerRef, contentRef, contentId, side, align }}
    >
      {children}
    </PopoverContext.Provider>
  );
}

const PopoverTrigger = forwardRef<HTMLElement, { children: ReactElement }>(function PopoverTrigger(
  { children },
  ref,
) {
  const ctx = usePopover();
  const composedRef = useComposedRefs<HTMLElement>(ref, ctx.triggerRef);
  return (
    <Slot
      ref={composedRef}
      onClick={() => ctx.setOpen(!ctx.open)}
      aria-haspopup="dialog"
      aria-expanded={ctx.open}
      aria-controls={ctx.open ? ctx.contentId : undefined}
    >
      {children}
    </Slot>
  );
});

interface PopoverContentProps extends ComponentPropsWithoutRef<'div'> {
  side?: Side;
  align?: Align;
}
const PopoverContent = forwardRef<HTMLDivElement, PopoverContentProps>(function PopoverContent(
  { className, children, side, align, ...rest },
  ref,
) {
  const ctx = usePopover();
  const { coords, update } = useFloating(ctx.triggerRef, ctx.contentRef, {
    side: side ?? ctx.side,
    align: align ?? ctx.align,
    open: ctx.open,
  });
  const measureRef = useMeasureOnAttach(update);
  const composedRef = useComposedRefs<HTMLDivElement>(ref, ctx.contentRef, measureRef);
  if (!ctx.open) return null;
  return (
    <Portal>
      <FocusScope>
        <DismissableLayer onDismiss={() => ctx.setOpen(false)} excludeRefs={[ctx.triggerRef]}>
          <div
            ref={composedRef}
            role="dialog"
            id={ctx.contentId}
            className={clsx(styles.content, className)}
            style={{ position: 'fixed', top: coords.top, left: coords.left }}
            {...rest}
          >
            {children}
          </div>
        </DismissableLayer>
      </FocusScope>
    </Portal>
  );
});

/** Non-modal floating panel anchored to a trigger. Traps focus + dismisses on Esc/outside. */
export const Popover = Object.assign(PopoverRoot, {
  Trigger: PopoverTrigger,
  Content: PopoverContent,
});
