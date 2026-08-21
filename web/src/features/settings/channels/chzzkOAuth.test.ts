import { callbackMatchesOrigin, CHZZK_CALLBACK_PATH } from './chzzkOAuth';

const ORIGIN = 'http://localhost:3000';
const authorizeUrl = (redirectUri: string) =>
  `https://chzzk.naver.com/account-interlock?clientId=cid&redirectUri=${encodeURIComponent(
    redirectUri,
  )}&state=signed`;

describe('callbackMatchesOrigin', () => {
  it('오리진과 경로가 모두 같아야 통과한다', () => {
    expect(callbackMatchesOrigin(authorizeUrl(`${ORIGIN}${CHZZK_CALLBACK_PATH}`), ORIGIN)).toBe(
      true,
    );
  });

  it('포트가 다르면 불일치 — 로컬 등록값이 8081(clip)로 남아 있던 실제 사고 지점이다', () => {
    expect(
      callbackMatchesOrigin(authorizeUrl(`http://localhost:8081${CHZZK_CALLBACK_PATH}`), ORIGIN),
    ).toBe(false);
  });

  it('경로가 다르면 불일치 — dev 등록값이 /auth/chzzk/callback으로 남아 있던 지점이다', () => {
    expect(callbackMatchesOrigin(authorizeUrl(`${ORIGIN}/auth/chzzk/callback`), ORIGIN)).toBe(
      false,
    );
  });

  it('redirectUri가 없거나 URL이 아니면 불일치로 본다', () => {
    expect(
      callbackMatchesOrigin('https://chzzk.naver.com/account-interlock?clientId=cid', ORIGIN),
    ).toBe(false);
    expect(callbackMatchesOrigin(authorizeUrl('not-a-url'), ORIGIN)).toBe(false);
    expect(callbackMatchesOrigin('그냥 문자열', ORIGIN)).toBe(false);
  });
});
