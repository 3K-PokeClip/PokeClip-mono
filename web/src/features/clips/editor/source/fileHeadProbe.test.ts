import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { stubFetch } from '@/test/mockFetch';

// ② 머리 판독 · seq 별 캐시(설계 prw2 3.2 결정 3 · 4절 · 계획 E4b-1 검증 묶음 B-1). ② 는 S3 서명 URL 이라 쿠키를 싣지 않고
// (credentials omit — 버킷 CORS 에 Allow-Credentials 가 없다) Range 하나만 싣는다. 캐시가 모듈 상태라 시험마다 모듈을 새로 읽는다.

const TESTDATA = join(
  __dirname,
  '..',
  '..',
  '..',
  '..',
  '..',
  '..',
  'media',
  'internal',
  'playback',
  'testdata',
);
/** 포크 녹화 실물 — 영상 1(트랙 1 · 90000) + 소리 6(트랙 2–7 · 44100), 소리가 0 에서 먼저 시작 · moof 하나 */
const segment1v6a = new Uint8Array(readFileSync(join(TESTDATA, 'segment_1v6a.mp4')));

const MEDIA_KEY = 'key-1';
/** 서명이 든 ② 주소 — 경고 · 상태에 남으면 안 되는 값 */
const SIGNED = 'https://seg.example/key-1/000300.mp4?X-Amz-Signature=secret-sig';

// --- 합성 바이트(실물에 없는 배치: 둘째 64KB 의 트랙 · 끝까지 빈 트랙 · open GOP) ---

function concat(...parts: Uint8Array[]): Uint8Array {
  const out = new Uint8Array(parts.reduce((sum, part) => sum + part.length, 0));
  let offset = 0;
  for (const part of parts) {
    out.set(part, offset);
    offset += part.length;
  }
  return out;
}

function u32(value: number): Uint8Array {
  const out = new Uint8Array(4);
  new DataView(out.buffer).setUint32(0, value);
  return out;
}

function i32(value: number): Uint8Array {
  const out = new Uint8Array(4);
  new DataView(out.buffer).setInt32(0, value);
  return out;
}

function ascii(text: string): Uint8Array {
  return Uint8Array.from(text, (ch) => ch.charCodeAt(0));
}

function box(type: string, ...payload: Uint8Array[]): Uint8Array {
  const body = concat(...payload);
  return concat(u32(body.length + 8), ascii(type), body);
}

function fullBox(type: string, version: number, flags: number, ...payload: Uint8Array[]) {
  return box(type, u32(((version << 24) | flags) >>> 0), ...payload);
}

function trak(trackId: number, handler: string, timescale: number): Uint8Array {
  return box(
    'trak',
    fullBox('tkhd', 0, 3, u32(0), u32(0), u32(trackId), u32(0)),
    box(
      'mdia',
      fullBox('mdhd', 0, 0, u32(0), u32(0), u32(timescale), u32(0)),
      fullBox('hdlr', 0, 0, u32(0), ascii(handler), u32(0), u32(0), u32(0)),
    ),
  );
}

/** 표본 { 길이 · cto } 들의 traf(trun v1 · 0x100 길이 · 0x800 cto) */
function traf(trackId: number, tfdt: number, samples: { duration: number; cto?: number }[]) {
  const fields = samples.flatMap((s) => [u32(s.duration), i32(s.cto ?? 0)]);
  return box(
    'traf',
    fullBox('tfhd', 0, 0x020000, u32(trackId)),
    fullBox('tfdt', 0, 0, u32(tfdt)),
    fullBox('trun', 1, 0x100 | 0x800, u32(samples.length), ...fields),
  );
}

function moof(...trafs: Uint8Array[]): Uint8Array {
  return box('moof', fullBox('mfhd', 0, 0, u32(1)), ...trafs);
}

function file(traks: Uint8Array[], ...rest: Uint8Array[]): Uint8Array {
  return concat(box('ftyp', ascii('isom'), u32(0)), box('moov', ...traks), ...rest);
}

const VIDEO = trak(1, 'vide', 90000);
const AUDIO = trak(2, 'soun', 48000);

/** 64KB 를 넘기는 mdat — 그 뒤 moof 는 둘째 판독에서야 보인다 */
const BIG_MDAT = box('mdat', new Uint8Array(70_000));

// --- 가짜 S3: Range 하나에 206 조각, 파일 밖 시작이면 416, Range 없으면 200 전체 ---

function serve(bytes: Uint8Array, options: { rangeFails?: boolean } = {}) {
  return stubFetch((_url, init) => {
    const range = new Headers(init?.headers).get('Range');
    if (range === null) return new Response(bytes.slice(), { status: 200 });
    if (options.rangeFails) throw new TypeError('Failed to fetch');
    const [, from, to] = /^bytes=(\d+)-(\d+)$/.exec(range)!;
    const start = Number(from);
    if (start >= bytes.length) return new Response(null, { status: 416 });
    return new Response(bytes.slice(start, Number(to) + 1), { status: 206 });
  });
}

function rangesOf(spy: ReturnType<typeof stubFetch>): (string | null)[] {
  return spy.mock.calls.map(([, init]) => new Headers(init?.headers).get('Range'));
}

async function load() {
  vi.resetModules();
  return import('./fileHeadProbe');
}

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('ensureHeads: ② 머리를 64KB 씩 읽어 seq 캐시에 채운다', () => {
  it('실물 ② 파일은 첫 64KB 하나로 읽고, 샘플 있는 트랙의 첫 DTS 최솟값 · PTS 최솟값 · 영상 첫 DTS 를 초로 준다', async () => {
    const { ensureHeads, headCacheFor } = await load();
    const spy = serve(segment1v6a);

    await ensureHeads(MEDIA_KEY, [{ seq: 300, url: SIGNED }], []);

    expect(rangesOf(spy)).toEqual(['bytes=0-65535']);
    expect(headCacheFor(MEDIA_KEY).get(300)).toEqual({
      state: 'ready',
      head: { hd: 0, hp: 0, videoDts: 8136 / 90000 },
    });
  });

  it('서명 URL 은 쿠키 없이(omit) · Range 머리 하나만 실어 받는다', async () => {
    const { ensureHeads } = await load();
    const spy = serve(segment1v6a);

    await ensureHeads(MEDIA_KEY, [{ seq: 300, url: SIGNED }], []);

    const [url, init] = spy.mock.calls[0]!;
    expect(url).toBe(SIGNED);
    expect(init?.credentials).toBe('omit');
    expect([...new Headers(init?.headers).keys()]).toEqual(['range']);
  });

  it('첫 64KB 에서 샘플 있는 traf 를 못 찾은 트랙이 남으면 다음 64KB 를 받아 그 트랙까지 넣는다', async () => {
    const { ensureHeads, headCacheFor } = await load();
    const bytes = file(
      [VIDEO, AUDIO],
      moof(traf(1, 9000, [{ duration: 3000 }])),
      BIG_MDAT,
      moof(traf(2, 2400, [{ duration: 1024 }])),
    );
    const spy = serve(bytes);

    await ensureHeads(MEDIA_KEY, [{ seq: 7, url: SIGNED }], []);

    expect(rangesOf(spy)).toEqual(['bytes=0-65535', 'bytes=65536-131071']);
    expect(headCacheFor(MEDIA_KEY).get(7)).toEqual({
      state: 'ready',
      head: { hd: 0.05, hp: 0.05, videoDts: 0.1 },
    });
  });

  it('파일 끝까지 표본이 없는 트랙(빈 traf 만 있음)은 Hd · Hp 계산에서 뺀다', async () => {
    const { ensureHeads, headCacheFor } = await load();
    const bytes = file(
      [VIDEO, AUDIO, trak(3, 'soun', 48000)],
      moof(
        traf(3, 0, []),
        traf(1, 9000, [{ duration: 3000 }]),
        traf(2, 4800, [{ duration: 1024 }]),
      ),
    );
    serve(bytes);

    await ensureHeads(MEDIA_KEY, [{ seq: 7, url: SIGNED }], []);

    expect(headCacheFor(MEDIA_KEY).get(7)).toEqual({
      state: 'ready',
      head: { hd: 0.1, hp: 0.1, videoDts: 0.1 },
    });
  });

  it('파일 끝이 64KB 경계에 걸려 다음 Range 가 416 이면 거기까지로 읽는다', async () => {
    const { ensureHeads, headCacheFor } = await load();
    const head = file([VIDEO, AUDIO], moof(traf(1, 9000, [{ duration: 3000 }])));
    const pad = box('free', new Uint8Array(65_536 - head.length - 8));
    serve(concat(head, pad));

    await ensureHeads(MEDIA_KEY, [{ seq: 7, url: SIGNED }], []);

    expect(headCacheFor(MEDIA_KEY).get(7)).toEqual({
      state: 'ready',
      head: { hd: 0.1, hp: 0.1, videoDts: 0.1 },
    });
  });

  it('영상 첫 traf 의 뒤 표본 PTS 가 더 작으면(open GOP) Hp 는 그 표본들의 PTS 최솟값이다', async () => {
    const { ensureHeads, headCacheFor } = await load();
    const bytes = file(
      [VIDEO, AUDIO],
      moof(
        traf(1, 9000, [
          { duration: 3000, cto: 9000 },
          { duration: 3000, cto: -3000 },
        ]),
        traf(2, 7200, [{ duration: 1024 }]),
      ),
    );
    serve(bytes);

    await ensureHeads(MEDIA_KEY, [{ seq: 7, url: SIGNED }], []);

    const entry = headCacheFor(MEDIA_KEY).get(7);
    expect(entry?.state === 'ready' && entry.head).toEqual({ hd: 0.1, hp: 0.1, videoDts: 0.1 });
  });

  it('Range 요청이 망 · CORS 오류로 실패하면 Range 없이 한 번 전체를 받아 읽는다', async () => {
    const { ensureHeads, headCacheFor } = await load();
    const spy = serve(segment1v6a, { rangeFails: true });

    await ensureHeads(MEDIA_KEY, [{ seq: 300, url: SIGNED }], []);

    expect(rangesOf(spy)).toEqual(['bytes=0-65535', null]);
    expect(spy.mock.calls[1]![1]?.credentials).toBe('omit');
    expect(headCacheFor(MEDIA_KEY).get(300)?.state).toBe('ready');
  });

  it('서버가 Range 를 무시하고 200 전체를 주면 그 본문으로 읽고 더 받지 않는다', async () => {
    const { ensureHeads, headCacheFor } = await load();
    const spy = stubFetch(() => new Response(segment1v6a.slice(), { status: 200 }));

    await ensureHeads(MEDIA_KEY, [{ seq: 300, url: SIGNED }], []);

    expect(spy).toHaveBeenCalledTimes(1);
    expect(headCacheFor(MEDIA_KEY).get(300)?.state).toBe('ready');
  });

  it('소리 주소 응답에 행이 없는 seq 는 받지 않고 missing 이다', async () => {
    const { ensureHeads, headCacheFor } = await load();
    const spy = serve(segment1v6a);

    await ensureHeads(MEDIA_KEY, [], [301]);

    expect(spy).not.toHaveBeenCalled();
    expect(headCacheFor(MEDIA_KEY).get(301)).toEqual({ state: 'missing' });
  });

  it('missing 이던 seq 도 행이 오면 읽어 ready 로 바꾼다', async () => {
    const { ensureHeads, headCacheFor } = await load();
    serve(segment1v6a);
    await ensureHeads(MEDIA_KEY, [], [300]);

    await ensureHeads(MEDIA_KEY, [{ seq: 300, url: SIGNED }], []);

    expect(headCacheFor(MEDIA_KEY).get(300)?.state).toBe('ready');
  });

  it('읽은 seq 는 다시 받지 않는다(조각 머리는 바뀌지 않는다) · missing 으로도 되돌리지 않는다', async () => {
    const { ensureHeads, headCacheFor } = await load();
    const spy = serve(segment1v6a);
    await ensureHeads(MEDIA_KEY, [{ seq: 300, url: SIGNED }], []);

    await ensureHeads(MEDIA_KEY, [{ seq: 300, url: SIGNED }], [300]);

    expect(spy).toHaveBeenCalledTimes(1);
    expect(headCacheFor(MEDIA_KEY).get(300)?.state).toBe('ready');
  });

  it('판독이 실패하면 failed 이고, 다음 ensureHeads 는 그 캐시를 지우고 한 번 더 읽는다', async () => {
    const { ensureHeads, headCacheFor } = await load();
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    let answer = 403;
    const spy = stubFetch((_url, init) => {
      if (answer !== 206) return new Response(null, { status: answer });
      const range = new Headers(init?.headers).get('Range');
      return new Response(range === 'bytes=0-65535' ? segment1v6a.slice(0, 65_536) : null, {
        status: 206,
      });
    });
    await ensureHeads(MEDIA_KEY, [{ seq: 300, url: SIGNED }], []);
    expect(headCacheFor(MEDIA_KEY).get(300)).toEqual({ state: 'failed' });
    answer = 206;

    await ensureHeads(MEDIA_KEY, [{ seq: 300, url: SIGNED }], []);

    expect(spy).toHaveBeenCalledTimes(2);
    expect(headCacheFor(MEDIA_KEY).get(300)?.state).toBe('ready');
  });

  it('영상 트랙이 없거나 상자를 못 읽으면 failed 다', async () => {
    const { ensureHeads, headCacheFor } = await load();
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    serve(file([AUDIO], moof(traf(2, 0, [{ duration: 1024 }]))));

    await ensureHeads(MEDIA_KEY, [{ seq: 7, url: SIGNED }], []);

    expect(headCacheFor(MEDIA_KEY).get(7)).toEqual({ state: 'failed' });
  });

  it('실패 경고에 서명 URL 을 싣지 않는다', async () => {
    const { ensureHeads } = await load();
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    stubFetch(() => new Response(null, { status: 403 }));

    await ensureHeads(MEDIA_KEY, [{ seq: 300, url: SIGNED }], []);

    expect(warn).toHaveBeenCalled();
    expect(JSON.stringify(warn.mock.calls)).not.toMatch(/seg\.example|Signature|secret-sig/);
  });

  it('끊은 판독(signal)은 캐시에 남기지 않아 다음에 다시 읽는다', async () => {
    const { ensureHeads, headCacheFor } = await load();
    const controller = new AbortController();
    stubFetch(() => {
      controller.abort();
      throw new DOMException('aborted', 'AbortError');
    });

    await ensureHeads(MEDIA_KEY, [{ seq: 300, url: SIGNED }], [], controller.signal);

    expect(headCacheFor(MEDIA_KEY).get(300)).toBeUndefined();
  });

  it('영상 키마다 캐시가 따로다(seq 는 그 키의 장부 번호다)', async () => {
    const { ensureHeads, headCacheFor } = await load();
    serve(segment1v6a);

    await ensureHeads(MEDIA_KEY, [{ seq: 300, url: SIGNED }], []);

    expect(headCacheFor('key-2').get(300)).toBeUndefined();
  });

  it('값이 바뀔 때마다 구독자를 부르고, 끊은 구독자는 부르지 않는다', async () => {
    const { ensureHeads, headCacheFor } = await load();
    serve(segment1v6a);
    const listener = vi.fn();
    const stop = headCacheFor(MEDIA_KEY).subscribe(listener);
    await ensureHeads(MEDIA_KEY, [], [301]);
    const calls = listener.mock.calls.length;
    stop();

    await ensureHeads(MEDIA_KEY, [{ seq: 300, url: SIGNED }], []);

    expect(calls).toBeGreaterThan(0);
    expect(listener).toHaveBeenCalledTimes(calls);
  });

  it('여러 seq 는 동시에 여섯까지만 받는다', async () => {
    const { ensureHeads } = await load();
    let open = 0;
    let peak = 0;
    stubFetch(async () => {
      open += 1;
      peak = Math.max(peak, open);
      await new Promise((resolve) => setTimeout(resolve, 0));
      open -= 1;
      return new Response(segment1v6a.slice(0, 65_536), { status: 206 });
    });
    const rows = Array.from({ length: 10 }, (_, i) => ({ seq: 300 + i, url: SIGNED }));

    await ensureHeads(MEDIA_KEY, rows, []);

    expect(peak).toBe(6);
  });
});
