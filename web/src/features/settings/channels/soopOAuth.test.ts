import { beforeEach, describe, expect, it } from 'vitest';
import {
  assertSoopConsentUrl,
  rememberSoopState,
  SOOP_STATE_KEY,
  takeSoopState,
} from './soopOAuth';

const CONSENT = 'https://openapi.sooplive.com/auth/code?client_id=cid&state=signed-1';

beforeEach(() => {
  window.sessionStorage.clear();
});

describe('assertSoopConsentUrl', () => {
  it('https 동의 주소는 통과시킨다', () => {
    expect(() => assertSoopConsentUrl(CONSENT)).not.toThrow();
  });

  it('https가 아니거나 URL이 아니면 막는다: 이동하지 않는다', () => {
    expect(() => assertSoopConsentUrl('http://openapi.sooplive.com/auth/code?state=s')).toThrow();
    expect(() => assertSoopConsentUrl('javascript:alert(1)')).toThrow();
    expect(() => assertSoopConsentUrl('그냥 문자열')).toThrow();
  });

  it('오류 메시지에 주소를 담지 않는다: 서명된 state가 들어 있다', () => {
    let message = '';
    try {
      assertSoopConsentUrl('http://evil.example/?state=signed-secret');
    } catch (e) {
      message = String(e);
    }
    expect(message).not.toBe('');
    expect(message).not.toContain('signed-secret');
  });
});

describe('rememberSoopState', () => {
  it('동의 주소의 state를 sessionStorage에 둔다', () => {
    rememberSoopState(CONSENT);
    expect(SOOP_STATE_KEY).toBe('pokeclip.soop.state');
    expect(window.sessionStorage.getItem('pokeclip.soop.state')).toBe('signed-1');
  });

  it('state가 없는 주소면 옛 값을 남기지 않는다', () => {
    window.sessionStorage.setItem(SOOP_STATE_KEY, 'old');
    rememberSoopState('https://openapi.sooplive.com/auth/code?client_id=cid');
    expect(window.sessionStorage.getItem(SOOP_STATE_KEY)).toBeNull();
  });
});

describe('takeSoopState', () => {
  it('쿼리 state가 있으면 그것을 쓰고 저장값은 지운다', () => {
    window.sessionStorage.setItem(SOOP_STATE_KEY, 'stored-1');
    expect(takeSoopState('query-1')).toBe('query-1');
    expect(window.sessionStorage.getItem(SOOP_STATE_KEY)).toBeNull();
  });

  it('쿼리 state가 없으면 저장된 것을 쓰고 지운다', () => {
    window.sessionStorage.setItem(SOOP_STATE_KEY, 'stored-1');
    expect(takeSoopState(null)).toBe('stored-1');
    expect(window.sessionStorage.getItem(SOOP_STATE_KEY)).toBeNull();
  });

  it('빈 쿼리 state는 없는 것으로 본다', () => {
    window.sessionStorage.setItem(SOOP_STATE_KEY, 'stored-1');
    expect(takeSoopState('')).toBe('stored-1');
  });

  it('둘 다 없으면 null이다: 콜백이 오류로 처리한다', () => {
    expect(takeSoopState(null)).toBeNull();
  });
});
