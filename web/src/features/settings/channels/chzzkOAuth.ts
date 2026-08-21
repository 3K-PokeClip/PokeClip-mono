'use client';

// 치지직 동의 화면 진입·복귀 (POK-205) — 구글과 달리 동의 URL과 `state`를 **서버가** 만든다
// (ChzzkLinkService.startUrl, HMAC 서명 + TTL 10분). 그래서 프론트에는 sessionStorage에
// state를 심고 돌아와 비교하는 googleOAuth의 절차가 없다. 이동과 콜백 자리만 갖는다.

/**
 * 치지직 개발자 센터에 등록된 redirect URI의 **경로**. 개발자 센터는 앱당 하나만 등록하므로
 * 환경마다 앱을 따로 파고, 경로는 전 환경 공통으로 이 값을 쓴다. 백엔드 `CHZZK_REDIRECT_URI`가
 * `{web origin}` + 이 경로와 정확히 같아야 왕복이 성립한다.
 */
export const CHZZK_CALLBACK_PATH = '/oauth/chzzk/callback';

/** 왕복이 끝나면 돌아갈 자리 — 사용자가 「연동」을 누른 화면이다. */
export const CHANNEL_SETTINGS_PATH = '/settings/channels';

/**
 * 동의 화면으로 이동한다. 치지직 오리진으로 나가는 하드 내비게이션이라 router를 쓰지 않는다.
 * jsdom이 location.assign을 흉내 내지 못하므로 모듈에 가둬 테스트에서 통째로 모킹한다
 * (googleOAuth와 같은 이유·같은 모양).
 */
export function goToChzzkConsent(authorizeUrl: string) {
  window.location.assign(authorizeUrl);
}

/**
 * 서버가 조립한 authorizeUrl의 `redirectUri`가 지금 이 오리진의 콜백 자리를 가리키는지.
 * 치지직 규격이라 파라미터 이름이 표준 OAuth의 `redirect_uri`가 아니라 camelCase다.
 */
export function callbackMatchesOrigin(authorizeUrl: string, origin: string): boolean {
  try {
    const redirectUri = new URL(authorizeUrl).searchParams.get('redirectUri');
    if (redirectUri === null) return false;
    const url = new URL(redirectUri);
    return url.origin === origin && url.pathname === CHZZK_CALLBACK_PATH;
  } catch {
    /* authorizeUrl·redirectUri가 URL이 아니다 — 불일치로 본다 */
    return false;
  }
}

/**
 * 등록 주소 불일치 조기 경고 — **개발 빌드에서만**. 이게 없으면 증상이 "동의를 다 마친 뒤
 * 낯선 주소에서 막힘"으로만 나타나고, 원인을 찾는 동안 state TTL 10분을 매번 태운다.
 *
 * **막지는 않는다.** 백엔드가 권위이고, 프록시·CDN 뒤에서 오리진이 정당하게 다를 수 있다.
 * 서버가 준 주소를 로그에 옮기지도 않는다 — 우리가 기대하는 값만 적는다.
 */
export function warnIfCallbackMismatch(authorizeUrl: string) {
  if (process.env.NODE_ENV === 'production') return;
  if (callbackMatchesOrigin(authorizeUrl, window.location.origin)) return;
  console.warn(
    `[chzzk] 동의 URL의 redirectUri가 ${window.location.origin}${CHZZK_CALLBACK_PATH}와 다르다 — ` +
      '치지직 개발자 센터 등록값과 백엔드 CHZZK_REDIRECT_URI를 확인하라. 동의를 마쳐도 돌아오지 못한다.',
  );
}
