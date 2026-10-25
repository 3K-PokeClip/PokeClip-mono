import { beforeEach, describe, expect, it } from 'vitest';
import {
  buildGoogleAuthUrl,
  computeCodeChallenge,
  consumeOAuthState,
  createCodeVerifier,
  prepareGoogleLogin,
} from '@/features/auth/googleOAuth';

const STATE_KEY = 'pc-oauth-state';

// RFC 7636 부록 B의 시험 벡터 — 우리 계산이 구글 서버의 S256 계산과 같은지를 표준 값으로 못 박는다.
const RFC_VERIFIER = 'dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk';
const RFC_CHALLENGE = 'E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM';

beforeEach(() => {
  window.sessionStorage.clear();
});

describe('buildGoogleAuthUrl', () => {
  it('동의 URL에 클라이언트·콜백·범위·state·PKCE challenge가 전부 실린다', () => {
    const url = new URL(
      buildGoogleAuthUrl('client-1', 'http://localhost:3000', 'state-1', 'challenge-1'),
    );

    expect(url.origin).toBe('https://accounts.google.com');
    expect(url.searchParams.get('client_id')).toBe('client-1');
    // 백엔드 GoogleAuthProperties.redirectUri 기본값과 짝 — 어긋나면 구글이 교환을 거부한다
    expect(url.searchParams.get('redirect_uri')).toBe('http://localhost:3000/auth/callback');
    expect(url.searchParams.get('response_type')).toBe('code');
    expect(url.searchParams.get('scope')).toBe('openid email profile');
    expect(url.searchParams.get('state')).toBe('state-1');
    expect(url.searchParams.get('code_challenge')).toBe('challenge-1');
    // 생략하면 구글은 plain으로 읽는다 — 그러면 challenge가 verifier 원문이 된다
    expect(url.searchParams.get('code_challenge_method')).toBe('S256');
  });
});

describe('createCodeVerifier', () => {
  it('RFC 7636의 길이(43–128자)와 문자 집합(unreserved)을 지킨다', () => {
    const verifier = createCodeVerifier();

    expect(verifier.length).toBeGreaterThanOrEqual(43);
    expect(verifier.length).toBeLessThanOrEqual(128);
    expect(verifier).toMatch(/^[A-Za-z0-9\-._~]+$/);
  });

  it('부를 때마다 새 값이다 — 시도마다 1회용', () => {
    expect(createCodeVerifier()).not.toBe(createCodeVerifier());
  });
});

describe('computeCodeChallenge', () => {
  it('S256 — SHA-256의 base64url(덧붙임 없음)이 RFC 7636 시험 벡터와 같다', async () => {
    await expect(computeCodeChallenge(RFC_VERIFIER)).resolves.toBe(RFC_CHALLENGE);
  });
});

describe('prepareGoogleLogin', () => {
  it('verifier를 state와 한 항목에 저장하고, 동의 URL에는 그 verifier의 challenge만 싣는다', async () => {
    const url = new URL(
      await prepareGoogleLogin('client-1', 'http://localhost:3000', '/settings/plugin'),
    );

    const stored = JSON.parse(window.sessionStorage.getItem(STATE_KEY) ?? 'null') as {
      state: string;
      returnTo: string | null;
      codeVerifier: string;
    };
    expect(stored.returnTo).toBe('/settings/plugin');
    expect(url.searchParams.get('state')).toBe(stored.state);
    expect(url.searchParams.get('code_challenge')).toBe(
      await computeCodeChallenge(stored.codeVerifier),
    );
    // verifier 원문은 주소로 나가지 않는다 — 주소는 기록·리퍼러로 새는 길이다
    expect(url.toString()).not.toContain(stored.codeVerifier);
  });
});

describe('consumeOAuthState', () => {
  it('저장된 state·verifier를 돌려주고 즉시 지운다 — 두 번째 호출은 null', () => {
    window.sessionStorage.setItem(
      STATE_KEY,
      JSON.stringify({
        state: 'state-1',
        returnTo: '/settings/plugin',
        codeVerifier: RFC_VERIFIER,
      }),
    );

    expect(consumeOAuthState()).toEqual({
      state: 'state-1',
      returnTo: '/settings/plugin',
      codeVerifier: RFC_VERIFIER,
    });
    expect(consumeOAuthState()).toBeNull();
    expect(window.sessionStorage.getItem(STATE_KEY)).toBeNull();
  });

  it('손상 JSON·state 누락은 null — 콜백이 에러 화면으로 처리한다', () => {
    window.sessionStorage.setItem(STATE_KEY, '{broken');
    expect(consumeOAuthState()).toBeNull();

    window.sessionStorage.setItem(
      STATE_KEY,
      JSON.stringify({ returnTo: '/home', codeVerifier: RFC_VERIFIER }),
    );
    expect(consumeOAuthState()).toBeNull();
  });

  it('verifier가 없는 항목(배포 전에 시작한 시도)은 null — 교환해 봐야 서버가 거부한다', () => {
    window.sessionStorage.setItem(STATE_KEY, JSON.stringify({ state: 'state-1', returnTo: null }));

    expect(consumeOAuthState()).toBeNull();
    expect(window.sessionStorage.getItem(STATE_KEY)).toBeNull();
  });
});
