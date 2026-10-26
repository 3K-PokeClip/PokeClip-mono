// OAuth 콜백 주소의 1회용 값 지우기 (POK-482). 콜백 화면이 code·state를 읽은 뒤 곧바로 부른다.
// 교환이 끝나면 대개 다른 화면으로 replace하지만, 실패 안내·로그인 필요처럼 이 화면에 머무는 갈래에서는
// 값이 주소창·히스토리에 남아 복사·공유로 샌다. 요청의 Referer는 next.config.ts의 Referrer-Policy가 막는다.

const OAUTH_PARAMS = ['code', 'state'];

/**
 * 현재 주소에서 `code`·`state`만 떼어 히스토리 항목을 바꿔 쓴다. 경로·다른 쿼리·해시는 그대로 두고,
 * 지울 것이 없으면 히스토리를 건드리지 않는다.
 */
export function stripOAuthParamsFromUrl(): void {
  const url = new URL(window.location.href);
  if (!OAUTH_PARAMS.some((name) => url.searchParams.has(name))) return;
  for (const name of OAUTH_PARAMS) {
    url.searchParams.delete(name);
  }
  // 상태 인자에 라우터 내부 키(__NA·_N)가 없어야 Next 라우터가 바뀐 주소를 받아 useSearchParams와 맞춘다(Next 문서
  // 「Native History API」, app-router의 replaceState 패치). 지금 항목의 상태(window.history.state)는 그 키를
  // 갖고 있어 넘기면 라우터가 옛 주소를 쥔 채로 남고, 다음 라우터 갱신 때 그 주소(code 포함)로 replaceState해
  // 되살린다(app-router의 HistoryUpdater). 넘길 상태가 없으니 null을 쓴다.
  window.history.replaceState(null, '', url.pathname + url.search + url.hash);
}
