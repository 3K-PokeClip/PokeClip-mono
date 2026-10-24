import { forwardRef, type ComponentPropsWithoutRef } from 'react';
import { VisuallyHidden } from '../../primitives/VisuallyHidden';

export interface LoadingRegionProps extends ComponentPropsWithoutRef<'div'> {
  /** 낭독기가 읽는 한 줄 — 「지난 방송을 불러오는 중」처럼 무엇을 기다리는지 말한다. */
  label: string;
}

/**
 * 스켈레톤을 감싸는 로딩 영역 — `aria-busy`와 숨은 `role="status"` 한 줄.
 *
 * 이름을 컨테이너의 `aria-label`로 주지 않는 이유: `div`·`ul`(generic 역할)에 붙인 `aria-label`은 ARIA 1.2가 금지해
 * 낭독기가 대부분 무시한다. 안의 `Skeleton`은 모두 `aria-hidden`이라 이 status 하나만 읽힌다.
 * 목록 모양 스켈레톤은 `ul`을 이 영역 안에 둔다 — `ul` 바로 아래엔 `li`만 설 수 있어 status를 넣을 수 없다.
 */
export const LoadingRegion = forwardRef<HTMLDivElement, LoadingRegionProps>(function LoadingRegion(
  { label, children, ...rest },
  ref,
) {
  return (
    <div ref={ref} aria-busy="true" {...rest}>
      <VisuallyHidden role="status">{label}</VisuallyHidden>
      {children}
    </div>
  );
});
