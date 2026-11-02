import { vi } from 'vitest';

/**
 * jsdom은 레이아웃이 없어 스크롤 치수가 전부 0이다. 목록에 값을 꽂아 넘친 상태를 흉내 낸다.
 * scrollTop 세터는 spy로 둔다: 「끝에 있을 때만 바닥으로 붙인다」를 관찰할 유일한 창구다
 * (빈 함수로 두면 그 동작이 통째로 빠져도 테스트가 전부 초록이다).
 */
export function fakeScrollMetrics(
  el: HTMLElement,
  { scrollHeight, clientHeight, scrollTop }: Record<string, number>,
) {
  const setScrollTop = vi.fn();
  Object.defineProperty(el, 'scrollHeight', { configurable: true, get: () => scrollHeight });
  Object.defineProperty(el, 'clientHeight', { configurable: true, get: () => clientHeight });
  Object.defineProperty(el, 'scrollTop', {
    configurable: true,
    get: () => scrollTop,
    set: setScrollTop,
  });
  return setScrollTop;
}
