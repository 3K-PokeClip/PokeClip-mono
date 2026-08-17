'use client';

// 구글 OAuth 동의 화면 진입 (POK-101) — authorization code 흐름의 앞반쪽.
// code→토큰 교환은 백엔드(POST /api/auth/google)가 client_secret으로 수행하므로
// 프론트는 동의 URL 조립과 state 검증만 책임진다.

const STATE_KEY = 'pc-oauth-state';

export interface OAuthState {
  state: string;
  /** 로그인 차단 전 가려던 경로 — 콜백 성공 후 복원한다. */
  returnTo: string | null;
}

/**
 * 동의 화면으로 이동한다. state는 CSRF 방지용 1회 난수 — 왕복 한 번에만 쓰이는
 * 탭 한정 값이라 sessionStorage가 맞다 (localStorage면 다른 탭 로그인과 섞인다).
 */
export function startGoogleLogin(returnTo?: string) {
  const clientId = process.env.NEXT_PUBLIC_GOOGLE_CLIENT_ID;
  if (!clientId) {
    // 배포 설정 오류다 — 조용히 넘어가면 "버튼이 안 눌리는" 증상만 남는다. 크게 죽는다.
    throw new Error('NEXT_PUBLIC_GOOGLE_CLIENT_ID가 없다 — web/.env.local을 확인하라');
  }
  const state = crypto.randomUUID();
  const payload: OAuthState = { state, returnTo: returnTo ?? null };
  sessionStorage.setItem(STATE_KEY, JSON.stringify(payload));

  window.location.assign(buildGoogleAuthUrl(clientId, window.location.origin, state));
}

/** 동의 URL 조립 — jsdom이 location.assign을 못 흉내 내므로 순수 함수로 떼어 검증한다. */
export function buildGoogleAuthUrl(clientId: string, origin: string, state: string): string {
  const url = new URL('https://accounts.google.com/o/oauth2/v2/auth');
  url.searchParams.set('client_id', clientId);
  // 백엔드 GoogleAuthProperties.redirectUri와 반드시 일치해야 한다 (기본값 {origin}/auth/callback).
  // 주소 하드코딩 금지 규칙에 따라 origin에서 조립한다.
  url.searchParams.set('redirect_uri', `${origin}/auth/callback`);
  url.searchParams.set('response_type', 'code');
  url.searchParams.set('scope', 'openid email profile');
  url.searchParams.set('state', state);
  return url.toString();
}

/**
 * 저장해 둔 state를 읽고 즉시 지운다 — 재사용·CSRF 방지. 콜백 화면이 딱 한 번 부른다.
 */
export function consumeOAuthState(): OAuthState | null {
  let raw: string | null = null;
  try {
    raw = sessionStorage.getItem(STATE_KEY);
    sessionStorage.removeItem(STATE_KEY);
  } catch {
    return null;
  }
  if (!raw) return null;
  try {
    const parsed: unknown = JSON.parse(raw);
    if (typeof parsed !== 'object' || parsed === null) return null;
    const { state, returnTo } = parsed as Record<string, unknown>;
    if (typeof state !== 'string' || state === '') return null;
    return { state, returnTo: typeof returnTo === 'string' ? returnTo : null };
  } catch {
    return null;
  }
}
