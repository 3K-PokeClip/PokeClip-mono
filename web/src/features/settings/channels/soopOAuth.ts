'use client';

// SOOP 동의 화면 진입·복귀 (POK-396). 치지직처럼 동의 주소와 서명된 state를 서버가 만든다.
// SOOP 공식 문서에는 동의 복귀가 state를 돌려준다는 말이 없지만, 실계정으로 확인하니 돌려준다
// (2026-10-23, 네 번 모두 `?code=…&state=…`). 그래서 웹은 state를 따로 나르지 않고 쿼리 값만 쓴다.
//
// 치지직의 콜백 주소 불일치 경고(warnIfCallbackMismatch)는 만들지 않는다: 서버가 동의 주소에
// redirect_uri를 싣지 않아(콘솔 등록값을 SOOP이 쓴다) 비교할 재료가 없다(계획 검증 M10).

/** SOOP 개발자 콘솔에 등록하는 Redirect URL의 경로. 백엔드 `SOOP_REDIRECT_URI`와 짝이다. */
export const SOOP_CALLBACK_PATH = '/oauth/soop/callback';

/**
 * 동의 주소가 https인지 확인한다. 아니면 `javascript:` 같은 스킴이 `location.assign`에 실려 우리
 * 오리진에서 실행될 수 있다. 메시지에 주소를 담지 않는다: 그 안에 서명된 state가 있다.
 */
export function assertSoopConsentUrl(authorizeUrl: string): void {
  let url: URL;
  try {
    url = new URL(authorizeUrl);
  } catch {
    throw new Error('SOOP 동의 URL 형식이 올바르지 않다');
  }
  if (url.protocol !== 'https:') throw new Error('SOOP 동의 URL이 https가 아니다');
}

/** 동의 화면으로 나간다. jsdom이 location.assign을 못 흉내 내 시험에서 이 함수만 모킹한다(치지직과 같다). */
export function goToSoopConsent(authorizeUrl: string) {
  window.location.assign(authorizeUrl);
}
