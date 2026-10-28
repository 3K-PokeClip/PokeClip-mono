import { afterEach, describe, expect, it, vi } from 'vitest';
import { hasInAppHistory } from './inAppHistory';

/**
 * 이 탭의 기록 항목을 Navigation API 꼴로 심는다 — 브라우저는 지금 항목과 같은 오리진으로 이어진 항목만 준다.
 * 경로는 이 앱 오리진 기준, `https://…`는 그대로. current는 지금 항목의 자리(기본은 마지막).
 */
function stubHistory(urls: (string | null)[], current = urls.length - 1) {
  const entries = urls.map((url, index) => ({
    index,
    url: url === null ? null : new URL(url, window.location.origin).href,
  }));
  vi.stubGlobal('navigation', { currentEntry: entries[current], entries: () => entries });
}

describe('hasInAppHistory — 「이전 화면으로」가 뒤로 가도 되는가(POK-491)', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('앱 안 화면에서 들어왔으면 뒤로 간다', () => {
    stubHistory(['/broadcast/vod/s1', '/clips/editor/studio']);
    expect(hasInAppHistory()).toBe(true);
  });

  it('지금 항목이 첫 항목이면(새 탭 · 주소 직접 · 다른 오리진을 지나 옴) 뒤로 가지 않는다', () => {
    stubHistory(['/clips/editor/studio']);
    expect(hasInAppHistory()).toBe(false);
  });

  it('로그인 흐름 뒤(OAuth 콜백이 편집기로 replace)엔 뒤로 가지 않는다 — 앞서 경로를 쌓던 판은 앱 밖으로 나갔다', () => {
    // 구글을 지나 새로 연 콜백 문서가 router.replace로 편집기가 됐다: 브라우저 기록은 항목 하나다
    stubHistory(['/clips/editor/studio']);
    expect(hasInAppHistory()).toBe(false);
    // 같은 오리진의 앞 항목이 로그인 흐름이어도 다시 밟지 않는다
    for (const auth of ['/login', '/auth/callback', '/oauth/soop/callback']) {
      stubHistory([auth, '/clips/editor/studio']);
      expect(hasInAppHistory()).toBe(false);
    }
  });

  it('브라우저 뒤로 가기로 가운데 항목에 서 있어도 바로 앞 항목을 본다', () => {
    stubHistory(['/clips/library', '/clips/editor/studio', '/home'], 1);
    expect(hasInAppHistory()).toBe(true);
    stubHistory(['/clips/editor/studio', '/clips/library'], 0);
    expect(hasInAppHistory()).toBe(false);
  });

  it('앞 항목이 다른 오리진이거나 주소를 모르면 뒤로 가지 않는다', () => {
    stubHistory(['https://accounts.google.com/o/oauth2/auth', '/clips/editor/studio']);
    expect(hasInAppHistory()).toBe(false);
    stubHistory([null, '/clips/editor/studio']);
    expect(hasInAppHistory()).toBe(false);
  });

  it('Navigation API가 없는 브라우저는 앞 화면이 없는 것으로 본다 — 앱 밖으로 나가는 것보다 보관함이 낫다', () => {
    vi.stubGlobal('navigation', undefined);
    expect(hasInAppHistory()).toBe(false);
  });
});
