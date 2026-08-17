import { useCallback, useEffect, useRef, useState, type RefCallback } from 'react';

export interface PresenceState {
  isMounted: boolean;
  ref: RefCallback<HTMLElement>;
}

/** duration은 "0s, 0.3s"처럼 쉼표 목록일 수 있어 하나라도 0보다 큰지로 판별한다. */
export function hasPositiveDuration(value: string): boolean {
  return value.split(',').some((d) => parseFloat(d) > 0);
}

/** Keeps a node mounted through its CSS exit animation after `present` flips to false. */
export function usePresence(present: boolean): PresenceState {
  const [mounted, setMounted] = useState(present);
  const nodeRef = useRef<HTMLElement | null>(null);

  useEffect(() => {
    if (present) {
      setMounted(true);
      return;
    }
    const node = nodeRef.current;
    if (!node) {
      setMounted(false);
      return;
    }
    const styles = getComputedStyle(node);
    const animates =
      (styles.animationName !== 'none' && hasPositiveDuration(styles.animationDuration)) ||
      hasPositiveDuration(styles.transitionDuration);
    if (!animates) {
      setMounted(false);
      return;
    }
    let done = false;
    const finish = () => {
      if (done) return;
      done = true;
      setMounted(false);
    };
    node.addEventListener('animationend', finish);
    node.addEventListener('transitionend', finish);
    const timeout = window.setTimeout(finish, 1200);
    return () => {
      node.removeEventListener('animationend', finish);
      node.removeEventListener('transitionend', finish);
      window.clearTimeout(timeout);
    };
  }, [present]);

  const ref = useCallback<RefCallback<HTMLElement>>((el) => {
    nodeRef.current = el;
  }, []);

  return { isMounted: mounted, ref };
}
