import { afterEach, describe, expect, it, vi } from 'vitest';
import { jsonResponse, stubFetch } from '@/test/mockFetch';
import { GET } from './route';

// 로컬 녹화 재생 전달(POK-317). prod는 엣지가 같은 일을 한다: 경로의 키로 path를 채우고 들어온 path는 버린다.

afterEach(() => {
  vi.unstubAllEnvs();
  vi.unstubAllGlobals();
});

function call(url: string, key: string, op: string) {
  return GET(new Request(url), { params: Promise.resolve({ key, op }) });
}

describe('로컬 녹화 재생 전달', () => {
  it('재생 서버 주소가 없으면 404다: prod · dev는 이 길이 꺼져 있고 엣지가 맡는다', async () => {
    vi.stubEnv('MEDIA_PLAYBACK_ORIGIN', '');
    const spy = stubFetch(() => jsonResponse(200, []));
    const res = await call('http://localhost:3000/media/playback/key-1/list', 'key-1', 'list');
    expect(res.status).toBe(404);
    expect(spy).not.toHaveBeenCalled();
  });

  it('녹화 목록은 경로의 키 하나로만 묻는다: 들어온 쿼리는 버린다(쿠키 정책은 쿼리를 못 막아 남의 녹화가 열린다)', async () => {
    vi.stubEnv('MEDIA_PLAYBACK_ORIGIN', 'http://localhost:9996/');
    const spy = stubFetch(() =>
      jsonResponse(200, [{ start: '2026-10-23T01:00:00Z', duration: 4 }]),
    );
    const res = await call(
      'http://localhost:3000/media/playback/key-1/list?path=someone-else&start=x',
      'key-1',
      'list',
    );
    expect(String(spy.mock.calls[0]?.[0])).toBe('http://localhost:9996/list?path=key-1');
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual([{ start: '2026-10-23T01:00:00Z', duration: 4 }]);
  });

  it('영상 조각은 시작 · 길이 · 형식만 옮기고 path는 경로의 키다', async () => {
    vi.stubEnv('MEDIA_PLAYBACK_ORIGIN', 'http://localhost:9996');
    const spy = stubFetch(
      () => new Response('mp4', { status: 200, headers: { 'content-type': 'video/mp4' } }),
    );
    const res = await call(
      'http://localhost:3000/media/playback/key-1/get?path=someone-else&start=2026-10-23T01%3A00%3A00.000Z&duration=12.500&format=mp4&x=1',
      'key-1',
      'get',
    );
    const upstream = new URL(String(spy.mock.calls[0]?.[0]));
    expect(upstream.origin + upstream.pathname).toBe('http://localhost:9996/get');
    expect(Object.fromEntries(upstream.searchParams)).toEqual({
      start: '2026-10-23T01:00:00.000Z',
      duration: '12.500',
      format: 'mp4',
      path: 'key-1',
    });
    expect(res.headers.get('content-type')).toBe('video/mp4');
    expect(await res.text()).toBe('mp4');
  });

  it('영상 조각은 Range를 넘기고 길이 · 범위 머리를 돌려준다: 재생 서버에 직접 붙던 때와 같게 한다(로컬 리뷰 1라운드)', async () => {
    vi.stubEnv('MEDIA_PLAYBACK_ORIGIN', 'http://localhost:9996');
    const spy = stubFetch(
      () =>
        new Response('mp', {
          status: 206,
          headers: {
            'content-type': 'video/mp4',
            'content-length': '2',
            'content-range': 'bytes 0-1/10',
            'accept-ranges': 'bytes',
            'set-cookie': 'x=1',
          },
        }),
    );
    const res = await GET(
      new Request('http://x/media/playback/key-1/get?start=a&duration=1&format=mp4', {
        headers: { range: 'bytes=0-1', cookie: 'CloudFront-Policy=secret' },
      }),
      { params: Promise.resolve({ key: 'key-1', op: 'get' }) },
    );
    const sent = new Headers(spy.mock.calls[0]?.[1]?.headers);
    expect(sent.get('range')).toBe('bytes=0-1');
    expect(sent.get('cookie')).toBeNull();
    expect(res.status).toBe(206);
    expect(res.headers.get('content-length')).toBe('2');
    expect(res.headers.get('content-range')).toBe('bytes 0-1/10');
    expect(res.headers.get('accept-ranges')).toBe('bytes');
    expect(res.headers.get('set-cookie')).toBeNull();
  });

  it('재생 서버의 상태를 그대로 넘긴다: 녹화 폴더가 없다는 400은 「녹화 없음」으로 읽힌다', async () => {
    vi.stubEnv('MEDIA_PLAYBACK_ORIGIN', 'http://localhost:9996');
    stubFetch(() => jsonResponse(400, { error: 'no recordings' }));
    expect(
      (await call('http://localhost:3000/media/playback/key-1/list', 'key-1', 'list')).status,
    ).toBe(400);
  });

  it('모르는 동작이나 키 글자 밖이면 404다: 재생 서버의 다른 길을 열지 않는다', async () => {
    vi.stubEnv('MEDIA_PLAYBACK_ORIGIN', 'http://localhost:9996');
    const spy = stubFetch(() => jsonResponse(200, []));
    expect((await call('http://x/media/playback/key-1/delete', 'key-1', 'delete')).status).toBe(
      404,
    );
    expect((await call('http://x/media/playback/a..b/list', '../list', 'list')).status).toBe(404);
    expect(spy).not.toHaveBeenCalled();
  });

  it('재생 서버에 못 닿으면 502다', async () => {
    vi.stubEnv('MEDIA_PLAYBACK_ORIGIN', 'http://localhost:9996');
    stubFetch(() => Promise.reject(new TypeError('fetch failed')));
    expect((await call('http://x/media/playback/key-1/list', 'key-1', 'list')).status).toBe(502);
  });
});
