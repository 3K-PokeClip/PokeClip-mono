import { useRef, useState, type ReactElement, type ReactNode } from 'react';
import { Portal } from '../../primitives/Portal';
import { Slot } from '../../primitives/Slot';
import { useId } from '../../primitives/hooks/useId';
import { useFloating, type Align, type Side } from '../../primitives/positioning';
import styles from './Tooltip.module.css';

export interface TooltipProps {
  content: ReactNode;
  children: ReactElement;
  side?: Side;
  align?: Align;
  /** Open delay in ms. Default 300. */
  delay?: number;
}

/** Text hint shown on hover/focus of its trigger. Non-modal; no focus trap. */
export function Tooltip({
  content,
  children,
  side = 'top',
  align = 'center',
  delay = 300,
}: TooltipProps) {
  const [open, setOpen] = useState(false);
  const triggerRef = useRef<HTMLElement>(null);
  const contentRef = useRef<HTMLDivElement>(null);
  const timer = useRef<number | undefined>(undefined);
  const id = useId();
  const { coords } = useFloating(triggerRef, contentRef, { side, align, open });

  const show = () => {
    window.clearTimeout(timer.current);
    timer.current = window.setTimeout(() => setOpen(true), delay);
  };
  const hide = () => {
    window.clearTimeout(timer.current);
    setOpen(false);
  };

  return (
    <>
      <Slot
        ref={triggerRef}
        onMouseEnter={show}
        onMouseLeave={hide}
        onFocus={show}
        onBlur={hide}
        aria-describedby={open ? id : undefined}
      >
        {children}
      </Slot>
      {open ? (
        <Portal>
          <div
            ref={contentRef}
            role="tooltip"
            id={id}
            className={styles.tooltip}
            style={{ position: 'fixed', top: coords.top, left: coords.left }}
          >
            {content}
          </div>
        </Portal>
      ) : null}
    </>
  );
}
