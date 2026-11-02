import Link from 'next/link';
import type { AnchorHTMLAttributes } from 'react';

/**
 * 히스토리를 남기지 않는 링크. OAuth 콜백 주소는 `code`를 한 번 쓰고 버린 자리라, 뒤로
 * 가기로 돌아오면 소모된 값으로 재교환을 시도하게 된다. `next/link`의 `replace`는 앵커
 * 속성이 아니라 `LinkButton` 타입으로는 못 넘기므로, 모듈 스코프 래퍼로 고정해 넘긴다.
 *
 * 구글·치지직 두 콜백 화면이 함께 쓴다 — 한쪽만 고쳐져 뒤로 가기 동작이 갈리지 않게 하나로 둔다.
 */
export function ReplaceLink(props: AnchorHTMLAttributes<HTMLAnchorElement> & { href: string }) {
  return <Link {...props} replace />;
}
