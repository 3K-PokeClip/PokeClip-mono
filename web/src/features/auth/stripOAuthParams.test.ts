import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { stripOAuthParamsFromUrl } from './stripOAuthParams';

afterEach(() => {
  vi.restoreAllMocks();
  window.history.replaceState(null, '', '/');
});

/**
 * 설치된 Next(16.3.0) app-router가 history.replaceState에 씌우는 패치의 대역. jsdom에서는 진짜 라우터를 띄울 수
 * 없어(서버가 만든 초기 RSC 페이로드가 있어야 한다) 동작만 옮겼다. 원본은 next/dist/client/components/app-router.js의
 * Router useEffect이고, 아래 「설치된 Next의 판정 조건」 시험이 그 원본이 아직 같은 조건인지 대조한다.
 * - 상태 인자에 라우터 내부 키(`__NA`·`_N`)가 있으면 Next 자신의 호출로 보고 라우터에 알리지 않는다.
 * - 없으면 현재 항목의 내부 키를 옮겨 붙이고, 바뀐 주소를 라우터의 canonicalUrl로 받는다(ACTION_RESTORE).
 * - 라우터가 다음에 갱신되면 HistoryUpdater가 canonicalUrl로 replaceState해 주소창을 라우터에 맞춘다.
 */
function installAppRouterDouble() {
  const original = window.history.replaceState.bind(window.history);
  const router = { canonicalUrl: currentHref() };
  original(
    { __NA: true, __PRIVATE_NEXTJS_INTERNALS_TREE: { tree: 'tree' } },
    '',
    router.canonicalUrl,
  );
  vi.spyOn(window.history, 'replaceState').mockImplementation((data, unused, url) => {
    const state = data as Record<string, unknown> | null;
    if (state?.__NA || state?._N) {
      original(data, unused, url);
      return;
    }
    const current = window.history.state as Record<string, unknown> | null;
    if (url) {
      const next = new URL(String(url), window.location.href);
      router.canonicalUrl = next.pathname + next.search + next.hash;
    }
    original(
      {
        ...state,
        __NA: current?.__NA,
        __PRIVATE_NEXTJS_INTERNALS_TREE: current?.__PRIVATE_NEXTJS_INTERNALS_TREE,
      },
      unused,
      url,
    );
  });
  return {
    router,
    /** 라우터의 다음 갱신(HistoryUpdater) — 라우터가 쥔 주소로 주소창을 덮어쓴다 */
    commitRouterUpdate: () => original({ __NA: true }, '', router.canonicalUrl),
  };
}

function currentHref(): string {
  return window.location.pathname + window.location.search + window.location.hash;
}

describe('stripOAuthParamsFromUrl (POK-482)', () => {
  it('주소창에서 code·state만 지우고 경로·다른 쿼리·해시는 남긴다', () => {
    window.history.replaceState(null, '', '/auth/callback?code=c-1&scope=email&state=s-1#top');

    stripOAuthParamsFromUrl();

    expect(window.location.pathname).toBe('/auth/callback');
    expect(window.location.search).toBe('?scope=email');
    expect(window.location.hash).toBe('#top');
  });

  it('라우터 내부 키(__NA·_N) 없는 상태로 바꿔 써, Next 라우터가 지운 주소를 받고 다음 갱신에도 code가 되살아나지 않는다', () => {
    window.history.replaceState(null, '', '/oauth/chzzk/callback?code=c-1&state=s-1');
    const { router, commitRouterUpdate } = installAppRouterDouble();

    stripOAuthParamsFromUrl();
    commitRouterUpdate();

    expect(window.location.search).toBe('');
    expect(router.canonicalUrl).toBe('/oauth/chzzk/callback');
  });

  it('지울 것이 없으면 히스토리를 건드리지 않는다', () => {
    window.history.replaceState(null, '', '/oauth/soop/callback?error=access_denied');
    const spy = vi.spyOn(window.history, 'replaceState');

    stripOAuthParamsFromUrl();

    expect(spy).not.toHaveBeenCalled();
  });
});

describe('설치된 Next의 판정 조건 (POK-485)', () => {
  // 위 대역이 기대는 전제를 설치된 Next 원본에서 확인한다. Next를 올렸는데 이 시험이 깨지면 판정 조건이 바뀐 것이니
  // app-router.js의 replaceState 패치를 다시 읽고 대역과 stripOAuthParams.ts를 함께 고친다.
  const require = createRequire(import.meta.url);
  const source = readFileSync(require.resolve('next/dist/client/components/app-router.js'), 'utf8');

  it('replaceState 패치는 라우터 내부 키가 있을 때만 라우터를 건너뛰고, 없으면 바뀐 주소를 라우터에 넘긴다', () => {
    const start = source.indexOf('window.history.replaceState = function replaceState(');
    expect(start).toBeGreaterThan(-1);
    const patch = source.slice(start, source.indexOf('};', start));

    expect(patch).toMatch(
      /if \(data\?\.__NA \|\| data\?\._N\) \{\s*return originalReplaceState\(data, _unused, url\);\s*\}/,
    );
    expect(patch).toMatch(/if \(url\) \{\s*applyUrlFromHistoryPushReplace\(url\);\s*\}/);
  });
});
