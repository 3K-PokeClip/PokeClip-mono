import { beforeEach, describe, expect, it } from 'vitest';
import { buildGoogleAuthUrl, consumeOAuthState } from '@/features/auth/googleOAuth';

const STATE_KEY = 'pc-oauth-state';

beforeEach(() => {
  window.sessionStorage.clear();
});

describe('buildGoogleAuthUrl', () => {
  it('동의 URL에 클라이언트·콜백·범위·state가 전부 실린다', () => {
    const url = new URL(buildGoogleAuthUrl('client-1', 'http://localhost:3000', 'state-1'));

    expect(url.origin).toBe('https://accounts.google.com');
    expect(url.searchParams.get('client_id')).toBe('client-1');
    // 백엔드 GoogleAuthProperties.redirectUri 기본값과 짝 — 어긋나면 구글이 교환을 거부한다
    expect(url.searchParams.get('redirect_uri')).toBe('http://localhost:3000/auth/callback');
    expect(url.searchParams.get('response_type')).toBe('code');
    expect(url.searchParams.get('scope')).toBe('openid email profile');
    expect(url.searchParams.get('state')).toBe('state-1');
  });
});

describe('consumeOAuthState', () => {
  it('저장된 state를 돌려주고 즉시 지운다 — 두 번째 호출은 null', () => {
    window.sessionStorage.setItem(
      STATE_KEY,
      JSON.stringify({ state: 'state-1', returnTo: '/settings/plugin' }),
    );

    expect(consumeOAuthState()).toEqual({ state: 'state-1', returnTo: '/settings/plugin' });
    expect(consumeOAuthState()).toBeNull();
    expect(window.sessionStorage.getItem(STATE_KEY)).toBeNull();
  });

  it('손상 JSON·state 누락은 null — 콜백이 에러 화면으로 처리한다', () => {
    window.sessionStorage.setItem(STATE_KEY, '{broken');
    expect(consumeOAuthState()).toBeNull();

    window.sessionStorage.setItem(STATE_KEY, JSON.stringify({ returnTo: '/home' }));
    expect(consumeOAuthState()).toBeNull();
  });
});
