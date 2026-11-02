import {
  assertYoutubeConsentUrl,
  callbackMatchesOrigin,
  YOUTUBE_CALLBACK_PATH,
} from './youtubeOAuth';

const ORIGIN = 'http://localhost:3000';
const authorizeUrl = (redirectUri: string) =>
  `https://accounts.google.com/o/oauth2/v2/auth?client_id=cid&redirect_uri=${encodeURIComponent(
    redirectUri,
  )}&state=signed`;

describe('callbackMatchesOrigin', () => {
  it('오리진과 경로가 모두 같아야 통과한다', () => {
    expect(callbackMatchesOrigin(authorizeUrl(`${ORIGIN}${YOUTUBE_CALLBACK_PATH}`), ORIGIN)).toBe(
      true,
    );
  });

  it('치지직처럼 camelCase(redirectUri)로 실린 URL은 불일치다 — 구글은 snake_case가 규격이다', () => {
    // chzzkOAuth를 복사해 만들 때 가장 그럴듯하게 틀리는 지점 — 파라미터 이름이 다르면
    // 값이 맞아도 읽지 못해 항상 불일치가 된다. 이 성질을 회귀 가드로 못 박는다.
    const camel = `https://accounts.google.com/o/oauth2/v2/auth?client_id=cid&redirectUri=${encodeURIComponent(
      `${ORIGIN}${YOUTUBE_CALLBACK_PATH}`,
    )}&state=signed`;
    expect(callbackMatchesOrigin(camel, ORIGIN)).toBe(false);
  });

  it('포트가 다르면 불일치 — 등록값이 다른 서비스 포트로 남는 실수를 잡는 지점이다', () => {
    expect(
      callbackMatchesOrigin(authorizeUrl(`http://localhost:8081${YOUTUBE_CALLBACK_PATH}`), ORIGIN),
    ).toBe(false);
  });

  it('경로가 다르면 불일치 — 치지직 경로(/oauth/chzzk/callback)로 등록된 경우까지 잡는다', () => {
    expect(callbackMatchesOrigin(authorizeUrl(`${ORIGIN}/oauth/chzzk/callback`), ORIGIN)).toBe(
      false,
    );
  });

  it('redirect_uri가 없거나 URL이 아니면 불일치로 본다', () => {
    expect(
      callbackMatchesOrigin('https://accounts.google.com/o/oauth2/v2/auth?client_id=cid', ORIGIN),
    ).toBe(false);
    expect(callbackMatchesOrigin(authorizeUrl('not-a-url'), ORIGIN)).toBe(false);
    expect(callbackMatchesOrigin('그냥 문자열', ORIGIN)).toBe(false);
  });
});

describe('assertYoutubeConsentUrl', () => {
  it('https 동의 URL은 통과시킨다', () => {
    expect(() =>
      assertYoutubeConsentUrl(authorizeUrl(`${ORIGIN}${YOUTUBE_CALLBACK_PATH}`)),
    ).not.toThrow();
  });

  it('javascript: 스킴을 막는다 — location.assign에 실리면 우리 오리진에서 실행된다', () => {
    expect(() => assertYoutubeConsentUrl('javascript:alert(1)')).toThrow();
  });

  it('http·URL 아닌 값도 막는다', () => {
    expect(() => assertYoutubeConsentUrl('http://accounts.google.com/o/oauth2/v2/auth')).toThrow();
    expect(() => assertYoutubeConsentUrl('그냥 문자열')).toThrow();
  });

  it('오류 메시지에 URL을 담지 않는다 — 서명된 state가 들어 있다', () => {
    expect(() => assertYoutubeConsentUrl('javascript:alert(1)')).toThrow(
      /^구글 동의 URL이 https가 아니다$/,
    );
  });
});
