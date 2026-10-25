import { afterEach, describe, expect, it, vi } from 'vitest';
import { jsonResponse, stubFetch } from '@/test/mockFetch';
import { parseSpanStartMs } from './mediaPlayback';

describe('parseSpanStartMs — 녹화 시작을 ms로 올린다(POK-253)', () => {
  it('ms 아래가 있으면 올린다 — 내리면 두 번째 구간 요청이 틈 안으로 들어가 404다(실측)', () => {
    expect(parseSpanStartMs('2026-09-30T01:58:16.372191Z')).toBe(
      Date.parse('2026-09-30T01:58:16.373Z'),
    );
  });

  it('ms 아래가 0이거나 없으면 그대로다', () => {
    expect(parseSpanStartMs('2026-09-30T01:58:16.372000Z')).toBe(
      Date.parse('2026-09-30T01:58:16.372Z'),
    );
    expect(parseSpanStartMs('2026-09-30T01:58:16.372Z')).toBe(
      Date.parse('2026-09-30T01:58:16.372Z'),
    );
    expect(parseSpanStartMs('2026-09-30T01:58:16Z')).toBe(Date.parse('2026-09-30T01:58:16Z'));
  });
});

describe('lookupRecordingSpans: 녹화 없음과 물을 수 없음을 가른다(리뷰 6라운드)', () => {
  // 재생 서버가 없거나 조회가 실패해도 빈 목록으로 접어 화면이 「녹화 영상을 찾지 못했어요」로 굳었다
  let fetchSpy: ReturnType<typeof stubFetch>;
  async function load(
    base: string,
    answer: (url: string, init?: RequestInit) => Promise<Response>,
  ) {
    vi.resetModules();
    vi.stubEnv('NEXT_PUBLIC_MEDIA_PLAYBACK_BASE_URL', base);
    fetchSpy = stubFetch(answer);
    return import('./mediaPlayback');
  }

  afterEach(() => {
    vi.unstubAllEnvs();
    vi.unstubAllGlobals();
  });

  it('재생 서버 주소가 없으면 묻지 않고 null이다: 녹화가 없다는 뜻이 아니다', async () => {
    // 물으면 빈 목록이 오게 둔다: 실패로 두면 조회 함수가 그 실패를 null로 삼켜, 묻지 않는지를 재지 못했다(리뷰 7라운드)
    const m = await load('', () => Promise.resolve(jsonResponse(200, [])));
    expect(await m.lookupRecordingSpans('vod-1')).toBeNull();
    expect(await m.fetchRecordingSpans('vod-1')).toEqual([]);
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it('녹화 폴더가 없다는 400과 404는 녹화 없음(빈 배열)이다', async () => {
    const bad = await load('http://pb', () => Promise.resolve(new Response('{}', { status: 400 })));
    expect(await bad.lookupRecordingSpans('vod-1')).toEqual([]);
    const missing = await load('http://pb', () =>
      Promise.resolve(new Response('{}', { status: 404 })),
    );
    expect(await missing.lookupRecordingSpans('vod-1')).toEqual([]);
  });

  it('서버 오류와 네트워크 오류는 null이다: 목록·편집기 쪽은 전처럼 빈 배열이다', async () => {
    const down = await load('http://pb', () => Promise.resolve(new Response('', { status: 503 })));
    expect(await down.lookupRecordingSpans('vod-1')).toBeNull();
    const offline = await load('http://pb', () => Promise.reject(new TypeError('network')));
    expect(await offline.lookupRecordingSpans('vod-1')).toBeNull();
    expect(await offline.fetchRecordingSpans('vod-1')).toEqual([]);
  });

  it.each([
    ['연결만 받고 답하지 않는 서버', false],
    ['머리만 보내고 본문을 끝내지 않는 서버', true],
  ])(
    '답이 30초 안에 오지 않으면 null이다(%s): 끝없이 기다리지 않는다',
    async (_name, headersOnly) => {
      // 지난 방송 채팅이 이 답을 기다리므로, 답이 없으면 채팅이 요청 하나 없이 「불러오고 있어요」에 멈췄다(POK-324 리뷰 2라운드).
      // 시한은 본문까지 걸린다: 머리만 오고 본문이 멈춘 서버에서도 끝나야 한다(리뷰 3라운드 P9). 10초였을 때는 느리게라도 답하는
      // 서버를 늘 묻지 못한 것으로 봐 녹화를 못 틀었다(PR #230)
      vi.useFakeTimers();
      try {
        const hung = await load('http://pb', (_url, init) => {
          const aborted = () =>
            new Promise<never>((_, reject) => {
              init?.signal?.addEventListener('abort', () =>
                reject(new DOMException('aborted', 'AbortError')),
              );
            });
          if (!headersOnly) return aborted();
          return Promise.resolve({ ok: true, status: 200, json: aborted } as unknown as Response);
        });
        let answer: unknown = 'waiting';
        void hung.lookupRecordingSpans('vod-1').then((spans) => {
          answer = spans;
        });
        await vi.advanceTimersByTimeAsync(29_999);
        expect(answer).toBe('waiting');
        await vi.advanceTimersByTimeAsync(1);
        expect(answer).toBeNull();
      } finally {
        vi.useRealTimers();
      }
    },
  );

  it('apiFetch 밖 요청이라도 활동으로 알린다: 본문까지 받아야 끝이다 — 지난 방송 목록이 기다리니 채널 전환 화면도 기다린다(PR #342 2차 리뷰)', async () => {
    let respond: (res: Response) => void = () => {};
    const m = await load(
      'http://pb',
      () =>
        new Promise<Response>((r) => {
          respond = r;
        }),
    );
    const { subscribeApiActivity } = await import('./client');
    const events: string[] = [];
    const off = subscribeApiActivity(({ type }) => events.push(type));

    const done = m.lookupRecordingSpans('vod-1');
    expect(events).toEqual(['start']);
    respond(new Response('[]', { status: 200 }));
    await done;

    expect(events).toEqual(['start', 'end']);
    off();
  });

  it('녹화가 있으면 구간을 준다', async () => {
    const m = await load('http://pb', () =>
      Promise.resolve(
        new Response(JSON.stringify([{ start: '2026-10-08T05:41:16.285Z', duration: 10 }]), {
          status: 200,
        }),
      ),
    );
    expect(await m.lookupRecordingSpans('vod-1')).toEqual([
      { startMs: Date.parse('2026-10-08T05:41:16.285Z'), durationSeconds: 10 },
    ]);
  });
});

describe('녹화 목록 요청의 쿠키 싣기 (POK-317)', () => {
  afterEach(() => {
    vi.unstubAllEnvs();
    vi.unstubAllGlobals();
  });

  it('출입증이 발급된 뒤에만 쿠키를 싣는다: CDN 뒤 녹화 목록은 쿠키가 없으면 403이다', async () => {
    vi.resetModules();
    vi.stubEnv('NEXT_PUBLIC_MEDIA_PLAYBACK_BASE_URL', 'https://media.test');
    const spy = stubFetch(() => Promise.resolve(jsonResponse(200, [])));
    const m = await import('./mediaPlayback');

    await m.lookupRecordingSpans('vod-1');
    m.markPlaybackCookiesIssued();
    await m.lookupRecordingSpans('vod-1');

    expect(spy.mock.calls.map(([, init]) => init?.credentials)).toEqual(['same-origin', 'include']);
  });
});

describe('녹화 재생 주소는 출입증 경로 아래다 (POK-317)', () => {
  afterEach(() => {
    vi.unstubAllEnvs();
    vi.unstubAllGlobals();
  });

  async function load() {
    vi.resetModules();
    vi.stubEnv('NEXT_PUBLIC_MEDIA_PLAYBACK_BASE_URL', 'https://media.test/');
    const spy = stubFetch(() => Promise.resolve(jsonResponse(200, [])));
    return { m: await import('./mediaPlayback'), spy };
  }

  it('녹화 목록은 {base}/playback/{키}/list 이고 path 를 경로의 키로 하나만 싣는다: Media 상자 nginx 가 path 하나 · 경로 키와 같음일 때만 통과시킨다', async () => {
    const { m, spy } = await load();
    await m.lookupRecordingSpans('key-1');
    const url = new URL(String(spy.mock.calls[0]?.[0]));
    expect(url.origin + url.pathname).toBe('https://media.test/playback/key-1/list');
    expect(url.searchParams.getAll('path')).toEqual(['key-1']);
  });

  it('영상 조각은 {base}/playback/{키}/get 이고 시작 · 길이 · 형식과 경로의 키로 path 하나를 싣는다: Media 상자 nginx 가 path 하나 · 경로 키와 같음일 때만 통과시킨다', async () => {
    const { m } = await load();
    const url = new URL(m.recordingClipUrl('key-1', Date.parse('2026-10-23T01:00:00Z'), 12.5));
    expect(url.origin + url.pathname).toBe('https://media.test/playback/key-1/get');
    expect([...url.searchParams.keys()].sort()).toEqual(['duration', 'format', 'path', 'start']);
    expect(url.searchParams.getAll('path')).toEqual(['key-1']);
    expect(url.searchParams.get('start')).toBe('2026-10-23T01:00:00.000Z');
  });

  it.each(['key-1', 'cam_01-a'])(
    '키 %s 의 path 값은 목록 · 조각 둘 다 경로의 키와 같다: 다르면 nginx 가 403 을 낸다',
    async (key) => {
      const { m, spy } = await load();
      await m.lookupRecordingSpans(key);
      const urls = [
        new URL(String(spy.mock.calls[0]?.[0])),
        new URL(m.recordingClipUrl(key, Date.parse('2026-10-23T01:00:00Z'), 4)),
      ];
      for (const url of urls) {
        const pathKey = decodeURIComponent(url.pathname.split('/')[2] ?? '');
        expect(pathKey).toBe(key);
        expect(url.searchParams.getAll('path')).toEqual([pathKey]);
      }
    },
  );
});

describe('녹화 목록이 403이면 그 키의 출입증을 버리게 알린다 (PR #332 codex)', () => {
  afterEach(() => {
    vi.unstubAllEnvs();
    vi.unstubAllGlobals();
  });

  it('403은 묻지 못함(null)이고, 알림을 받은 쪽이 그 키를 안다: 브라우저가 쿠키를 지웠으면 다시 받아야 한다', async () => {
    vi.resetModules();
    vi.stubEnv('NEXT_PUBLIC_MEDIA_PLAYBACK_BASE_URL', 'https://media.test');
    stubFetch(() => Promise.resolve(jsonResponse(403, {})));
    const m = await import('./mediaPlayback');
    const forbidden: string[] = [];
    m.onPlaybackForbidden((key) => forbidden.push(key));
    expect(await m.lookupRecordingSpans('key-1')).toBeNull();
    expect(forbidden).toEqual(['key-1']);
  });
});
