/** 독 탭 순서와 전환 방향. 렌더와 분리해 둔 이유는 transitionTypes가 DOM 속성으로
 *  나가지 않아 렌더 테스트로는 검증할 수 없기 때문이다 — 순수 함수로 직접 테스트한다. */

export const DOCK_HREFS = ['/home', '/broadcast', '/clips', '/settings'] as const;

export type DockHref = (typeof DOCK_HREFS)[number];

/** 오른쪽 탭으로 가면 forward, 왼쪽 탭으로 가면 back. shell.css의 뷰 트랜지션 클래스명과 짝이다. */
export type DockTransitionType = 'dock-forward' | 'dock-back';

/** 하위 경로도 그 탭으로 친다 — /settings/plugin은 설정 탭이다. */
export function isDockHrefActive(pathname: string, href: string): boolean {
  return pathname === href || pathname.startsWith(`${href}/`);
}

/** 현재 경로의 탭 인덱스. 독 밖의 경로(로그인 등)면 -1. */
export function dockIndexOf(pathname: string): number {
  return DOCK_HREFS.findIndex((href) => isDockHrefActive(pathname, href));
}

/** 방향이 없으면 undefined — 같은 탭이거나 독 밖에서 들어오는 경우다.
 *  이때 <Link>에 타입을 싣지 않으면 ScreenTransition의 default:'none'이 걸려 슬라이드가 돌지 않는다. */
export function dockTransitionType(
  fromPath: string,
  toHref: DockHref,
): DockTransitionType | undefined {
  const from = dockIndexOf(fromPath);
  if (from < 0) return undefined;

  const to = DOCK_HREFS.indexOf(toHref);
  if (to < 0 || to === from) return undefined;

  return to > from ? 'dock-forward' : 'dock-back';
}
