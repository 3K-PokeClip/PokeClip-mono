import {
  cloneElement,
  forwardRef,
  isValidElement,
  type HTMLAttributes,
  type ReactElement,
  type Ref,
} from 'react';
import clsx from 'clsx';
import { composeRefs } from '../hooks/useComposedRefs';

export interface SlotProps extends HTMLAttributes<HTMLElement> {
  children?: ReactElement;
}

type AnyProps = Record<string, unknown>;

function mergeProps(slotProps: AnyProps, childProps: AnyProps): AnyProps {
  const merged: AnyProps = { ...childProps };
  for (const key in slotProps) {
    const slotValue = slotProps[key];
    const childValue = childProps[key];
    if (/^on[A-Z]/.test(key)) {
      if (typeof slotValue === 'function' && typeof childValue === 'function') {
        merged[key] = (...args: unknown[]) => {
          (childValue as (...a: unknown[]) => void)(...args);
          (slotValue as (...a: unknown[]) => void)(...args);
        };
      } else if (typeof slotValue === 'function') {
        merged[key] = slotValue;
      }
    } else if (key === 'style') {
      merged[key] = { ...(slotValue as object), ...(childValue as object) };
    } else if (key === 'className') {
      merged[key] = clsx(slotValue as string, childValue as string);
    } else {
      merged[key] = slotValue;
    }
  }
  return merged;
}

/**
 * Merges its own props (and ref) onto its single child element. Powers `asChild`:
 * behaviour/attributes are injected onto a consumer-provided element instead of
 * wrapping it in an extra DOM node.
 */
export const Slot = forwardRef<HTMLElement, SlotProps>(function Slot(props, forwardedRef) {
  const { children, ...slotProps } = props;
  if (!isValidElement(children)) return null;

  const child = children as ReactElement & { ref?: Ref<HTMLElement> };
  const merged = mergeProps(slotProps as AnyProps, (child.props ?? {}) as AnyProps);
  merged.ref = child.ref ? composeRefs(forwardedRef, child.ref) : forwardedRef;

  return cloneElement(child, merged);
});
