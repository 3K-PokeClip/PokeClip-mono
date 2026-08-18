import { useCallback, useState, type RefCallback, type RefObject } from 'react';
import { useIsomorphicLayoutEffect } from '../hooks/useIsomorphicLayoutEffect';

export type Side = 'top' | 'bottom' | 'left' | 'right';
export type Align = 'start' | 'center' | 'end';

export interface Coords {
  top: number;
  left: number;
  side: Side;
}

const OPPOSITE: Record<Side, Side> = { top: 'bottom', bottom: 'top', left: 'right', right: 'left' };
const VIEWPORT_PAD = 8;

function compute(
  trigger: DOMRect,
  content: { width: number; height: number },
  side: Side,
  align: Align,
  offset: number,
): Coords {
  const vw = window.innerWidth;
  const vh = window.innerHeight;
  const space: Record<Side, number> = {
    top: trigger.top,
    bottom: vh - trigger.bottom,
    left: trigger.left,
    right: vw - trigger.right,
  };
  const need =
    side === 'top' || side === 'bottom' ? content.height + offset : content.width + offset;
  let resolved = side;
  if (space[side] < need && space[OPPOSITE[side]] > space[side]) resolved = OPPOSITE[side];

  let top = 0;
  let left = 0;
  if (resolved === 'top') top = trigger.top - content.height - offset;
  else if (resolved === 'bottom') top = trigger.bottom + offset;
  else if (resolved === 'left') left = trigger.left - content.width - offset;
  else left = trigger.right + offset;

  if (resolved === 'top' || resolved === 'bottom') {
    if (align === 'start') left = trigger.left;
    else if (align === 'end') left = trigger.right - content.width;
    else left = trigger.left + trigger.width / 2 - content.width / 2;
  } else {
    if (align === 'start') top = trigger.top;
    else if (align === 'end') top = trigger.bottom - content.height;
    else top = trigger.top + trigger.height / 2 - content.height / 2;
  }

  left = Math.max(VIEWPORT_PAD, Math.min(left, vw - content.width - VIEWPORT_PAD));
  top = Math.max(VIEWPORT_PAD, Math.min(top, vh - content.height - VIEWPORT_PAD));
  return { top, left, side: resolved };
}

export interface UseFloatingOptions {
  side?: Side;
  align?: Align;
  offset?: number;
  open: boolean;
}

/** Computes viewport-relative coordinates (for `position: fixed`) with basic edge-flip. */
export function useFloating(
  triggerRef: RefObject<HTMLElement | null>,
  contentRef: RefObject<HTMLElement | null>,
  { side = 'bottom', align = 'center', offset = 8, open }: UseFloatingOptions,
): { coords: Coords; update: () => void } {
  const [coords, setCoords] = useState<Coords>({ top: 0, left: 0, side });

  const update = useCallback(() => {
    const t = triggerRef.current;
    const c = contentRef.current;
    if (!t || !c) return;
    setCoords(
      compute(
        t.getBoundingClientRect(),
        { width: c.offsetWidth, height: c.offsetHeight },
        side,
        align,
        offset,
      ),
    );
  }, [triggerRef, contentRef, side, align, offset]);

  useIsomorphicLayoutEffect(() => {
    if (!open) return;
    update();
    window.addEventListener('scroll', update, true);
    window.addEventListener('resize', update);
    return () => {
      window.removeEventListener('scroll', update, true);
      window.removeEventListener('resize', update);
    };
  }, [open, update]);

  return { coords, update };
}

/**
 * Portal 콘텐츠용 재측정 ref (useFloating과 짝) — Portal은 SSR 안전을 위해 마운트
 * 확인 후에야 children을 그리므로, 열림 커밋의 레이아웃 이펙트 시점엔 콘텐츠 DOM이
 * 없어 첫 측정이 무산된다. 그대로 두면 팝업이 초기 좌표 좌상단에 붙은 채 스크롤·
 * 리사이즈에만 복구된다. 이 콜백을 콘텐츠 ref "뒤에" 합성하면(측정 대상 ref가 먼저
 * 채워진 뒤) DOM이 붙는 순간 — 페인트 전 — 다시 재서 첫 프레임부터 제자리에 그려진다.
 */
export function useMeasureOnAttach(update: () => void): RefCallback<HTMLElement> {
  return useCallback(
    (node) => {
      if (node !== null) update();
    },
    [update],
  );
}
