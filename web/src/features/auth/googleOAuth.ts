'use client';

// 구글 OAuth 동의 화면 진입 (POK-101) — authorization code 흐름의 앞반쪽.
// code→토큰 교환은 백엔드(POST /api/auth/google)가 client_secret으로 수행하므로
// 프론트는 동의 URL 조립과 state 검증, 그리고 PKCE verifier 보관만 책임진다.
//
// PKCE (POK-483): 시도마다 1회용 비밀(code_verifier)을 만들어 그 해시(code_challenge)만
// 동의 URL에 싣고, 원문은 교환 요청 본문으로만 보낸다. 주소창·기록에서 code가 새어도
// verifier가 없으면 교환할 수 없다 (RFC 7636).

const STATE_KEY = 'pc-oauth-state';

/**
 * 구글 동의 뒤 돌아올 redirect URI의 경로. 백엔드 GoogleAuthProperties.redirectUri(기본값 {origin}/auth/callback)와
 * 반드시 일치해야 한다. next.config.ts의 콜백 Referrer-Policy 경로도 이 값과 짝이다(next.config.test.ts가 대조).
 */
export const GOOGLE_CALLBACK_PATH = '/auth/callback';

/** RFC 7636 4.1 — 32바이트 난수의 base64url은 43자로, 허용 범위(43–128자)의 하한이다. */
const CODE_VERIFIER_BYTES = 32;

export interface OAuthState {
  state: string;
  /** 로그인 차단 전 가려던 경로 — 콜백 성공 후 복원한다. */
  returnTo: string | null;
  /** 교환 요청에 실을 PKCE 원문. state와 한 항목에 둬 같은 순간에 같이 소모된다. */
  codeVerifier: string;
}

/**
 * 동의 화면으로 이동한다. state는 CSRF 방지용 1회 난수 — 왕복 한 번에만 쓰이는
 * 탭 한정 값이라 sessionStorage가 맞다 (localStorage면 다른 탭 로그인과 섞인다).
 */
export async function startGoogleLogin(returnTo?: string) {
  const clientId = process.env.NEXT_PUBLIC_GOOGLE_CLIENT_ID;
  if (!clientId) {
    // 배포 설정 오류다 — 조용히 넘어가면 "버튼이 안 눌리는" 증상만 남는다. 크게 죽는다.
    throw new Error('NEXT_PUBLIC_GOOGLE_CLIENT_ID가 없다 — web/.env.local을 확인하라');
  }
  window.location.assign(await prepareGoogleLogin(clientId, window.location.origin, returnTo));
}

/**
 * 새 로그인 시도를 sessionStorage에 기록하고 그 시도의 동의 URL을 돌려준다 — jsdom이
 * location.assign을 못 흉내 내므로 이동과 떼어 검증한다. challenge 계산이 실패하면
 * 아무것도 저장하지 않는다.
 */
export async function prepareGoogleLogin(
  clientId: string,
  origin: string,
  returnTo?: string,
): Promise<string> {
  const state = crypto.randomUUID();
  const codeVerifier = createCodeVerifier();
  const codeChallenge = await computeCodeChallenge(codeVerifier);
  const payload: OAuthState = { state, returnTo: returnTo ?? null, codeVerifier };
  sessionStorage.setItem(STATE_KEY, JSON.stringify(payload));
  return buildGoogleAuthUrl(clientId, origin, state, codeChallenge);
}

/** 동의 URL 조립 — 순수 함수. */
export function buildGoogleAuthUrl(
  clientId: string,
  origin: string,
  state: string,
  codeChallenge: string,
): string {
  const url = new URL('https://accounts.google.com/o/oauth2/v2/auth');
  url.searchParams.set('client_id', clientId);
  // 주소 하드코딩 금지 규칙에 따라 origin에서 조립한다.
  url.searchParams.set('redirect_uri', `${origin}${GOOGLE_CALLBACK_PATH}`);
  url.searchParams.set('response_type', 'code');
  url.searchParams.set('scope', 'openid email profile');
  url.searchParams.set('state', state);
  url.searchParams.set('code_challenge', codeChallenge);
  // 생략하면 구글이 plain으로 읽는다 — S256을 못 박아야 주소에 원문이 실리지 않는다.
  url.searchParams.set('code_challenge_method', 'S256');
  return url.toString();
}

/** PKCE 원문 — 암호학적 난수를 base64url로. 결과는 unreserved 문자만 쓴다 (RFC 7636 4.1). */
export function createCodeVerifier(): string {
  return toBase64Url(crypto.getRandomValues(new Uint8Array(CODE_VERIFIER_BYTES)));
}

/** S256 challenge = base64url(SHA-256(ASCII(verifier))), 덧붙임(=) 없음 (RFC 7636 4.2). */
export async function computeCodeChallenge(codeVerifier: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(codeVerifier));
  return toBase64Url(new Uint8Array(digest));
}

function toBase64Url(bytes: Uint8Array): string {
  const binary = String.fromCharCode(...bytes);
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

/**
 * 저장해 둔 state·verifier를 읽고 즉시 지운다 — 재사용·CSRF 방지. 콜백 화면이 딱 한 번
 * 부른다. 어느 갈래(성공·실패·state 불일치·동의 취소)로 가든 verifier는 여기서 사라진다.
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
    const { state, returnTo, codeVerifier } = parsed as Record<string, unknown>;
    if (typeof state !== 'string' || state === '') return null;
    // 배포 전에 시작한 시도는 verifier가 없다 — 그 code는 challenge 없이 발급돼 PKCE로 교환할 수 없으니 여기서 끊는다.
    if (typeof codeVerifier !== 'string' || codeVerifier === '') return null;
    return { state, returnTo: typeof returnTo === 'string' ? returnTo : null, codeVerifier };
  } catch {
    return null;
  }
}
