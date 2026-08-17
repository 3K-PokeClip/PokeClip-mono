import {
  forwardRef,
  useRef,
  type ComponentPropsWithoutRef,
  type KeyboardEvent,
  type PointerEvent,
} from 'react';
import clsx from 'clsx';
import { useControllableState } from '../../primitives/hooks/useControllableState';
import styles from './Slider.module.css';

export interface SliderProps extends Omit<
  ComponentPropsWithoutRef<'div'>,
  'onChange' | 'defaultValue'
> {
  value?: number;
  defaultValue?: number;
  onValueChange?: (value: number) => void;
  min?: number;
  max?: number;
  step?: number;
  disabled?: boolean;
  label?: string;
}

/** Single-value slider with pointer drag and full keyboard control. */
export const Slider = forwardRef<HTMLDivElement, SliderProps>(function Slider(
  {
    value,
    defaultValue = 0,
    onValueChange,
    min = 0,
    max = 100,
    step = 1,
    disabled = false,
    label,
    className,
    ...rest
  },
  ref,
) {
  const [val, setVal] = useControllableState<number>({
    value,
    defaultValue,
    onChange: onValueChange,
  });
  const trackRef = useRef<HTMLDivElement>(null);
  const thumbRef = useRef<HTMLDivElement>(null);

  const clamp = (v: number) => Math.min(max, Math.max(min, v));
  const snap = (v: number) => clamp(Math.round((v - min) / step) * step + min);
  const pct = max === min ? 0 : ((val - min) / (max - min)) * 100;

  const setFromClientX = (clientX: number) => {
    const track = trackRef.current;
    if (!track) return;
    const rect = track.getBoundingClientRect();
    const ratio = rect.width === 0 ? 0 : (clientX - rect.left) / rect.width;
    setVal(snap(min + ratio * (max - min)));
  };

  const onPointerDown = (e: PointerEvent<HTMLDivElement>) => {
    if (disabled) return;
    e.currentTarget.setPointerCapture(e.pointerId);
    thumbRef.current?.focus();
    setFromClientX(e.clientX);
  };
  const onPointerMove = (e: PointerEvent<HTMLDivElement>) => {
    if (disabled || !e.currentTarget.hasPointerCapture(e.pointerId)) return;
    setFromClientX(e.clientX);
  };

  const onKeyDown = (e: KeyboardEvent<HTMLDivElement>) => {
    let next = val;
    if (e.key === 'ArrowRight' || e.key === 'ArrowUp') next = val + step;
    else if (e.key === 'ArrowLeft' || e.key === 'ArrowDown') next = val - step;
    else if (e.key === 'Home') next = min;
    else if (e.key === 'End') next = max;
    else if (e.key === 'PageUp') next = val + step * 10;
    else if (e.key === 'PageDown') next = val - step * 10;
    else return;
    e.preventDefault();
    setVal(snap(next));
  };

  return (
    <div
      ref={ref}
      className={clsx(styles.root, className)}
      data-disabled={disabled || undefined}
      onPointerDown={onPointerDown}
      onPointerMove={onPointerMove}
      {...rest}
    >
      <div ref={trackRef} className={styles.track}>
        <div className={styles.range} style={{ width: `${pct}%` }} />
        <div
          ref={thumbRef}
          className={styles.thumb}
          role="slider"
          tabIndex={disabled ? -1 : 0}
          aria-valuemin={min}
          aria-valuemax={max}
          aria-valuenow={val}
          aria-label={label}
          aria-disabled={disabled || undefined}
          onKeyDown={onKeyDown}
          style={{ left: `${pct}%` }}
        />
      </div>
    </div>
  );
});
