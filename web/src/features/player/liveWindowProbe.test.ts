import { afterEach, describe, expect, it, vi } from 'vitest';
import { stubFetch } from '@/test/mockFetch';
import {
  firstVariantUri,
  parseMediaPlaylist,
  PROBE_TIMEOUT_MS,
  probeLiveWindow,
} from './liveWindowProbe';

// 라이브 창 탐침(설계 r9 2절 · T20 — POK-200 c13g). 넘기기 전에 라이브 목록의 시작 하한 S_live 를 잰다

const LIVE_URL = 'https://media.example/live/key-1/index.m3u8';
/**
 * MediaMTX 는 쿠키가 없으면 index.m3u8 을 `?cookieCheck=1` 로 302 한다(2026-10-27 로컬 실측).
 * 여기서는 디렉터리도 바꿔 상대 URI 를 요청 주소가 아니라 따라간 뒤의 주소로 푸는지 가른다
 */
const REDIRECTED_URL = 'https://origin.example/hls/key-1/index.m3u8?cookieCheck=1';
const PDT0 = Date.parse('2026-10-27T02:20:00.000Z');

/** MediaMTX LL-HLS 다변 목록 모양 — 오디오 EXT-X-MEDIA 의 URI 속성은 변형이 아니다 */
const MULTIVARIANT = [
  '#EXTM3U',
  '#EXT-X-VERSION:10',
  '#EXT-X-INDEPENDENT-SEGMENTS',
  '',
  '#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="audio2",DEFAULT=YES,URI="audio2_stream.m3u8"',
  '',
  '#EXT-X-STREAM-INF:BANDWIDTH=795250,CODECS="avc1.64001e,mp4a.40.2",AUDIO="audio"',
  'video1_stream.m3u8?session=s-1',
  '#EXT-X-STREAM-INF:BANDWIDTH=395250,CODECS="avc1.64001e,mp4a.40.2",AUDIO="audio"',
  'video2_stream.m3u8?session=s-1',
].join('\n');
const VARIANT_URL = 'https://origin.example/hls/key-1/video1_stream.m3u8?session=s-1';

/**
 * 4초 조각 n 개(PDT 연속) · 끝 조각 뒤에 부분 조각(EXT-X-PART) 둘.
 * pdtFrom = PDT 줄을 다는 첫 조각 — MediaMTX LL-HLS 는 끝 두 조각에만 단다(2026-10-27 로컬 실측)
 */
function mediaPlaylist(
  opts: { n?: number; pdt?: boolean; pdtFrom?: number; endlist?: boolean } = {},
): string {
  const { n = 7, pdt = true, pdtFrom = 0, endlist = false } = opts;
  const lines = [
    '#EXTM3U',
    '#EXT-X-VERSION:9',
    '#EXT-X-TARGETDURATION:4',
    '#EXT-X-MEDIA-SEQUENCE:40',
  ];
  for (let i = 0; i < n; i += 1) {
    if (pdt && i >= pdtFrom) {
      lines.push(`#EXT-X-PROGRAM-DATE-TIME:${new Date(PDT0 + i * 4_000).toISOString()}`);
    }
    lines.push('#EXT-X-PART:DURATION=0.5,URI="part0.mp4",INDEPENDENT=YES');
    lines.push('#EXTINF:4.000,', `seg${40 + i}.mp4`);
  }
  lines.push('#EXT-X-PART:DURATION=0.5,URI="part_next0.mp4",INDEPENDENT=YES');
  lines.push('#EXT-X-PART:DURATION=0.5,URI="part_next1.mp4"');
  lines.push('#EXT-X-PRELOAD-HINT:TYPE=PART,URI="part_next2.mp4"');
  if (endlist) lines.push('#EXT-X-ENDLIST');
  return lines.join('\n');
}

/** fetch 가 302 를 따라간 응답 — Response.url 은 생성자로 줄 수 없어 덮는다 */
function playlistResponse(body: string, url: string, status = 200): Response {
  const response = new Response(body, { status });
  Object.defineProperty(response, 'url', { value: url });
  return response;
}

/** 다변 목록(302 뒤) → 첫 변형 → media 를 내는 받기 */
function stubLive(media: string, variantStatus = 200) {
  return stubFetch((url) => {
    if (url === LIVE_URL) return playlistResponse(MULTIVARIANT, REDIRECTED_URL);
    if (url === VARIANT_URL) return playlistResponse(media, VARIANT_URL, variantStatus);
    return playlistResponse('', url, 404);
  });
}

function probe(): ReturnType<typeof probeLiveWindow> {
  return probeLiveWindow(LIVE_URL, new AbortController().signal);
}

afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

describe('firstVariantUri — 다변 목록의 첫 EXT-X-STREAM-INF 다음 URI', () => {
  it('EXT-X-MEDIA 의 URI 속성을 건너뛰고 첫 변형의 URI 를 그대로 낸다', () => {
    expect(firstVariantUri(MULTIVARIANT)).toBe('video1_stream.m3u8?session=s-1');
  });

  it('매체 목록이면(변형 없음) null', () => {
    expect(firstVariantUri(mediaPlaylist())).toBeNull();
  });

  it('STREAM-INF 뒤에 URI 줄이 없으면 null', () => {
    expect(firstVariantUri('#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\n')).toBeNull();
  });
});

describe('parseMediaPlaylist — 완성 조각 · TARGETDURATION · ENDLIST', () => {
  it('EXTINF 를 누적해 조각을 세고 EXT-X-PART · PRELOAD-HINT 는 세지 않는다', () => {
    const parsed = parseMediaPlaylist(mediaPlaylist());
    expect(parsed?.targetDurationS).toBe(4);
    expect(parsed?.endlist).toBe(false);
    expect(parsed?.fragments).toHaveLength(7);
    expect(parsed?.fragments[0]).toEqual({ start: 0, duration: 4, programDateTime: PDT0 });
    expect(parsed?.fragments[6]).toEqual({
      start: 24,
      duration: 4,
      programDateTime: PDT0 + 24_000,
    });
  });

  it('PDT 줄이 빠진 조각은 앞 조각의 PDT + 길이로 잇는다', () => {
    const text = mediaPlaylist({ n: 2 }).replace(
      /#EXT-X-PROGRAM-DATE-TIME:[^\n]*\n(?=#EXT-X-PART[^\n]*\n#EXTINF:4.000,\nseg41)/,
      '',
    );
    expect(parseMediaPlaylist(text)?.fragments[1]?.programDateTime).toBe(PDT0 + 4_000);
  });

  it('앞 조각에 PDT 가 없으면 hls.js 처럼 첫 PDT 에서 길이를 빼 뒤로 채운다(MediaMTX 모양)', () => {
    const parsed = parseMediaPlaylist(mediaPlaylist({ pdtFrom: 5 }));
    expect(parsed?.fragments.map((f) => f.programDateTime)).toEqual(
      [0, 1, 2, 3, 4, 5, 6].map((i) => PDT0 + i * 4_000),
    );
  });

  it('EXT-X-GAP 조각도 조각으로 센다 — hls.js 가 세는 수와 같아야 하한이 같다', () => {
    const text = mediaPlaylist({ pdtFrom: 1 })
      .replace('seg40.mp4', 'gap.mp4')
      .replace('#EXTINF:4.000,\ngap.mp4', '#EXT-X-GAP\n#EXTINF:4.000,\ngap.mp4');
    expect(text).toContain('#EXT-X-GAP');
    expect(parseMediaPlaylist(text)?.fragments[0]).toEqual({
      start: 0,
      duration: 4,
      programDateTime: PDT0,
    });
  });

  it('PDT 가 한 줄도 없으면 조각의 시각은 null 이다', () => {
    const parsed = parseMediaPlaylist(mediaPlaylist({ pdt: false }));
    expect(parsed?.fragments.every((f) => f.programDateTime === null)).toBe(true);
  });

  it('ENDLIST 를 읽는다', () => {
    expect(parseMediaPlaylist(mediaPlaylist({ endlist: true }))?.endlist).toBe(true);
  });

  it('형식 오류(EXTM3U 없음 · TARGETDURATION 없음 · 깨진 PDT · 깨진 EXTINF)는 null', () => {
    expect(parseMediaPlaylist(mediaPlaylist().replace('#EXTM3U\n', ''))).toBeNull();
    expect(parseMediaPlaylist(mediaPlaylist().replace('#EXT-X-TARGETDURATION:4\n', ''))).toBeNull();
    expect(
      parseMediaPlaylist(mediaPlaylist({ n: 1 }).replace(/DATE-TIME:[^\n]*/, 'DATE-TIME:어제')),
    ).toBeNull();
    expect(
      parseMediaPlaylist(mediaPlaylist({ n: 1 }).replace('#EXTINF:4.000,', '#EXTINF:abc,')),
    ).toBeNull();
  });
});

describe('probeLiveWindow — 하한 S_live 와 라이브 목록 끝', () => {
  it('302 뒤 response.url 로 상대 변형을 풀고 floorMs = 첫 PDT + 3 × TD 다', async () => {
    const spy = stubLive(mediaPlaylist());

    await expect(probe()).resolves.toEqual({
      kind: 'ok',
      floorMs: PDT0 + 12_000,
      endMs: PDT0 + 28_000,
    });
    expect(spy.mock.calls.map(([url]) => String(url))).toEqual([LIVE_URL, VARIANT_URL]);
  });

  it('끝 두 조각에만 PDT 가 있는 실물 모양에서도 하한 = 뒤로 채운 첫 PDT + 12초다', async () => {
    stubLive(mediaPlaylist({ pdtFrom: 5 }));
    await expect(probe()).resolves.toEqual({
      kind: 'ok',
      floorMs: PDT0 + 12_000,
      endMs: PDT0 + 28_000,
    });
  });

  it('매체 목록을 바로 주면 받기 한 번으로 잰다', async () => {
    const spy = stubFetch((url) => playlistResponse(mediaPlaylist(), url));

    await expect(probe()).resolves.toMatchObject({ kind: 'ok', floorMs: PDT0 + 12_000 });
    expect(spy).toHaveBeenCalledTimes(1);
  });

  it('ENDLIST · PDT 없음 · 형식 오류는 unavailable', async () => {
    for (const media of [
      mediaPlaylist({ endlist: true }),
      mediaPlaylist({ pdt: false }),
      'not a playlist',
    ]) {
      stubLive(media);
      await expect(probe()).resolves.toEqual({ kind: 'unavailable' });
    }
  });

  it('403 · 404 · 5xx 는 unavailable — 다변 목록에서도 변형 목록에서도', async () => {
    for (const status of [403, 404, 503]) {
      stubFetch((url) => playlistResponse('', url, status));
      await expect(probe()).resolves.toEqual({ kind: 'unavailable' });
      stubLive(mediaPlaylist(), status);
      await expect(probe()).resolves.toEqual({ kind: 'unavailable' });
    }
  });

  it('하한이 유한하지 않으면 unavailable — 첫 조각 길이가 터무니없어 뒤로 채운 시각이 -∞', async () => {
    const huge = mediaPlaylist({ pdtFrom: 1 }).replace('#EXTINF:4.000,', '#EXTINF:1e308,');
    const parsed = parseMediaPlaylist(huge);
    expect(parsed?.fragments[0]?.programDateTime).toBe(Number.NEGATIVE_INFINITY);
    stubLive(huge);
    await expect(probe()).resolves.toEqual({ kind: 'unavailable' });
  });

  it('목록 끝이 유한하지 않으면 unavailable — 끝 조각 길이가 터무니없다', async () => {
    const text = mediaPlaylist({ n: 7 });
    const at = text.lastIndexOf('#EXTINF:4.000,');
    const huge = `${text.slice(0, at)}#EXTINF:1e308,${text.slice(at + '#EXTINF:4.000,'.length)}`;
    stubLive(huge);
    await expect(probe()).resolves.toEqual({ kind: 'unavailable' });
  });

  it('이미 끊긴 signal 이면 받지 않고 unavailable', async () => {
    const spy = stubLive(mediaPlaylist());
    const controller = new AbortController();
    controller.abort();

    await expect(probeLiveWindow(LIVE_URL, controller.signal)).resolves.toEqual({
      kind: 'unavailable',
    });
    expect(spy).not.toHaveBeenCalled();
  });

  it('받기 실패(망 오류)는 unavailable', async () => {
    stubFetch(() => {
      throw new TypeError('Failed to fetch');
    });
    await expect(probe()).resolves.toEqual({ kind: 'unavailable' });
  });

  it(`시한 ${PROBE_TIMEOUT_MS}ms 를 넘기면 받기를 끊고 unavailable`, async () => {
    vi.useFakeTimers();
    const signals: AbortSignal[] = [];
    stubFetch(
      (...[, init]) =>
        new Promise<Response>((...[, reject]) => {
          const signal = init?.signal as AbortSignal;
          signals.push(signal);
          signal.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')));
        }),
    );

    const result = probe();
    await vi.advanceTimersByTimeAsync(PROBE_TIMEOUT_MS - 1);
    expect(signals[0]?.aborted).toBe(false);
    await vi.advanceTimersByTimeAsync(1);

    await expect(result).resolves.toEqual({ kind: 'unavailable' });
    expect(signals[0]?.aborted).toBe(true);
  });

  it('부르는 쪽이 끊으면 결과 없이 AbortError 를 던진다 — 세션이 바뀐 탐침은 버린다', async () => {
    stubFetch(
      (...[, init]) =>
        new Promise<Response>((...[, reject]) => {
          (init?.signal as AbortSignal).addEventListener('abort', () =>
            reject(new DOMException('aborted', 'AbortError')),
          );
        }),
    );
    const controller = new AbortController();

    const result = probeLiveWindow(LIVE_URL, controller.signal);
    controller.abort();

    await expect(result).rejects.toMatchObject({ name: 'AbortError' });
  });

  it('credentials 는 hls.js 와 같은 mediaCredentials() 를 따른다 — 출입증 전 · 뒤', async () => {
    // 출입증 표시는 모듈 상태다 — 새로 읽은 모듈에서만 켜서 이 파일의 다른 시험에 남기지 않는다
    vi.resetModules();
    const playback = await import('@/api/mediaPlayback');
    const fresh = await import('./liveWindowProbe');
    const freshProbe = () => fresh.probeLiveWindow(LIVE_URL, new AbortController().signal);
    const spy = stubLive(mediaPlaylist());
    await freshProbe();
    expect(spy.mock.calls.map(([, init]) => init?.credentials)).toEqual([
      'same-origin',
      'same-origin',
    ]);

    playback.markPlaybackCookiesIssued();
    spy.mockClear();
    await freshProbe();
    expect(playback.mediaCredentials()).toBe('include');
    expect(spy.mock.calls.map(([, init]) => init?.credentials)).toEqual(['include', 'include']);
  });
});
