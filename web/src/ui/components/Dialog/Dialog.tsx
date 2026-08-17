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
import styles from './Dialog.module.css';

interface DialogContextValue {
  open: boolean;
  setOpen: (open: boolean) => void;
  titleId: string;
  descriptionId: string;
  hasTitle: boolean;
  hasDescription: boolean;
  registerTitle: (present: boolean) => void;
  registerDescription: (present: boolean) => void;
}
const DialogContext = createContext<DialogContextValue | null>(null);
function useDialog(): DialogContextValue {
  const c = useContext(DialogContext);
  if (!c) throw new Error('Dialog subcomponents must be used within <Dialog>.');
  return c;
}

export interface DialogProps {
  children: ReactNode;
  open?: boolean;
  defaultOpen?: boolean;
  onOpenChange?: (open: boolean) => void;
}

function DialogRoot({ children, open, defaultOpen = false, onOpenChange }: DialogProps) {
  const [isOpen, setOpen] = useControllableState<boolean>({
    value: open,
    defaultValue: defaultOpen,
    onChange: onOpenChange,
  });
  const base = useId();
  const [hasTitle, setHasTitle] = useState(false);
  const [hasDescription, setHasDescription] = useState(false);
  return (
    <DialogContext.Provider
      value={{
        open: isOpen,
        setOpen,
        titleId: `${base}-title`,
        descriptionId: `${base}-desc`,
        hasTitle,
        hasDescription,
        registerTitle: setHasTitle,
        registerDescription: setHasDescription,
      }}
    >
      {children}
    </DialogContext.Provider>
  );
}

const DialogTrigger = forwardRef<HTMLElement, { children: ReactElement }>(function DialogTrigger(
  { children },
  ref,
) {
  const ctx = useDialog();
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

const DialogContent = forwardRef<HTMLDivElement, ComponentPropsWithoutRef<'div'>>(
  function DialogContent({ className, children, ...rest }, ref) {
    const ctx = useDialog();
    useScrollLock(ctx.open);
    if (!ctx.open) return null;
    return (
      <Portal>
        <div className={styles.overlay}>
          <FocusScope>
            <DismissableLayer onDismiss={() => ctx.setOpen(false)}>
              <div
                ref={ref}
                role="dialog"
                aria-modal="true"
                aria-labelledby={ctx.hasTitle ? ctx.titleId : undefined}
                aria-describedby={ctx.hasDescription ? ctx.descriptionId : undefined}
                className={clsx(styles.content, className)}
                {...rest}
              >
                {children}
              </div>
            </DismissableLayer>
          </FocusScope>
        </div>
      </Portal>
    );
  },
);

const DialogTitle = forwardRef<HTMLHeadingElement, ComponentPropsWithoutRef<'h2'>>(
  function DialogTitle({ className, ...rest }, ref) {
    const ctx = useDialog();
    useEffect(() => {
      ctx.registerTitle(true);
      return () => ctx.registerTitle(false);
      // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);
    return <h2 ref={ref} id={ctx.titleId} className={clsx(styles.title, className)} {...rest} />;
  },
);

const DialogDescription = forwardRef<HTMLParagraphElement, ComponentPropsWithoutRef<'p'>>(
  function DialogDescription({ className, ...rest }, ref) {
    const ctx = useDialog();
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

const DialogClose = forwardRef<HTMLElement, { children: ReactElement }>(function DialogClose(
  { children },
  ref,
) {
  const ctx = useDialog();
  return (
    <Slot ref={ref} onClick={() => ctx.setOpen(false)}>
      {children}
    </Slot>
  );
});

/** Modal dialog: focus trap, scroll lock, Esc/backdrop dismiss, ARIA wiring. */
export const Dialog = Object.assign(DialogRoot, {
  Trigger: DialogTrigger,
  Content: DialogContent,
  Title: DialogTitle,
  Description: DialogDescription,
  Close: DialogClose,
});
