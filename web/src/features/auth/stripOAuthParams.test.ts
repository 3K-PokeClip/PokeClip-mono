import { afterEach, describe, expect, it, vi } from 'vitest';
import { stripOAuthParamsFromUrl } from './stripOAuthParams';

afterEach(() => {
  vi.restoreAllMocks();
  window.history.replaceState(null, '', '/');
});

describe('stripOAuthParamsFromUrl (POK-482)', () => {
  it('주소창에서 code·state만 지우고 경로·다른 쿼리·해시는 남긴다', () => {
    window.history.replaceState(null, '', '/auth/callback?code=c-1&scope=email&state=s-1#top');

    stripOAuthParamsFromUrl();

    expect(window.location.pathname).toBe('/auth/callback');
    expect(window.location.search).toBe('?scope=email');
    expect(window.location.hash).toBe('#top');
  });

  it('상태 인자로 null을 넘긴다: Next 라우터가 바뀐 주소를 받아 useSearchParams와 맞춘다', () => {
    window.history.replaceState(null, '', '/oauth/chzzk/callback?code=c-1&state=s-1');
    const spy = vi.spyOn(window.history, 'replaceState');

    stripOAuthParamsFromUrl();

    expect(spy).toHaveBeenCalledTimes(1);
    expect(spy).toHaveBeenCalledWith(null, '', '/oauth/chzzk/callback');
  });

  it('지울 것이 없으면 히스토리를 건드리지 않는다', () => {
    window.history.replaceState(null, '', '/oauth/soop/callback?error=access_denied');
    const spy = vi.spyOn(window.history, 'replaceState');

    stripOAuthParamsFromUrl();

    expect(spy).not.toHaveBeenCalled();
  });
});
