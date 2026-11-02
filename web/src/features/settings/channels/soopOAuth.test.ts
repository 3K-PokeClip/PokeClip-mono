import { describe, expect, it } from 'vitest';
import * as soopOAuth from './soopOAuth';
import { assertSoopConsentUrl } from './soopOAuth';

const CONSENT = 'https://openapi.sooplive.com/auth/code?client_id=cid&state=signed-1';

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

describe('state 대체 경로 없음', () => {
  it('세션 저장소로 state를 나르는 함수를 두지 않는다: SOOP이 state를 돌려준다(실계정 확인)', () => {
    expect(Object.keys(soopOAuth).sort()).toEqual([
      'SOOP_CALLBACK_PATH',
      'assertSoopConsentUrl',
      'goToSoopConsent',
    ]);
  });
});
