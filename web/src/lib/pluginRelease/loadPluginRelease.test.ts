import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  FAILURE_BACKOFF_MS,
  PluginReleaseUnavailableError,
  RELEASES_API,
  STALE_MAX_MS,
  TTL_MS,
  loadPluginRelease,
  resetPluginReleaseCache,
} from './loadPluginRelease';

const TAG = 'obs-plugin-v0.2.0';
const PKG = {
  name: 'pokeclip-obs-0.2.0-macos-universal.pkg',
  state: 'uploaded',
  size: 10,
  browser_download_url: `https://github.com/3K-PokeClip/PokeClip-mono/releases/download/${TAG}/pokeclip-obs-0.2.0-macos-universal.pkg`,
};
const RELEASE = {
  tag_name: TAG,
  draft: false,
  prerelease: false,
  published_at: '2026-10-20T03:00:00Z',
  assets: [PKG],
};
const RELEASES = [RELEASE];

function json(status: number, body: unknown, headers: Record<string, string> = {}) {
  return new Response(JSON.stringify(body), { status, headers });
}

let t = 0;
const now = () => t;

beforeEach(() => {
  resetPluginReleaseCache();
  t = Date.parse('2026-10-21T00:00:00Z');
  vi.spyOn(console, 'warn').mockImplementation(() => {});
});
afterEach(() => vi.restoreAllMocks());

describe('loadPluginRelease: GitHub 조회와 서버 메모', () => {
  it('첫 호출은 GitHub를 한 번 부르고, 5분 안 두 번째는 부르지 않는다', async () => {
    const fetch = vi.fn(async () => json(200, RELEASES));
    const first = await loadPluginRelease({ fetch, now });
    expect(first.cache).toBe('miss');
    expect(first.body).toMatchObject({ status: 'ready', release: { tag: TAG } });

    t += TTL_MS - 1;
    const second = await loadPluginRelease({ fetch, now });
    expect(second.cache).toBe('hit');
    expect(fetch).toHaveBeenCalledTimes(1);

    t += 1;
    expect((await loadPluginRelease({ fetch, now })).cache).toBe('miss');
    expect(fetch).toHaveBeenCalledTimes(2);
  });

  it('빈 목록도 「아직 없음」으로 캐시한다 — Release가 없는 동안 요청마다 GitHub를 치지 않는다', async () => {
    const fetch = vi.fn(async () => json(200, []));
    expect((await loadPluginRelease({ fetch, now })).body).toEqual({ status: 'unavailable' });
    await loadPluginRelease({ fetch, now });
    expect(fetch).toHaveBeenCalledTimes(1);
  });

  it('동시에 들어온 요청은 GitHub 한 번으로 합친다', async () => {
    const fetch = vi.fn(async () => json(200, RELEASES));
    const [a, b] = await Promise.all([
      loadPluginRelease({ fetch, now }),
      loadPluginRelease({ fetch, now }),
    ]);
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(a.body).toEqual(b.body);
  });

  it('필수 헤더를 붙이고, 토큰은 있을 때만 Authorization으로 보낸다', async () => {
    const fetch = vi.fn(async (_url: RequestInfo | URL, _init?: RequestInit) => json(200, []));
    await loadPluginRelease({ fetch, now });
    const [url, init] = fetch.mock.calls[0]!;
    expect(url).toBe(RELEASES_API);
    expect(init?.cache).toBe('no-store');
    expect(init?.headers).toMatchObject({
      Accept: 'application/vnd.github+json',
      'User-Agent': 'pokeclip-web',
    });
    expect(init?.headers).not.toHaveProperty('Authorization');

    resetPluginReleaseCache();
    await loadPluginRelease({ fetch, now, token: 'ghp_test' });
    expect(fetch.mock.calls[1]![1]?.headers).toMatchObject({ Authorization: 'Bearer ghp_test' });
  });

  it('시험판은 옵션이 있을 때만 고른다', async () => {
    const rcTag = 'obs-plugin-v0.2.0-rc.1';
    const rc = [
      {
        ...RELEASE,
        tag_name: rcTag,
        prerelease: true,
        assets: [{ ...PKG, browser_download_url: PKG.browser_download_url.replace(TAG, rcTag) }],
      },
    ];
    const fetch = vi.fn(async () => json(200, rc));
    expect((await loadPluginRelease({ fetch, now })).body).toEqual({ status: 'unavailable' });
    resetPluginReleaseCache();
    expect((await loadPluginRelease({ fetch, now, includePrerelease: true })).body).toMatchObject({
      status: 'ready',
    });
  });

  it('지난 값이 있으면 실패 때 그것을 대신 준다(24시간까지)', async () => {
    const fetch = vi.fn(async () => json(200, RELEASES));
    await loadPluginRelease({ fetch, now });

    fetch.mockImplementation(async () => json(503, { message: 'down' }));
    t += TTL_MS;
    const stale = await loadPluginRelease({ fetch, now });
    expect(stale.cache).toBe('stale');
    expect(stale.body).toMatchObject({ status: 'ready' });

    t += STALE_MAX_MS;
    await expect(loadPluginRelease({ fetch, now })).rejects.toBeInstanceOf(
      PluginReleaseUnavailableError,
    );
  });

  it('지난 값도 없으면 던진다 — 장애를 「아직 없음」으로 바꾸지 않는다', async () => {
    const fetch = vi.fn(async () => json(403, { message: 'rate limit' }));
    await expect(loadPluginRelease({ fetch, now })).rejects.toBeInstanceOf(
      PluginReleaseUnavailableError,
    );
  });

  it('잘못된 JSON·시간 초과도 실패 경로다', async () => {
    const badJson = vi.fn(async () => new Response('<html>', { status: 200 }));
    await expect(loadPluginRelease({ fetch: badJson, now })).rejects.toBeInstanceOf(
      PluginReleaseUnavailableError,
    );

    resetPluginReleaseCache();
    const timeout = vi.fn(async () => {
      throw new DOMException('timed out', 'TimeoutError');
    });
    await expect(loadPluginRelease({ fetch: timeout, now })).rejects.toBeInstanceOf(
      PluginReleaseUnavailableError,
    );
  });

  it('실패 뒤 60초는 다시 부르지 않는다', async () => {
    const fetch = vi.fn(async () => json(500, {}));
    await expect(loadPluginRelease({ fetch, now })).rejects.toThrow();
    t += FAILURE_BACKOFF_MS - 1;
    await expect(loadPluginRelease({ fetch, now })).rejects.toThrow();
    expect(fetch).toHaveBeenCalledTimes(1);

    t += 1;
    await expect(loadPluginRelease({ fetch, now })).rejects.toThrow();
    expect(fetch).toHaveBeenCalledTimes(2);
  });

  it('한도 초과가 알려 준 재시도 시각까지 기다린다', async () => {
    const reset = Math.floor(t / 1000) + 600; // 10분 뒤
    const fetch = vi.fn(async () =>
      json(403, {}, { 'x-ratelimit-remaining': '0', 'x-ratelimit-reset': String(reset) }),
    );
    await expect(loadPluginRelease({ fetch, now })).rejects.toThrow();
    t += 5 * 60_000;
    await expect(loadPluginRelease({ fetch, now })).rejects.toThrow();
    expect(fetch).toHaveBeenCalledTimes(1);

    t = reset * 1000;
    await expect(loadPluginRelease({ fetch, now })).rejects.toThrow();
    expect(fetch).toHaveBeenCalledTimes(2);
  });
});
