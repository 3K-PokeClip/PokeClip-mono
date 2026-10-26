import { afterEach, describe, expect, it, vi } from 'vitest';
import { jsonResponse, stubFetch } from '@/test/mockFetch';
import {
  fetchRewindStatus,
  parseRewindStatus,
  roundRewindUrl,
  streamRewindUrl,
} from './rewindStatus';

const BASE = 'https://media.pokeclip.com';
const DVR_URL = 'https://media.pokeclip.com/dvr/key-1/sess-1/index.m3u8';

/** 계약3 7-2절의 200 본문(되감기 가능) */
const READY_LIVE = {
  ready: true,
  mode: 'live',
  url: DVR_URL,
  firstPdt: '2026-10-25T05:00:04.000Z',
  headPdt: '2026-10-25T05:59:58.000Z',
};

describe('제어면 주소 두 경로(계약3 7-2절 · 설계 5.1절)', () => {
  it('스트림 경로는 {base}/api/streams/{key}/rewind 다', () => {
    expect(streamRewindUrl(BASE, 'key-1')).toBe(
      'https://media.pokeclip.com/api/streams/key-1/rewind',
    );
  });

  it('회차별 경로는 {base}/api/streams/{key}/rounds/{roundId}/rewind 다', () => {
    expect(roundRewindUrl(BASE, 'key-1', '01JB7Z')).toBe(
      'https://media.pokeclip.com/api/streams/key-1/rounds/01JB7Z/rewind',
    );
  });

  it('주소 끝의 / 를 정규화한다 — 이중 슬래시는 엣지가 다른 경로로 본다', () => {
    expect(streamRewindUrl(`${BASE}/`, 'key-1')).toBe(
      'https://media.pokeclip.com/api/streams/key-1/rewind',
    );
  });

  it('주소가 비었거나 키 형식이 어긋나면 null — 폴링을 열지 않는다(경로 조작 방지)', () => {
    expect(streamRewindUrl(null, 'key-1')).toBeNull();
    expect(streamRewindUrl('', 'key-1')).toBeNull();
    expect(streamRewindUrl(BASE, '../x')).toBeNull();
    expect(streamRewindUrl(BASE, '')).toBeNull();
    expect(roundRewindUrl(BASE, 'key-1', 'a/b')).toBeNull();
    expect(roundRewindUrl(BASE, 'k/1', 'r1')).toBeNull();
  });
});

describe('parseRewindStatus — 응답 검증(보안: url 은 재생기 소스가 된다)', () => {
  it('되감기 가능 본문을 시각(ms)으로 바꿔 받는다', () => {
    expect(parseRewindStatus(READY_LIVE, BASE)).toEqual({
      ready: true,
      mode: 'live',
      url: DVR_URL,
      firstPdtMs: Date.parse('2026-10-25T05:00:04.000Z'),
      headPdtMs: Date.parse('2026-10-25T05:59:58.000Z'),
    });
  });

  it('미준비 · 국면 모름은 값이 null 인 200 이다', () => {
    const blank = { ready: false, mode: null, url: null, firstPdt: null, headPdt: null };
    expect(parseRewindStatus(blank, BASE)).toEqual({
      ready: false,
      mode: null,
      url: null,
      firstPdtMs: null,
      headPdtMs: null,
    });
    for (const mode of ['live', 'ending', 'ended']) {
      expect(parseRewindStatus({ ...blank, mode }, BASE)?.mode).toBe(mode);
    }
  });

  it('vod 는 /vod 목록 url 과 범위를 싣는다', () => {
    const vod = { ...READY_LIVE, mode: 'vod', url: `${BASE}/vod/key-1/round-1/index.m3u8` };
    expect(parseRewindStatus(vod, BASE)?.url).toBe(`${BASE}/vod/key-1/round-1/index.m3u8`);
  });

  it('키 다섯 가운데 하나라도 없으면 형식 오류다', () => {
    for (const key of Object.keys(READY_LIVE)) {
      const partial: Record<string, unknown> = { ...READY_LIVE };
      delete partial[key];
      expect(parseRewindStatus(partial, BASE)).toBeNull();
    }
  });

  it('형이 어긋나면 형식 오류다', () => {
    const bad: unknown[] = [
      null,
      'ready',
      [],
      { ...READY_LIVE, ready: 'true' },
      { ...READY_LIVE, mode: 'paused' },
      { ...READY_LIVE, url: 42 },
      { ...READY_LIVE, firstPdt: 1730000000000 },
      { ...READY_LIVE, firstPdt: '어제' },
      { ...READY_LIVE, headPdt: '2026-10-25 05:59:58' },
    ];
    for (const body of bad) expect(parseRewindStatus(body, BASE)).toBeNull();
  });

  it('ready 인데 url · 범위가 없거나 범위가 뒤집혔으면 형식 오류다', () => {
    expect(parseRewindStatus({ ...READY_LIVE, url: null }, BASE)).toBeNull();
    expect(parseRewindStatus({ ...READY_LIVE, firstPdt: null }, BASE)).toBeNull();
    expect(
      parseRewindStatus(
        { ...READY_LIVE, firstPdt: READY_LIVE.headPdt, headPdt: READY_LIVE.firstPdt },
        BASE,
      ),
    ).toBeNull();
    expect(parseRewindStatus({ ...READY_LIVE, mode: null }, BASE)).toBeNull();
    expect(parseRewindStatus({ ...READY_LIVE, mode: 'ended' }, BASE)).toBeNull();
  });

  it('url 은 제어면과 같은 출처의 m3u8 만 받는다 — 남의 출처 · 스킴은 형식 오류다', () => {
    const reject = [
      'https://evil.example/dvr/key-1/sess-1/index.m3u8',
      'http://media.pokeclip.com/dvr/key-1/sess-1/index.m3u8',
      'javascript:alert(1)',
      '/dvr/key-1/sess-1/index.m3u8',
      'https://media.pokeclip.com/dvr/key-1/sess-1/index.mp4',
    ];
    for (const url of reject) expect(parseRewindStatus({ ...READY_LIVE, url }, BASE)).toBeNull();
  });

  it('로컬 실험대는 루프백 http 끼리만 출처가 달라도 받는다(제어면 28086 · 엣지 28480)', () => {
    const local = { ...READY_LIVE, url: 'http://127.0.0.1:28480/dvr/key-1/sess-1/index.m3u8' };
    expect(parseRewindStatus(local, 'http://127.0.0.1:28086')?.url).toBe(local.url);
    expect(parseRewindStatus(local, 'http://localhost:8086')?.url).toBe(local.url);
    // 제어면이 루프백이 아니면 루프백 url 도 남의 출처다
    expect(parseRewindStatus(local, BASE)).toBeNull();
  });
});

describe('fetchRewindStatus — 결과 합집합(설계 5.1절 결과 해석)', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('쿠키 없이 · 사용자 정의 머리 없이 묻는다(단순 요청 — preflight 없음)', async () => {
    const spy = stubFetch(() => jsonResponse(200, READY_LIVE));
    const signal = new AbortController().signal;
    await fetchRewindStatus(`${BASE}/api/streams/key-1/rewind`, BASE, signal);
    const init = spy.mock.calls[0]?.[1];
    expect(init?.credentials).toBe('omit');
    expect(init?.headers).toBeUndefined();
    expect(init?.signal).toBe(signal);
  });

  it('200 은 ok · 404 는 none · 503 과 그 밖 5xx 는 unavailable 이다', async () => {
    stubFetch(() => jsonResponse(200, READY_LIVE));
    expect((await fetchRewindStatus('u', BASE)).kind).toBe('ok');
    stubFetch(() => jsonResponse(404, { error: 'not_found' }));
    expect(await fetchRewindStatus('u', BASE)).toEqual({ kind: 'none' });
    for (const status of [503, 500, 502, 403]) {
      stubFetch(() => jsonResponse(status, { error: 'unavailable' }));
      expect(await fetchRewindStatus('u', BASE)).toEqual({ kind: 'unavailable' });
    }
  });

  it('형식 오류 · JSON 아님 · 망 오류는 unavailable 이다(직전 상태 유지)', async () => {
    stubFetch(() => jsonResponse(200, { ...READY_LIVE, mode: 'paused' }));
    expect(await fetchRewindStatus('u', BASE)).toEqual({ kind: 'unavailable' });
    stubFetch(() => new Response('<html>', { status: 200 }));
    expect(await fetchRewindStatus('u', BASE)).toEqual({ kind: 'unavailable' });
    stubFetch(() => Promise.reject(new TypeError('Failed to fetch')));
    expect(await fetchRewindStatus('u', BASE)).toEqual({ kind: 'unavailable' });
  });

  it('끊은 요청은 결과를 내지 않고 AbortError 를 던진다 — 부르는 쪽이 버린다', async () => {
    stubFetch((_url, init) => {
      const abort = new DOMException('aborted', 'AbortError');
      return init?.signal?.aborted ? Promise.reject(abort) : jsonResponse(200, READY_LIVE);
    });
    const controller = new AbortController();
    controller.abort();
    await expect(fetchRewindStatus('u', BASE, controller.signal)).rejects.toThrow('aborted');
  });
});

describe('fetchRewindStatus — 버린 답의 관측(cc M2)', () => {
  afterEach(() => vi.unstubAllGlobals());

  /** 사유별 「한 번」은 모듈에 남으므로 시험마다 새로 읽는다 */
  async function freshModule() {
    vi.resetModules();
    return import('./rewindStatus');
  }

  it('형식 오류 · 목록 주소 거부는 사유마다 한 번만 console.warn 으로 남긴다', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const { fetchRewindStatus: fetchOnce } = await freshModule();
    stubFetch(() => jsonResponse(200, { ...READY_LIVE, mode: 'paused' }));
    await fetchOnce('u', BASE);
    await fetchOnce('u', BASE);
    stubFetch(() => new Response('<html>', { status: 200 }));
    await fetchOnce('u', BASE);
    expect(warn).toHaveBeenCalledTimes(1);
    expect(warn.mock.calls[0]?.[0]).toContain('형식 오류');
    stubFetch(() => jsonResponse(200, { ...READY_LIVE, url: 'https://evil.test/a/index.m3u8' }));
    await fetchOnce('u', BASE);
    await fetchOnce('u', BASE);
    // 같은 출처라도 m3u8 이 아닌 주소는 출처 불일치가 아니다 — 같은 「목록 주소 거부」 사유로 묶인다(cx L)
    stubFetch(() => jsonResponse(200, { ...READY_LIVE, url: `${BASE}/a/index.json` }));
    await fetchOnce('u', BASE);
    expect(warn).toHaveBeenCalledTimes(2);
    expect(warn.mock.calls[1]?.[0]).toContain('목록 주소 거부');
    warn.mockRestore();
  });

  it('503 · 그 밖 상태 · 정상 답은 남기지 않는다', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const { fetchRewindStatus: fetchOnce } = await freshModule();
    for (const status of [503, 500, 404]) {
      stubFetch(() => jsonResponse(status, { error: 'x' }));
      await fetchOnce('u', BASE);
    }
    stubFetch(() => jsonResponse(200, READY_LIVE));
    await fetchOnce('u', BASE);
    expect(warn).not.toHaveBeenCalled();
    warn.mockRestore();
  });
});
