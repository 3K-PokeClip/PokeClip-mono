'use client';

import { useLayoutEffect, useState } from 'react';
import { TOUR_STEPS, isStepVisible } from './tourSteps';

// 시작 가이드가 DOM에서 읽는 것 — 스포트라이트 타깃의 유무와 「홈을 아직 읽는 중」. tourSteps.ts는 DOM을 모르는 순수 모델로 둔다.

export function hasTargetInDom(targetId: string): boolean {
  return document.querySelector(`[data-tour-id="${targetId}"]`) !== null;
}

/** 홈이 아직 첫 응답을 기다리는 중 — 큰 칸 스켈레톤이 data-tour-pending을 단다. 그동안 뒤 단계 타깃이 없는 것은 정상이다 */
export function isHomePending(): boolean {
  return document.querySelector('[data-tour-pending]') !== null;
}

export interface TourVisibility {
  /** 지금 화면에서 보여 줄 단계의 TOUR_STEPS 인덱스(오름차순) — 막 가입 계정은 4개, 기록 있는 계정은 5개 */
  visible: readonly number[];
  /** 홈을 아직 읽는 중이라 몇 단계가 보일지 모른다 */
  pending: boolean;
}

function read(): TourVisibility {
  return {
    visible: TOUR_STEPS.flatMap((_, i) => (isStepVisible(i, hasTargetInDom) ? [i] : [])),
    pending: isHomePending(),
  };
}

function same(a: TourVisibility, b: TourVisibility): boolean {
  return (
    a.pending === b.pending &&
    a.visible.length === b.visible.length &&
    a.visible.every((v, i) => v === b.visible[i])
  );
}

/**
 * 지금 화면에서 보일 단계와 「홈 읽는 중」을 DOM 변화마다 다시 읽는다. 배지(n/전체)·점·웰컴 목록이 이것으로 센다 —
 * TOUR_STEPS 6개를 그대로 세면 막 가입 계정은 4단계에서 「4/6」인 채 끝나 「왜 벌써 끝나지」가 된다(POK-378).
 * @param active 웰컴 창이나 투어가 떠 있을 때만 참 — 칩만 남으면 쓰는 곳이 없어 홈 폴링마다 다시 읽지 않는다(리뷰 #299 2차)
 */
export function useTourVisibility(active: boolean): TourVisibility {
  const [state, setState] = useState<TourVisibility>({ visible: [], pending: true });
  useLayoutEffect(() => {
    if (!active) return;
    let raf = 0;
    const update = () => {
      const next = read();
      setState((prev) => (same(prev, next) ? prev : next));
    };
    update();
    const observer = new MutationObserver(() => {
      cancelAnimationFrame(raf);
      raf = requestAnimationFrame(update);
    });
    observer.observe(document.body, {
      childList: true,
      subtree: true,
      attributes: true,
      attributeFilter: ['data-tour-id', 'data-tour-pending'],
    });
    return () => {
      cancelAnimationFrame(raf);
      observer.disconnect();
    };
  }, [active]);
  return state;
}
