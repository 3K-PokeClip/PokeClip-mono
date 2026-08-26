'use client';

// 구글(유튜브) 동의 화면 진입·복귀 (POK-221) — 치지직(chzzkOAuth)과 같은 모양이다:
// 동의 URL과 `state`를 **서버가** 만든다 (YoutubeLinkService.startUrl, HMAC 서명 + TTL 10분).
// 그래서 프론트에는 sessionStorage에 state를 심고 돌아와 비교하는 googleOAuth의 절차가
// 없다. 이동과 콜백 자리만 갖는다.

/**
 * GCP 콘솔에 등록된 redirect URI의 **경로**. 백엔드 `YOUTUBE_REDIRECT_URI`가
 * `{web origin}` + 이 경로와 정확히 같아야 왕복이 성립한다. 구글은 리다이렉트 URI를
 * 여러 개 등록할 수 있어 치지직과 달리 환경마다 앱을 나눌 필요는 없다.
 */
export const YOUTUBE_CALLBACK_PATH = '/oauth/youtube/callback';

// 왕복이 끝나면 돌아갈 자리 — 두 콜백이 같은 화면으로 돌아가므로 정의는 chzzkOAuth
// 한 곳에만 두고 재수출한다. 두 곳에 두면 갈라진다.
export { CHANNEL_SETTINGS_PATH } from './chzzkOAuth';

/**
 * 동의 URL이 우리가 보낼 수 있는 주소인지 확인한다. 값 자체는 우리 백엔드가 주지만
 * (`YoutubeLinkService.startUrl`), 설정이 바뀌거나 프록시 응답이 변조되면 `javascript:`
 * 같은 스킴이 그대로 `location.assign`에 실려 **우리 오리진에서 실행**된다. 입구에서 막는다.
 * 메시지에 URL을 담지 않는다 — 그 안에 서명된 state가 들어 있다.
 */
export function assertYoutubeConsentUrl(authorizeUrl: string): void {
  let url: URL;
  try {
    url = new URL(authorizeUrl);
  } catch {
    throw new Error('구글 동의 URL 형식이 올바르지 않다');
  }
  if (url.protocol !== 'https:') throw new Error('구글 동의 URL이 https가 아니다');
}

/**
 * 동의 화면으로 이동한다. 구글 오리진으로 나가는 하드 내비게이션이라 router를 쓰지 않는다.
 * jsdom이 location.assign을 흉내 내지 못하므로 모듈에 가둬 테스트에서 통째로 모킹한다
 * (chzzkOAuth와 같은 이유·같은 모양).
 */
export function goToYoutubeConsent(authorizeUrl: string) {
  window.location.assign(authorizeUrl);
}

/**
 * 서버가 조립한 authorizeUrl의 `redirect_uri`가 지금 이 오리진의 콜백 자리를 가리키는지.
 * 구글은 표준 OAuth 규격이라 파라미터가 **snake_case**다 — 치지직의 camelCase(`redirectUri`)를
 * 여기로 역복사하면 항상 불일치로 판정돼 dev 경고가 매번 찍힌다.
 */
export function callbackMatchesOrigin(authorizeUrl: string, origin: string): boolean {
  try {
    const redirectUri = new URL(authorizeUrl).searchParams.get('redirect_uri');
    if (redirectUri === null) return false;
    const url = new URL(redirectUri);
    return url.origin === origin && url.pathname === YOUTUBE_CALLBACK_PATH;
  } catch {
    /* authorizeUrl·redirect_uri가 URL이 아니다 — 불일치로 본다 */
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
    `[youtube] 동의 URL의 redirect_uri가 ${window.location.origin}${YOUTUBE_CALLBACK_PATH}와 다르다 — ` +
      'GCP 콘솔 등록값과 백엔드 YOUTUBE_REDIRECT_URI를 확인하라. 동의를 마쳐도 돌아오지 못한다.',
  );
}
