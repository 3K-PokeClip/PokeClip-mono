import { useRef, useState, type ReactElement, type ReactNode } from 'react';
import { Portal } from '../../primitives/Portal';
import { Slot } from '../../primitives/Slot';
import { useId } from '../../primitives/hooks/useId';
import { useComposedRefs } from '../../primitives/hooks/useComposedRefs';
import {
  useFloating,
  useMeasureOnAttach,
  type Align,
  type Side,
} from '../../primitives/positioning';
import styles from './Tooltip.module.css';

export interface TooltipProps {
  content: ReactNode;
  children: ReactElement;
  side?: Side;
  align?: Align;
  /** Open delay in ms. Default 300. */
  delay?: number;
  /**
   * Link the tooltip to its trigger via aria-describedby. Default true. Set false when the content only
   * repeats the trigger's accessible name (e.g. an icon button's aria-label) — otherwise screen readers
   * read the same words twice, as name and as description. The tooltip is then hidden from them.
   */
  describe?: boolean;
}

/** Text hint shown on hover/focus of its trigger. Non-modal; no focus trap. */
export function Tooltip({
  content,
  children,
  side = 'top',
  align = 'center',
  delay = 300,
  describe = true,
}: TooltipProps) {
  const [open, setOpen] = useState(false);
  const triggerRef = useRef<HTMLElement>(null);
  const contentRef = useRef<HTMLDivElement>(null);
  const timer = useRef<number | undefined>(undefined);
  const id = useId();
  const { coords, update } = useFloating(triggerRef, contentRef, { side, align, open });
  const measureRef = useMeasureOnAttach(update);
  const contentAttachRef = useComposedRefs<HTMLDivElement>(contentRef, measureRef);

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
        aria-describedby={open && describe ? id : undefined}
      >
        {children}
      </Slot>
      {open ? (
        <Portal>
          <div
            ref={contentAttachRef}
            role="tooltip"
            id={id}
            aria-hidden={describe ? undefined : true}
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
