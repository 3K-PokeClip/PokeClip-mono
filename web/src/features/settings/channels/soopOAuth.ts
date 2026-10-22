'use client';

// SOOP 동의 화면 진입·복귀 (POK-396). 치지직처럼 동의 주소와 서명된 state를 서버가 만든다.
// 다른 점 하나: SOOP 공식 문서에 동의 복귀가 state를 돌려준다는 말이 없다(2026-10-23 확인).
// 그래서 나갈 때 주소 안의 state를 sessionStorage에 두고, 돌아와 쿼리에 state가 없으면 그 값으로
// 완료한다. 검증은 여전히 서버가 한다(서명·회원·만료). 여기 두는 것은 되돌려줄 값뿐이다.
//
// 치지직의 콜백 주소 불일치 경고(warnIfCallbackMismatch)는 만들지 않는다: 서버가 동의 주소에
// redirect_uri를 싣지 않아(콘솔 등록값을 SOOP이 쓴다) 비교할 재료가 없다(계획 검증 M10).

/** SOOP 개발자 콘솔에 등록하는 Redirect URL의 경로. 백엔드 `SOOP_REDIRECT_URI`와 짝이다. */
export const SOOP_CALLBACK_PATH = '/oauth/soop/callback';

export const SOOP_STATE_KEY = 'pokeclip.soop.state';

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

/** 나가기 직전 주소 안의 state를 둔다. state가 없는 주소면 옛 값을 지운다(다른 시도의 값이 섞이지 않게). */
export function rememberSoopState(authorizeUrl: string): void {
  const state = new URL(authorizeUrl).searchParams.get('state');
  if (state) window.sessionStorage.setItem(SOOP_STATE_KEY, state);
  else window.sessionStorage.removeItem(SOOP_STATE_KEY);
}

/** 콜백에서 쓸 state. 쿼리 값이 앞이고, 없으면 둔 값. 어느 쪽이든 둔 값은 지운다. 둘 다 없으면 null. */
export function takeSoopState(queryState: string | null): string | null {
  const stored = window.sessionStorage.getItem(SOOP_STATE_KEY);
  window.sessionStorage.removeItem(SOOP_STATE_KEY);
  return queryState || stored || null;
}

/** 동의 화면으로 나간다. jsdom이 location.assign을 못 흉내 내 시험에서 이 함수만 모킹한다(치지직과 같다). */
export function goToSoopConsent(authorizeUrl: string) {
  window.location.assign(authorizeUrl);
}
