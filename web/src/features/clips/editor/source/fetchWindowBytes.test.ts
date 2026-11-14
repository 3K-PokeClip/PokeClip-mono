import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { stubFetch } from '@/test/mockFetch';
import type { PlaylistSegment } from './editorPlaylist';

// 창 조각 받기(설계 4절 fetchWindowBytes · 6절 위험 9). 출입증 모듈은 부르기만 하므로 가짜로 바꿔 부른 순서를 본다.
// 쿠키 실음(mediaCredentials)은 진짜 모듈이다 — 모듈 상태라 갈래마다 모듈을 새로 읽는다.
vi.mock('@/api/playbackAccess', () => ({
  ensureMediaAccess: vi.fn(() => null),
  reportPlaybackForbidden: vi.fn(),
}));

const MEDIA_KEY = 'key-1';
const BASE = 'https://media.example/dvr/key-1/r-1';
const MAP = `${BASE}/init/i-1.mp4`;

function segment(seq: number, extra: Partial<PlaylistSegment> = {}): PlaylistSegment {
  return {
    seq,
    uri: `${BASE}/seg/${String(seq).padStart(7, '0')}.m4s`,
    mapUri: MAP,
    pdtMs: 1_000_000 + seq * 4_000,
    durationMs: 4_000,
    timing: 'v2',
    gap: false,
    ...extra,
  };
}

/** 본문 = 주소 글자(ASCII) 그대로라 주소마다 다르다 — 어느 응답이 어느 칸에 갔는지 가린다 */
function bodyOf(url: string): Uint8Array<ArrayBuffer> {
  return Uint8Array.from(url, (char) => char.charCodeAt(0));
}

async function load() {
  vi.resetModules();
  const playback = await import('@/api/mediaPlayback');
  const access = await import('@/api/playbackAccess');
  const windowBytes = await import('./fetchWindowBytes');
  return { playback, access, ...windowBytes };
}

describe('fetchWindowBytes: 창의 init 과 조각 바이트를 받는다', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('init 은 MAP 주소에서, 조각은 목록 순서대로 seq 와 함께 준다', async () => {
    const { fetchWindowBytes } = await load();
    const spy = stubFetch((url) => new Response(bodyOf(url)));

    const got = await fetchWindowBytes(MEDIA_KEY, [segment(300), segment(301)]);

    expect(spy.mock.calls.map(([url]) => url)).toEqual([
      MAP,
      `${BASE}/seg/0000300.m4s`,
      `${BASE}/seg/0000301.m4s`,
    ]);
    expect(got.init).toEqual(bodyOf(MAP));
    expect(got.segments).toEqual([
      { seq: 300, bytes: bodyOf(`${BASE}/seg/0000300.m4s`) },
      { seq: 301, bytes: bodyOf(`${BASE}/seg/0000301.m4s`) },
    ]);
  });

  it('출입증 쿠키를 받기 전에는 same-origin 으로 묻는다', async () => {
    const { fetchWindowBytes } = await load();
    const spy = stubFetch(() => new Response(new Uint8Array(1)));

    await fetchWindowBytes(MEDIA_KEY, [segment(300)]);

    expect(spy.mock.calls.map(([, init]) => init?.credentials)).toEqual([
      'same-origin',
      'same-origin',
    ]);
  });

  it('출입증 쿠키를 받은 뒤에는 include 로 쿠키를 싣는다', async () => {
    const { playback, fetchWindowBytes } = await load();
    playback.markPlaybackCookiesIssued();
    const spy = stubFetch(() => new Response(new Uint8Array(1)));

    await fetchWindowBytes(MEDIA_KEY, [segment(300)]);

    expect(spy.mock.calls.map(([, init]) => init?.credentials)).toEqual(['include', 'include']);
  });

  it('Range · 사용자 정의 머리를 싣지 않는다(프리플라이트 없음) · 응답 머리를 읽지 않는다', async () => {
    const { fetchWindowBytes } = await load();
    const spy = stubFetch(() => {
      const res = new Response(new Uint8Array(1));
      // CloudFront ExposeHeaders 가 비어 브라우저에서는 응답 머리가 보이지 않는다 — 읽으면 시험이 깨진다
      Object.defineProperty(res, 'headers', {
        get() {
          throw new Error('응답 머리를 읽었다');
        },
      });
      return res;
    });

    await fetchWindowBytes(MEDIA_KEY, [segment(300)]);

    expect(spy).toHaveBeenCalledTimes(2);
    for (const [, init] of spy.mock.calls) {
      expect(init?.headers).toBeUndefined();
      expect(init?.method ?? 'GET').toBe('GET');
    }
  });

  it('넘겨주기(redirect)를 따라가지 않는다 — 서명 쿠키를 다른 주소로 싣지 않는다', async () => {
    const { fetchWindowBytes } = await load();
    const spy = stubFetch(() => new Response(new Uint8Array(1)));

    await fetchWindowBytes(MEDIA_KEY, [segment(300), segment(301)]);

    expect(spy.mock.calls.map(([, init]) => init?.redirect)).toEqual(['error', 'error', 'error']);
  });

  it('곧 끝나는 출입증은 새로 받은 뒤에 묻는다', async () => {
    const { access, fetchWindowBytes } = await load();
    const order: string[] = [];
    vi.mocked(access.ensureMediaAccess).mockImplementationOnce(() => {
      order.push('ensure');
      return Promise.resolve({ kind: 'granted', expiresAtMs: null });
    });
    stubFetch((url) => {
      order.push(url);
      return new Response(new Uint8Array(1));
    });

    await fetchWindowBytes(MEDIA_KEY, [segment(300)]);

    expect(vi.mocked(access.ensureMediaAccess)).toHaveBeenCalledWith(MEDIA_KEY);
    expect(order[0]).toBe('ensure');
    expect(order).toHaveLength(3);
  });

  it('403 이면 출입증을 버리고 다시 받은 뒤 그 조각을 한 번 더 묻는다', async () => {
    const { access, fetchWindowBytes } = await load();
    const seg = `${BASE}/seg/0000300.m4s`;
    let segCalls = 0;
    const order: string[] = [];
    vi.mocked(access.reportPlaybackForbidden).mockImplementation(() => order.push('report'));
    vi.mocked(access.ensureMediaAccess).mockImplementation(() => {
      order.push('ensure');
      return null;
    });
    stubFetch((url) => {
      if (url !== seg) return new Response(new Uint8Array(1));
      segCalls += 1;
      order.push(`seg#${segCalls}`);
      return segCalls === 1 ? new Response(null, { status: 403 }) : new Response(bodyOf(seg));
    });

    const got = await fetchWindowBytes(MEDIA_KEY, [segment(300)]);

    expect(got.segments[0]?.bytes).toEqual(bodyOf(seg));
    expect(segCalls).toBe(2);
    expect(vi.mocked(access.reportPlaybackForbidden)).toHaveBeenCalledWith(MEDIA_KEY);
    expect(order.slice(-4)).toEqual(['seg#1', 'report', 'ensure', 'seg#2']);
  });

  it('init 이 403 이어도 출입증을 다시 받은 뒤 init 을 한 번 더 묻는다', async () => {
    const { access, fetchWindowBytes } = await load();
    let mapCalls = 0;
    stubFetch((url) => {
      if (url !== MAP) return new Response(bodyOf(url));
      mapCalls += 1;
      return mapCalls === 1 ? new Response(null, { status: 403 }) : new Response(bodyOf(url));
    });

    const got = await fetchWindowBytes(MEDIA_KEY, [segment(300)]);

    expect(got.init).toEqual(bodyOf(MAP));
    expect(mapCalls).toBe(2);
    expect(vi.mocked(access.reportPlaybackForbidden)).toHaveBeenCalledWith(MEDIA_KEY);
  });

  it('다시 묻는 요청은 새로 받은 출입증의 쿠키 자격을 싣는다(same-origin → include)', async () => {
    const { access, playback, fetchWindowBytes } = await load();
    const seg = `${BASE}/seg/0000300.m4s`;
    // 403 뒤 다시 받기가 쿠키를 심는다
    vi.mocked(access.reportPlaybackForbidden).mockImplementation(() => {
      vi.mocked(access.ensureMediaAccess).mockImplementation(() => {
        playback.markPlaybackCookiesIssued();
        return null;
      });
    });
    let segCalls = 0;
    const spy = stubFetch((url) => {
      if (url !== seg) return new Response(bodyOf(url));
      segCalls += 1;
      return segCalls === 1 ? new Response(null, { status: 403 }) : new Response(bodyOf(seg));
    });

    await fetchWindowBytes(MEDIA_KEY, [segment(300)]);

    expect(
      spy.mock.calls.filter(([url]) => url === seg).map(([, init]) => init?.credentials),
    ).toEqual(['same-origin', 'include']);
  });

  it('출입증을 새로 받고도 403 이면 SegmentForbiddenError(seq) 다', async () => {
    const { fetchWindowBytes, SegmentForbiddenError } = await load();
    const spy = stubFetch((url) =>
      url === MAP ? new Response(new Uint8Array(1)) : new Response(null, { status: 403 }),
    );

    const failure = fetchWindowBytes(MEDIA_KEY, [segment(300)]);

    await expect(failure).rejects.toBeInstanceOf(SegmentForbiddenError);
    await expect(failure).rejects.toMatchObject({ seq: 300 });
    expect(spy.mock.calls.filter(([url]) => url !== MAP)).toHaveLength(2);
  });

  it('404 면 SegmentGoneError 다(조각은 seq · init 은 null) — 다시 묻지 않는다', async () => {
    const { fetchWindowBytes, SegmentGoneError } = await load();
    const segSpy = stubFetch((url) =>
      url === MAP ? new Response(new Uint8Array(1)) : new Response(null, { status: 404 }),
    );

    await expect(fetchWindowBytes(MEDIA_KEY, [segment(301)])).rejects.toMatchObject({ seq: 301 });
    expect(segSpy.mock.calls).toHaveLength(2);

    stubFetch(() => new Response(null, { status: 404 }));
    const initGone = fetchWindowBytes(MEDIA_KEY, [segment(301)]);
    await expect(initGone).rejects.toBeInstanceOf(SegmentGoneError);
    await expect(initGone).rejects.toMatchObject({ seq: null });
  });

  it('그 밖의 실패 응답(500)은 사라짐 · 거절이 아닌 오류로 던진다', async () => {
    const { fetchWindowBytes, SegmentForbiddenError, SegmentGoneError } = await load();
    stubFetch(() => new Response(null, { status: 500 }));

    const failure = fetchWindowBytes(MEDIA_KEY, [segment(300)]);

    await expect(failure).rejects.toThrow('500');
    await expect(failure).rejects.not.toBeInstanceOf(SegmentGoneError);
    await expect(failure).rejects.not.toBeInstanceOf(SegmentForbiddenError);
  });

  it('넘긴 AbortSignal 을 모든 요청에 싣고, 끊으면 AbortError 로 끝난다', async () => {
    const { fetchWindowBytes } = await load();
    const controller = new AbortController();
    const spy = stubFetch(
      (_url, init) =>
        new Promise<Response>((_resolve, reject) => {
          init?.signal?.addEventListener('abort', () => reject(init.signal?.reason));
        }),
    );

    const pending = fetchWindowBytes(MEDIA_KEY, [segment(300)], controller.signal);
    await vi.waitFor(() => expect(spy).toHaveBeenCalledTimes(2));
    controller.abort();

    await expect(pending).rejects.toMatchObject({ name: 'AbortError' });
    for (const [, init] of spy.mock.calls) expect(init?.signal).toBe(controller.signal);
  });

  it('GAP 조각 · 서로 다른 MAP · 빈 목록은 묻지 않고 거절한다', async () => {
    const { fetchWindowBytes } = await load();
    const spy = stubFetch(() => new Response(new Uint8Array(1)));

    await expect(fetchWindowBytes(MEDIA_KEY, [segment(300, { gap: true })])).rejects.toThrow();
    await expect(
      fetchWindowBytes(MEDIA_KEY, [segment(300), segment(301, { mapUri: `${BASE}/init/i-2.mp4` })]),
    ).rejects.toThrow();
    await expect(fetchWindowBytes(MEDIA_KEY, [])).rejects.toThrow();
    expect(spy).not.toHaveBeenCalled();
  });
});
