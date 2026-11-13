import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { stubFetch } from '@/test/mockFetch';

// ② 머리 판독 · seq 별 캐시(설계 prw2 3.2 결정 3 · 4절 · 계획 E4b-1 검증 묶음 B-1). ② 는 S3 서명 URL 이라 쿠키를 싣지 않고
// (credentials omit — 서명이 자격이라 실을 쿠키가 없다 · M4 실측 2026-11-13) Range 하나만 싣는다. 캐시가 모듈 상태라 시험마다
// 모듈을 새로 읽는다.

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

/** 한 ② 에서 받는 바이트 상한(fileHeadProbe HEAD_PROBE_MAX_BYTES — 계약 ② 개당 약 4MB 의 두 배) */
const BYTE_LIMIT = 8 * 1024 * 1024;

/** 영상 표본은 첫 moof 에 있고 소리 트랙은 끝까지 비어, 파일 끝을 봐야 머리가 끝나는 size 바이트 ② */
function emptyTrackFile(size: number): Uint8Array {
  const head = file([VIDEO, AUDIO], moof(traf(1, 9000, [{ duration: 3000 }])));
  return concat(head, box('mdat', new Uint8Array(size - head.length - 8)));
}

/** 끊은 뒤에도 스트림이 큐를 채우려 미리 당기는 몫(한 조각)까지 — 그 이상이면 상한에서 끊지 않은 것이다 */
const PULL_LIMIT = BYTE_LIMIT + 2 * 65_536;

/** bytes 를 64KB 씩 흘려 주는 200 응답 — pulled() 는 지금까지 꺼내 간 바이트 */
function streamed(bytes: Uint8Array) {
  let sent = 0;
  const body = new ReadableStream<Uint8Array>({
    pull(controller) {
      if (sent >= bytes.length) {
        controller.close();
        return;
      }
      const piece = bytes.slice(sent, sent + 65_536);
      sent += piece.length;
      controller.enqueue(piece);
    },
  });
  return { response: new Response(body, { status: 200 }), pulled: () => sent };
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

describe('ensureHeads: ② 머리를 64KB 부터 두 배씩 읽어 seq 캐시에 채운다', () => {
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

  it('첫 64KB 에서 샘플 있는 traf 를 못 찾은 트랙이 남으면 다음 128KB 를 받아 그 트랙까지 넣는다', async () => {
    const { ensureHeads, headCacheFor } = await load();
    const bytes = file(
      [VIDEO, AUDIO],
      moof(traf(1, 9000, [{ duration: 3000 }])),
      BIG_MDAT,
      moof(traf(2, 2400, [{ duration: 1024 }])),
    );
    const spy = serve(bytes);

    await ensureHeads(MEDIA_KEY, [{ seq: 7, url: SIGNED }], []);

    expect(rangesOf(spy)).toEqual(['bytes=0-65535', 'bytes=65536-196607']);
    expect(headCacheFor(MEDIA_KEY).get(7)).toEqual({
      state: 'ready',
      head: { hd: 0.05, hp: 0.05, videoDts: 0.1 },
    });
  });

  it('받은 본문이 이번에 요청한 크기보다 짧으면 파일 끝이라 더 받지 않는다', async () => {
    const { ensureHeads, headCacheFor } = await load();
    const spy = serve(emptyTrackFile(65_536 + 100_000));

    await ensureHeads(MEDIA_KEY, [{ seq: 7, url: SIGNED }], []);

    expect(rangesOf(spy)).toEqual(['bytes=0-65535', 'bytes=65536-196607']);
    expect(headCacheFor(MEDIA_KEY).get(7)).toEqual({
      state: 'ready',
      head: { hd: 0.1, hp: 0.1, videoDts: 0.1 },
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

describe('ensureHeads: ② 하나에서 받는 바이트에 상한을 둔다(보안 검수 prw2 E4b-1 M2)', () => {
  it('빈 트랙이 끝까지 이어지는 큰 ② 는 64KB 부터 두 배씩, 마지막은 상한까지 남은 만큼 받고 failed 다', async () => {
    const { ensureHeads, headCacheFor } = await load();
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    const spy = serve(emptyTrackFile(BYTE_LIMIT + 1_000_000));

    await ensureHeads(MEDIA_KEY, [{ seq: 7, url: SIGNED }], []);

    // 64 · 128 · 256 · 512 · 1024 · 2048 · 4096KB 를 받으면 8128KB — 마지막은 상한(8192KB)까지 남은 64KB
    expect(rangesOf(spy)).toEqual([
      'bytes=0-65535',
      'bytes=65536-196607',
      'bytes=196608-458751',
      'bytes=458752-983039',
      'bytes=983040-2031615',
      'bytes=2031616-4128767',
      'bytes=4128768-8323071',
      'bytes=8323072-8388607',
    ]);
    expect(headCacheFor(MEDIA_KEY).get(7)).toEqual({ state: 'failed' });
  });

  it('빈 트랙 ② 가 상한보다 1바이트 작으면 끝까지 읽어 ready, 1바이트 크면 failed 다', async () => {
    const { ensureHeads, headCacheFor } = await load();
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    const under = emptyTrackFile(BYTE_LIMIT - 1);
    const over = emptyTrackFile(BYTE_LIMIT + 1);
    stubFetch((url, init) => {
      const bytes = url.endsWith('under') ? under : over;
      const [, from, to] = /^bytes=(\d+)-(\d+)$/.exec(new Headers(init?.headers).get('Range')!)!;
      if (Number(from) >= bytes.length) return new Response(null, { status: 416 });
      return new Response(bytes.slice(Number(from), Number(to) + 1), { status: 206 });
    });

    await ensureHeads(
      MEDIA_KEY,
      [
        { seq: 1, url: `${SIGNED}&under` },
        { seq: 2, url: `${SIGNED}&over` },
      ],
      [],
    );

    expect(headCacheFor(MEDIA_KEY).get(1)).toEqual({
      state: 'ready',
      head: { hd: 0.1, hp: 0.1, videoDts: 0.1 },
    });
    expect(headCacheFor(MEDIA_KEY).get(2)).toEqual({ state: 'failed' });
  });

  it('서버가 Range 를 무시하고 상한보다 큰 200 본문을 주면 상한에서 끊고, 그 안에서 머리를 못 끝내면 failed 다', async () => {
    const { ensureHeads, headCacheFor } = await load();
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    const body = streamed(emptyTrackFile(2 * BYTE_LIMIT));
    stubFetch(() => body.response);

    await ensureHeads(MEDIA_KEY, [{ seq: 7, url: SIGNED }], []);

    expect(body.pulled()).toBeLessThanOrEqual(PULL_LIMIT);
    expect(headCacheFor(MEDIA_KEY).get(7)).toEqual({ state: 'failed' });
  });

  it('Range 가 망 오류라 전체를 받을 때도 상한에서 끊고 failed 다', async () => {
    const { ensureHeads, headCacheFor } = await load();
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    const body = streamed(emptyTrackFile(2 * BYTE_LIMIT));
    const spy = stubFetch((_url, init) => {
      if (new Headers(init?.headers).has('Range')) throw new TypeError('Failed to fetch');
      return body.response;
    });

    await ensureHeads(MEDIA_KEY, [{ seq: 7, url: SIGNED }], []);

    expect(rangesOf(spy)).toEqual(['bytes=0-65535', null]);
    expect(body.pulled()).toBeLessThanOrEqual(PULL_LIMIT);
    expect(headCacheFor(MEDIA_KEY).get(7)).toEqual({ state: 'failed' });
  });

  it('상한보다 큰 200 본문이라도 상한 안 앞부분에서 머리가 끝나면 ready 다', async () => {
    const { ensureHeads, headCacheFor } = await load();
    const bytes = concat(segment1v6a, box('mdat', new Uint8Array(BYTE_LIMIT)));
    stubFetch(() => new Response(bytes.slice(), { status: 200 }));

    await ensureHeads(MEDIA_KEY, [{ seq: 300, url: SIGNED }], []);

    expect(headCacheFor(MEDIA_KEY).get(300)).toEqual({
      state: 'ready',
      head: { hd: 0, hp: 0, videoDts: 8136 / 90000 },
    });
  });
});

describe('ensureHeads: 전송 오류(codex prw2 E4b-1 r1 지적 6 · 7)', () => {
  it('전송 오류 원문에 서명 URL 이 있어도 경고에는 사유만 싣는다', async () => {
    const { ensureHeads, headCacheFor } = await load();
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    stubFetch(() => {
      throw new TypeError(`Failed to parse URL from ${SIGNED}`);
    });

    await ensureHeads(MEDIA_KEY, [{ seq: 300, url: SIGNED }], []);

    expect(headCacheFor(MEDIA_KEY).get(300)).toEqual({ state: 'failed' });
    expect(warn).toHaveBeenCalled();
    expect(JSON.stringify(warn.mock.calls)).not.toMatch(/seg\.example|Signature|secret-sig/);
  });

  it('206 머리를 받은 뒤 본문 전송이 끊기면 Range 없이 한 번 전체를 받아 읽는다', async () => {
    const { ensureHeads, headCacheFor } = await load();
    const spy = stubFetch((_url, init) => {
      if (!new Headers(init?.headers).has('Range')) {
        return new Response(segment1v6a.slice(), { status: 200 });
      }
      const broken = new ReadableStream<Uint8Array>({
        pull(controller) {
          controller.error(new TypeError('network error'));
        },
      });
      return new Response(broken, { status: 206 });
    });

    await ensureHeads(MEDIA_KEY, [{ seq: 300, url: SIGNED }], []);

    expect(rangesOf(spy)).toEqual(['bytes=0-65535', null]);
    expect(headCacheFor(MEDIA_KEY).get(300)?.state).toBe('ready');
  });
});

// --- 트랙 수 · 표본 수 증폭(보안 검수 prw2 경화 M-1 · cc M): 판독 한 번이 트랙마다 표본 상한을 새로 쓰면 CPU 가 멈춘다 ---

/** 판독 한 번의 표본 상한(fmp4Boxes MAX_TRACK_SAMPLES — 창 상한 200초 × 최고 프레임률 240) */
const SAMPLE_LIMIT = 48_000;

/** 표본별 칸이 없는(stride 0) trun 하나의 traf — 수십 바이트로 표본 count 개 */
function bareTraf(trackId: number, count: number): Uint8Array {
  return box(
    'traf',
    fullBox('tfhd', 0, 0x020000, u32(trackId)),
    fullBox('tfdt', 0, 0, u32(0)),
    fullBox('trun', 0, 0, u32(count)),
  );
}

/** 한 ② 판독이 끝날 때까지 걸린 밀리초와 그 seq 의 캐시 값 */
async function timedProbe(bytes: Uint8Array) {
  const { ensureHeads, headCacheFor } = await load();
  vi.spyOn(console, 'warn').mockImplementation(() => {});
  serve(bytes);
  const started = performance.now();
  await ensureHeads(MEDIA_KEY, [{ seq: 7, url: SIGNED }], []);
  return { ms: performance.now() - started, entry: headCacheFor(MEDIA_KEY).get(7) };
}

describe('ensureHeads: 트랙 수로 판독이 부풀지 않는다(보안 검수 prw2 경화 M-1)', () => {
  it('표본 상한만큼 든 영상 트랙 455개는 곧바로 failed 다(트랙마다 상한을 새로 쓰지 않는다)', async () => {
    const ids = Array.from({ length: 455 }, (_, i) => i + 1);
    const bytes = file(
      ids.map((id) => trak(id, 'vide', 90000)),
      moof(...ids.map((id) => bareTraf(id, SAMPLE_LIMIT))),
    );

    const { ms, entry } = await timedProbe(bytes);

    expect(entry).toEqual({ state: 'failed' });
    expect(ms).toBeLessThan(200);
  });

  it('같은 track_ID 의 trak 1만 개는 곧바로 failed 다', async () => {
    const bytes = file(
      Array.from({ length: 10_000 }, () => trak(1, 'vide', 90000)),
      moof(bareTraf(1, SAMPLE_LIMIT)),
    );

    const { ms, entry } = await timedProbe(bytes);

    expect(entry).toEqual({ state: 'failed' });
    expect(ms).toBeLessThan(200);
  });

  it('같은 track_ID 의 trak 이 둘이면 failed 다', async () => {
    const bytes = file([VIDEO, trak(1, 'soun', 48000)], moof(traf(1, 9000, [{ duration: 3000 }])));

    const { entry } = await timedProbe(bytes);

    expect(entry).toEqual({ state: 'failed' });
  });

  it('판독 한 번의 표본은 트랙 합으로 상한까지다: 두 트랙이 합쳐 넘으면 failed 다', async () => {
    const bytes = file(
      [VIDEO, AUDIO],
      moof(bareTraf(1, SAMPLE_LIMIT / 2), bareTraf(2, SAMPLE_LIMIT / 2 + 1)),
    );

    const { entry } = await timedProbe(bytes);

    expect(entry).toEqual({ state: 'failed' });
  });

  it('두 트랙이 합쳐 상한까지면 읽는다', async () => {
    const bytes = file(
      [VIDEO, AUDIO],
      moof(bareTraf(1, SAMPLE_LIMIT / 2), bareTraf(2, SAMPLE_LIMIT / 2)),
    );

    const { entry } = await timedProbe(bytes);

    expect(entry).toEqual({ state: 'ready', head: { hd: 0, hp: 0, videoDts: 0 } });
  });
});

// --- 재판독 비용(보안 검수 prw2 후속 경화 M-1 · kty 결정 (가)): 받을 때마다 받은 앞부분 전체를 다시 읽으므로 받는 횟수가 곧 비용이다 ---

/** piece 를 이어 붙여 size 바이트 이상으로 만든 바이트 */
function repeated(piece: Uint8Array, size: number): Uint8Array {
  const count = Math.ceil(size / piece.length);
  const out = new Uint8Array(count * piece.length);
  for (let i = 0; i < count; i += 1) out.set(piece, i * piece.length);
  return out;
}

/**
 * 16 트랙 ② — 표본 있는 traf(15 트랙 합 47,985 표본)를 상한의 한가운데(4MiB)에 두고, 그 앞뒤를 16번 트랙의 빈 traf 로
 * 채워 상한을 넘긴다. 4MiB 뒤로는 판독마다 표본을 모두 읽고도 16번 트랙 때문에 머리가 끝나지 않는다(보안 검수 P5 · P6 꼴)
 */
function sampledInMiddle(): Uint8Array {
  const ids = Array.from({ length: 16 }, (_, i) => i + 1);
  const head = file(ids.map((id) => trak(id, id === 1 ? 'vide' : 'soun', 48000)));
  const empty = moof(bareTraf(16, 0));
  const sampled = moof(...ids.slice(0, 15).map((id) => bareTraf(id, 3199)));
  const before = repeated(empty, BYTE_LIMIT / 2 - head.length);
  return concat(head, before, sampled, repeated(empty, BYTE_LIMIT / 2 + 100_000));
}

describe('ensureHeads: 재판독 횟수가 받는 크기를 두 배씩 키운 만큼으로 준다(보안 검수 prw2 후속 경화 M-1 · kty (가))', () => {
  // 판별은 받은 횟수가 한다 — 벽시계 단언은 부하 때 흔들려 두지 않는다
  it('표본 traf 를 4MiB 에 두고 빈 traf 로 상한을 넘긴 16 트랙 ② 는 여덟 번 받고 failed 다', async () => {
    const { ensureHeads, headCacheFor } = await load();
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    const spy = serve(sampledInMiddle());

    await ensureHeads(MEDIA_KEY, [{ seq: 7, url: SIGNED }], []);

    expect(headCacheFor(MEDIA_KEY).get(7)).toEqual({ state: 'failed' });
    expect(spy).toHaveBeenCalledTimes(8);
  }, 120_000);
});

// --- 본문 받기(codex prw2 경화 r1 지적 1 · 2 · 3) ---

/**
 * bytes 를 size 바이트 쪼각으로 흘려 주는 200 응답. 다음 쪼각을 내기 전에 앞 쪼각의 바이트를 0 으로 지운다 — 미리 당기지
 * 않으므로(highWaterMark 0) 다음 쪼각은 읽는 쪽이 앞 쪼각을 다 쓰고 다시 read 할 때 나간다
 */
function reusedChunks(bytes: Uint8Array, size: number): Response {
  let sent = 0;
  let previous: Uint8Array | null = null;
  const body = new ReadableStream<Uint8Array>(
    {
      pull(controller) {
        previous?.fill(0);
        if (sent >= bytes.length) {
          controller.close();
          return;
        }
        previous = bytes.slice(sent, sent + size);
        sent += previous.length;
        controller.enqueue(previous);
      },
    },
    { highWaterMark: 0 },
  );
  return new Response(body, { status: 200 });
}

/** 바꿔 끼우기 전의 Uint8Array — 대역을 전역에 끼운 동안에도 진짜 생성자를 쓰려고 둔다 */
const NATIVE_BYTES = Uint8Array;

/**
 * 새 바이트 저장소를 잡는 횟수를 세는 Uint8Array 대역. 길이 · 배열에서 만들 때만 센다(ArrayBuffer 위의 보기 — subarray —
 * 는 저장소를 잡지 않는다). chunkOf 로 만든 쪼각은 이 대역을 constructor 로 가져 그 slice 도 대역을 거친다(species)
 */
function countingBytes() {
  let allocations = 0;
  const type = new Proxy(NATIVE_BYTES, {
    construct(target, args, newTarget) {
      if (!(args[0] instanceof ArrayBuffer)) allocations += 1;
      return Reflect.construct(target, args, newTarget);
    },
  });
  const proto: object = Object.create(NATIVE_BYTES.prototype, { constructor: { value: type } });
  return {
    type,
    chunkOf: (bytes: Uint8Array): Uint8Array => Object.setPrototypeOf(bytes, proto),
    allocations: () => allocations,
  };
}

describe('ensureHeads: 본문 쪼각을 들고 있지 않는다(codex prw2 경화 r1 지적 1)', () => {
  it('1바이트 쪼각마다 복사본을 만들지 않는다: 바이트 저장소는 판독 버퍼를 두 배씩 늘린 횟수만큼만 새로 잡는다', async () => {
    const { ensureHeads, headCacheFor } = await load();
    const counting = countingBytes();
    let sent = 0;
    const body = new ReadableStream<Uint8Array>({
      pull(controller) {
        if (sent >= segment1v6a.length) {
          controller.close();
          return;
        }
        controller.enqueue(counting.chunkOf(segment1v6a.slice(sent, sent + 1)));
        sent += 1;
      },
    });
    stubFetch(() => new Response(body, { status: 200 }));
    vi.stubGlobal('Uint8Array', counting.type);

    await ensureHeads(MEDIA_KEY, [{ seq: 300, url: SIGNED }], []);

    vi.stubGlobal('Uint8Array', NATIVE_BYTES);
    // 실물 366,425바이트 — 두 배씩 늘리면 log2 ≈ 19번이다. 쪼각마다 복사하면 36만 번을 넘는다
    expect(counting.allocations()).toBeLessThan(40);
    expect(headCacheFor(MEDIA_KEY).get(300)?.state).toBe('ready');
  });

  it('받은 쪼각은 곧바로 판독 버퍼로 옮긴다: 다음 쪼각 전에 앞 쪼각 바이트가 지워져도 실물 ② 를 그대로 읽는다', async () => {
    const { ensureHeads, headCacheFor } = await load();
    stubFetch(() => reusedChunks(segment1v6a, 1));

    await ensureHeads(MEDIA_KEY, [{ seq: 300, url: SIGNED }], []);

    expect(headCacheFor(MEDIA_KEY).get(300)).toEqual({
      state: 'ready',
      head: { hd: 0, hp: 0, videoDts: 8136 / 90000 },
    });
  });
});

describe('ensureHeads: 상한에서 끊은 본문은 스트림을 닫는다(cc · codex prw2 경화 r1 L)', () => {
  it('상한보다 큰 200 본문을 끊으면 스트림의 cancel 이 불린다', async () => {
    const { ensureHeads } = await load();
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    const bytes = emptyTrackFile(2 * BYTE_LIMIT);
    const cancel = vi.fn();
    let sent = 0;
    const body = new ReadableStream<Uint8Array>({
      pull(controller) {
        const piece = bytes.slice(sent, sent + 65_536);
        sent += piece.length;
        controller.enqueue(piece);
      },
      cancel,
    });
    stubFetch(() => new Response(body, { status: 200 }));

    await ensureHeads(MEDIA_KEY, [{ seq: 7, url: SIGNED }], []);

    expect(cancel).toHaveBeenCalledTimes(1);
  });
});

describe('ensureHeads: 정확히 상한인 ② 는 받는 길과 무관하게 같은 결과다(codex prw2 경화 r1 지적 2)', () => {
  it('끝까지 빈 트랙이 있는 정확히 상한 크기 ② 는 Range 로 받든 200 으로 받든 failed 다', async () => {
    const { ensureHeads, headCacheFor } = await load();
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    const bytes = emptyTrackFile(BYTE_LIMIT);
    stubFetch((url, init) => {
      const range = new Headers(init?.headers).get('Range');
      if (url.endsWith('whole') || range === null)
        return new Response(bytes.slice(), { status: 200 });
      const [, from, to] = /^bytes=(\d+)-(\d+)$/.exec(range)!;
      if (Number(from) >= bytes.length) return new Response(null, { status: 416 });
      return new Response(bytes.slice(Number(from), Number(to) + 1), { status: 206 });
    });

    await ensureHeads(
      MEDIA_KEY,
      [
        { seq: 1, url: `${SIGNED}&range` },
        { seq: 2, url: `${SIGNED}&whole` },
      ],
      [],
    );

    expect(headCacheFor(MEDIA_KEY).get(1)).toEqual({ state: 'failed' });
    expect(headCacheFor(MEDIA_KEY).get(2)).toEqual({ state: 'failed' });
  });
});

// --- 겹친 판독 · 계정 경계 · 받기 시한(PR 단계 장부 78 · 79 · 86 · 94) ---

/** 시험이 풀 때까지 걸려 있는 206 답 — release 하면 실물 ② 첫 64KB 를 준다 */
function heldRange() {
  const releases: (() => void)[] = [];
  const spy = stubFetch(
    () =>
      new Promise<Response>((resolve) => {
        releases.push(() => resolve(new Response(segment1v6a.slice(0, 65_536), { status: 206 })));
      }),
  );
  return { spy, releaseAll: () => releases.splice(0).forEach((release) => release()) };
}

const rowsOf = (seqs: number[]) => seqs.map((seq) => ({ seq, url: `${SIGNED}&seq=${seq}` }));

describe('ensureHeads: 겹쳐 부르면 같은 seq 를 두 번 받지 않고, 동시 받기는 모듈 전체로 여섯까지다', () => {
  it('읽는 중인 seq 는 그 판독을 기다리고, 다 읽힌 뒤에 끝난다', async () => {
    const { ensureHeads, headCacheFor } = await load();
    const { spy, releaseAll } = heldRange();
    const first = ensureHeads(MEDIA_KEY, rowsOf([300]), []);
    let secondDone = false;
    const second = ensureHeads(MEDIA_KEY, rowsOf([300]), []).then(() => {
      secondDone = true;
    });

    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(secondDone).toBe(false);
    releaseAll();
    await second;

    expect(headCacheFor(MEDIA_KEY).get(300)?.state).toBe('ready');
    expect(spy).toHaveBeenCalledTimes(1);
    await first;
  });

  it('앞 호출의 줄에 든(아직 받기 전인) seq 도 다시 받지 않는다', async () => {
    const { ensureHeads } = await load();
    const spy = stubFetch(async () => {
      await new Promise((resolve) => setTimeout(resolve, 0));
      return new Response(segment1v6a.slice(0, 65_536), { status: 206 });
    });
    const seqs = Array.from({ length: 10 }, (_, i) => 300 + i);

    await Promise.all([
      ensureHeads(MEDIA_KEY, rowsOf(seqs), []),
      ensureHeads(MEDIA_KEY, rowsOf(seqs), []),
    ]);

    expect(spy).toHaveBeenCalledTimes(10);
  });

  it('기다리던 판독이 그 판독을 세운 쪽의 멈춤으로 끝나면, 기다리던 쪽이 다시 받아 채운다', async () => {
    const { ensureHeads, headCacheFor } = await load();
    let calls = 0;
    const spy = stubFetch((_url, init) => {
      calls += 1;
      if (calls === 1) return neverAnswers(init);
      return new Response(segment1v6a.slice(0, 65_536), { status: 206 });
    });
    const owner = new AbortController();
    const first = ensureHeads(MEDIA_KEY, rowsOf([300]), [], owner.signal);
    const waiter = ensureHeads(MEDIA_KEY, rowsOf([300]), []);

    owner.abort();
    await Promise.all([first, waiter]);

    expect(spy).toHaveBeenCalledTimes(2);
    expect(headCacheFor(MEDIA_KEY).get(300)?.state).toBe('ready');
  });

  it('호출 · 영상 키가 달라도 동시에 받는 수는 모두 합쳐 여섯까지다', async () => {
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
    const seqs = Array.from({ length: 10 }, (_, i) => 300 + i);

    await Promise.all([
      ensureHeads(MEDIA_KEY, rowsOf(seqs), []),
      ensureHeads('key-2', rowsOf(seqs), []),
    ]);

    expect(peak).toBe(6);
  });
});

describe('ensureHeads: 계정이 바뀌면 머리 캐시를 비운다(보안 검수 prw2 E4b-1 L2)', () => {
  it('부른 쪽이 넘긴 세션이 이미 지났으면(주소를 묻는 사이 계정 바뀜) 받지도 적지도 않는다', async () => {
    const { ensureHeads, headCacheFor } = await load();
    const spy = serve(segment1v6a);

    await ensureHeads(MEDIA_KEY, rowsOf([300]), [301], undefined, () => false);

    expect(spy).not.toHaveBeenCalled();
    expect(headCacheFor(MEDIA_KEY).get(300)).toBeUndefined();
    expect(headCacheFor(MEDIA_KEY).get(301)).toBeUndefined();
  });

  it('② 받기도 3xx 를 따라가지 않는다(redirect error) — 넘어가면 failed 다', async () => {
    const { ensureHeads, headCacheFor } = await load();
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    const spy = stubFetch((_url, init) => {
      if (init?.redirect === 'error') throw new TypeError('Failed to fetch');
      return new Response(segment1v6a.slice(0, 65_536), { status: 206 });
    });

    await ensureHeads(MEDIA_KEY, rowsOf([300]), []);

    expect(headCacheFor(MEDIA_KEY).get(300)).toEqual({ state: 'failed' });
    expect(spy.mock.calls.every(([, init]) => init?.redirect === 'error')).toBe(true);
  });

  it('읽은 머리를 비우고, 들고 있던 캐시의 구독자도 그 비움을 듣는다', async () => {
    const { ensureHeads, headCacheFor } = await load();
    const { useAuthStore } = await import('@/stores/auth');
    serve(segment1v6a);
    await ensureHeads(MEDIA_KEY, rowsOf([300]), [301]);
    const heads = headCacheFor(MEDIA_KEY);
    const listener = vi.fn();
    heads.subscribe(listener);

    useAuthStore.getState().clearTokens();

    expect(heads.get(300)).toBeUndefined();
    expect(heads.get(301)).toBeUndefined();
    expect(listener).toHaveBeenCalled();
  });

  it('바뀌기 전에 시작한 판독은 끝나도 캐시에 넣지 않고, 같은 seq 를 새로 부르면 다시 받는다', async () => {
    const { ensureHeads, headCacheFor } = await load();
    const { useAuthStore } = await import('@/stores/auth');
    const { spy, releaseAll } = heldRange();
    const before = ensureHeads(MEDIA_KEY, rowsOf([300]), []);
    await new Promise((resolve) => setTimeout(resolve, 0));

    useAuthStore.getState().clearTokens();
    const after = ensureHeads(MEDIA_KEY, rowsOf([300]), []);
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(spy).toHaveBeenCalledTimes(2);
    releaseAll();
    await Promise.all([before, after]);

    expect(headCacheFor(MEDIA_KEY).get(300)?.state).toBe('ready');
  });

  it('바뀌기 전에 시작한 판독이 바뀐 뒤 끝나면 캐시는 비어 있다', async () => {
    const { ensureHeads, headCacheFor } = await load();
    const { useAuthStore } = await import('@/stores/auth');
    const { releaseAll } = heldRange();
    const before = ensureHeads(MEDIA_KEY, rowsOf([300]), []);
    await new Promise((resolve) => setTimeout(resolve, 0));

    useAuthStore.getState().clearTokens();
    releaseAll();
    await before;

    expect(headCacheFor(MEDIA_KEY).get(300)).toBeUndefined();
  });
});

/** signal 이 멈추면 거절하는(실제 fetch 처럼) 끝나지 않는 답 */
function neverAnswers(init?: RequestInit): Promise<Response> {
  return new Promise((_resolve, reject) => {
    init?.signal?.addEventListener('abort', () =>
      reject(new DOMException('aborted', 'AbortError')),
    );
  });
}

/** 첫 쪼각을 준 뒤 멈추는 본문 — 끊어도 read 가 풀리지 않는다(시한은 읽는 쪽이 지켜야 한다) */
function stalledBody(first: Uint8Array) {
  const cancel = vi.fn();
  let sent = false;
  const body = new ReadableStream<Uint8Array>(
    {
      pull(controller) {
        if (sent) return new Promise<void>(() => undefined);
        sent = true;
        controller.enqueue(first);
        return undefined;
      },
      cancel,
    },
    { highWaterMark: 0 },
  );
  return { body, cancel };
}

describe('ensureHeads: 받기가 멈추면 시한에서 끊는다(보안 검수 prw2 경화 L-3)', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it('응답이 오지 않으면 시한에서 끊고 failed 다 — Range 없는 전체 받기로 물러나지 않는다', async () => {
    vi.useFakeTimers();
    const { ensureHeads, headCacheFor, HEAD_FETCH_STALL_MS } = await load();
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    const spy = stubFetch((_url, init) => neverAnswers(init));

    const done = ensureHeads(MEDIA_KEY, rowsOf([300]), []);
    await vi.advanceTimersByTimeAsync(HEAD_FETCH_STALL_MS);
    await done;

    expect(headCacheFor(MEDIA_KEY).get(300)).toEqual({ state: 'failed' });
    expect(spy).toHaveBeenCalledTimes(1);
  });

  it('206 본문이 도중에 멈추면 시한에서 끊고 본문을 닫는다', async () => {
    vi.useFakeTimers();
    const { ensureHeads, headCacheFor, HEAD_FETCH_STALL_MS } = await load();
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    const stalled = stalledBody(segment1v6a.slice(0, 1_000));
    const spy = stubFetch(() => new Response(stalled.body, { status: 206 }));

    const done = ensureHeads(MEDIA_KEY, rowsOf([300]), []);
    await vi.advanceTimersByTimeAsync(HEAD_FETCH_STALL_MS);
    await done;

    expect(headCacheFor(MEDIA_KEY).get(300)).toEqual({ state: 'failed' });
    expect(spy).toHaveBeenCalledTimes(1);
    expect(stalled.cancel).toHaveBeenCalled();
  });

  it('쪼각이 시한 안에 이어 오면 오래 걸려도 끊지 않는다(멈춤 시한이지 전체 시한이 아니다)', async () => {
    vi.useFakeTimers();
    const { ensureHeads, headCacheFor, HEAD_FETCH_STALL_MS } = await load();
    const step = Math.floor(HEAD_FETCH_STALL_MS * 0.6);
    let sent = 0;
    const slow = new ReadableStream<Uint8Array>(
      {
        async pull(controller) {
          if (sent >= 65_536) {
            controller.close();
            return;
          }
          await new Promise((resolve) => setTimeout(resolve, step));
          controller.enqueue(segment1v6a.slice(sent, sent + 16_384));
          sent += 16_384;
        },
      },
      { highWaterMark: 0 },
    );
    stubFetch(() => new Response(slow, { status: 206 }));

    const done = ensureHeads(MEDIA_KEY, rowsOf([300]), []);
    await vi.advanceTimersByTimeAsync(step * 5);
    await done;

    expect(headCacheFor(MEDIA_KEY).get(300)?.state).toBe('ready');
  });

  it('③ 조각 판독도 같은 시한에서 끊고 던진다', async () => {
    vi.useFakeTimers();
    const { readFragmentVideoDts, HEAD_FETCH_STALL_MS } = await load();
    stubFetch((_url, init) => neverAnswers(init));

    const read = readFragmentVideoDts({
      uri: 'https://cdn/seg/1.m4s',
      mapUri: 'https://cdn/init.mp4',
    });
    const caught = read.catch((e: unknown) => e);
    await vi.advanceTimersByTimeAsync(HEAD_FETCH_STALL_MS);

    expect(await caught).toBeInstanceOf(Error);
  });
});
