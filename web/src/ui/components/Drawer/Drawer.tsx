import {
  createContext,
  forwardRef,
  useContext,
  useEffect,
  useState,
  type ComponentPropsWithoutRef,
  type ReactElement,
  type ReactNode,
} from 'react';
import clsx from 'clsx';
import { Portal } from '../../primitives/Portal';
import { Slot } from '../../primitives/Slot';
import { FocusScope } from '../../primitives/FocusScope';
import { DismissableLayer } from '../../primitives/DismissableLayer';
import { useControllableState } from '../../primitives/hooks/useControllableState';
import { useScrollLock } from '../../primitives/hooks/useScrollLock';
import { useId } from '../../primitives/hooks/useId';
import styles from './Drawer.module.css';

type DrawerSide = 'left' | 'right' | 'top' | 'bottom';

interface DrawerContextValue {
  open: boolean;
  setOpen: (open: boolean) => void;
  side: DrawerSide;
  titleId: string;
  descriptionId: string;
  hasTitle: boolean;
  hasDescription: boolean;
  registerTitle: (present: boolean) => void;
  registerDescription: (present: boolean) => void;
}
const DrawerContext = createContext<DrawerContextValue | null>(null);
function useDrawer(): DrawerContextValue {
  const c = useContext(DrawerContext);
  if (!c) throw new Error('Drawer subcomponents must be used within <Drawer>.');
  return c;
}

export interface DrawerProps {
  children: ReactNode;
  open?: boolean;
  defaultOpen?: boolean;
  onOpenChange?: (open: boolean) => void;
  side?: DrawerSide;
}

function DrawerRoot({
  children,
  open,
  defaultOpen = false,
  onOpenChange,
  side = 'right',
}: DrawerProps) {
  const [isOpen, setOpen] = useControllableState<boolean>({
    value: open,
    defaultValue: defaultOpen,
    onChange: onOpenChange,
  });
  const base = useId();
  const [hasTitle, setHasTitle] = useState(false);
  const [hasDescription, setHasDescription] = useState(false);
  return (
    <DrawerContext.Provider
      value={{
        open: isOpen,
        setOpen,
        side,
        titleId: `${base}-title`,
        descriptionId: `${base}-desc`,
        hasTitle,
        hasDescription,
        registerTitle: setHasTitle,
        registerDescription: setHasDescription,
      }}
    >
      {children}
    </DrawerContext.Provider>
  );
}

const DrawerTrigger = forwardRef<HTMLElement, { children: ReactElement }>(function DrawerTrigger(
  { children },
  ref,
) {
  const ctx = useDrawer();
  return (
    <Slot
      ref={ref}
      onClick={() => ctx.setOpen(true)}
      aria-haspopup="dialog"
      aria-expanded={ctx.open}
    >
      {children}
    </Slot>
  );
});

const DrawerContent = forwardRef<HTMLDivElement, ComponentPropsWithoutRef<'div'>>(
  function DrawerContent({ className, children, ...rest }, ref) {
    const ctx = useDrawer();
    useScrollLock(ctx.open);
    if (!ctx.open) return null;
    return (
      <Portal>
        <div className={styles.overlay} />
        <FocusScope>
          <DismissableLayer onDismiss={() => ctx.setOpen(false)}>
            <div
              ref={ref}
              role="dialog"
              aria-modal="true"
              data-side={ctx.side}
              aria-labelledby={ctx.hasTitle ? ctx.titleId : undefined}
              aria-describedby={ctx.hasDescription ? ctx.descriptionId : undefined}
              className={clsx(styles.content, className)}
              {...rest}
            >
              {children}
            </div>
          </DismissableLayer>
        </FocusScope>
      </Portal>
    );
  },
);

const DrawerTitle = forwardRef<HTMLHeadingElement, ComponentPropsWithoutRef<'h2'>>(
  function DrawerTitle({ className, ...rest }, ref) {
    const ctx = useDrawer();
    useEffect(() => {
      ctx.registerTitle(true);
      return () => ctx.registerTitle(false);
      // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);
    return <h2 ref={ref} id={ctx.titleId} className={clsx(styles.title, className)} {...rest} />;
  },
);

const DrawerDescription = forwardRef<HTMLParagraphElement, ComponentPropsWithoutRef<'p'>>(
  function DrawerDescription({ className, ...rest }, ref) {
    const ctx = useDrawer();
    useEffect(() => {
      ctx.registerDescription(true);
      return () => ctx.registerDescription(false);
      // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);
    return (
      <p
        ref={ref}
        id={ctx.descriptionId}
        className={clsx(styles.description, className)}
        {...rest}
      />
    );
  },
);

const DrawerClose = forwardRef<HTMLElement, { children: ReactElement }>(function DrawerClose(
  { children },
  ref,
) {
  const ctx = useDrawer();
  return (
    <Slot ref={ref} onClick={() => ctx.setOpen(false)}>
      {children}
    </Slot>
  );
});

/** Edge-anchored modal sheet (drawer). Focus trap, scroll lock, Esc/backdrop dismiss. */
export const Drawer = Object.assign(DrawerRoot, {
  Trigger: DrawerTrigger,
  Content: DrawerContent,
  Title: DrawerTitle,
  Description: DrawerDescription,
  Close: DrawerClose,
});
