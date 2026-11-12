import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { EditorSourceState } from './source/editorSourceState';
import type { EditorPlaylist, PlaylistSegment } from './source/editorPlaylist';
import type { HeadEntry } from './source/fileHeadProbe';
import type { RemuxedWindow } from './source/remuxWindow';
import type { SourceList } from './source/useEditorSource';

// 편집기 미리보기 = ③ 되감기 목록의 창(POK-473 계획 prw2 5절 E4b-2 · 설계 5절 4). 구간 앞뒤 여유를 런 하나 안 조각으로 받아
// mediabunny 로 다시 싸고(remuxWindow — 여기서는 조각 seq 로 표를 짓는 가짜), 창 배치(placeWindow — 실물)로 Blob 시각 ↔ 편집기
// 초를 바꾼다. 녹화 재생 서버(/playback)는 부르지 않는다.
//
// 가짜 목록 · 표: seq 100 부터 4초 조각, PDT = BASE + (seq − 100)·4초, ③ 영상 첫 DTS = (seq − 100)·4초(판 2 원점 = D3).
// 그래서 편집기 초 S 와 ③ PTS 가 같고, 창 Blob 시각 t 는 S − (창 첫 조각 시작)이다.

const remux = vi.hoisted(() => {
  const first = 100;
  /** 조각 seq 들의 가짜 재다중화 결과 — 조각마다 0.5초 프레임 여덟 */
  const of = (seqs: readonly number[]): RemuxedWindow => {
    const segments = seqs.map((seq) => {
      const dts = (seq - first) * 4;
      const frames = Array.from({ length: 8 }, (_, i) => ({ pts: dts + i * 0.5, dur: 0.5 }));
      return { seq, videoDts: dts, frames, mediaStart: dts, mediaEnd: dts + 4 };
    });
    return { blob: new Blob(['mp4']), shiftSeconds: segments[0]!.videoDts, segments };
  };
  return { first, of };
});
vi.mock('./source/remuxWindow', () => ({
  remuxWindow: vi.fn(async (bytes: { segments: { seq: number }[] }) =>
    remux.of(bytes.segments.map((seg) => seg.seq)),
  ),
}));

/** 가짜 ② 머리 캐시 — 시험이 값을 넣고 알린다 */
const heads = vi.hoisted(() => {
  const scopes = new Map<string, { entries: Map<number, HeadEntry>; listeners: Set<() => void> }>();
  const scopeOf = (key: string) => {
    let scope = scopes.get(key);
    if (scope === undefined) {
      scope = { entries: new Map(), listeners: new Set() };
      scopes.set(key, scope);
    }
    return scope;
  };
  return {
    cacheFor: (key: string) => {
      const scope = scopeOf(key);
      return {
        get: (seq: number) => scope.entries.get(seq),
        subscribe: (listener: () => void) => {
          scope.listeners.add(listener);
          return () => scope.listeners.delete(listener);
        },
      };
    },
    set: (key: string, seq: number, entry: HeadEntry) => {
      const scope = scopeOf(key);
      scope.entries.set(seq, entry);
      for (const listener of [...scope.listeners]) listener();
    },
    clear: () => scopes.clear(),
    ensure: vi.fn(async () => undefined),
  };
});
vi.mock('./source/fileHeadProbe', () => ({
  headCacheFor: (key: string) => heads.cacheFor(key),
  ensureHeads: heads.ensure,
}));
vi.mock('@/api/playbackAccess', () => ({
  ensureMediaAccess: vi.fn(() => null),
  reportPlaybackForbidden: vi.fn(),
}));
vi.mock('@/api/clipEditor', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/clipEditor')>()),
  requestSegmentAudioAccess: vi.fn(),
}));

const { requestSegmentAudioAccess } = await import('@/api/clipEditor');
const { remuxWindow } = await import('./source/remuxWindow');
const { clearEditorMedia, editorWindowKey, windowBlobs } = await import('./editorMedia');
const { decideWindow } = await import('./source/editorWindow');
const { useEditorVideoPlayback } = await import('./useEditorVideoPlayback');

const BASE = Date.parse('2026-11-12T01:00:00.000Z');
const DIR = 'https://media.example/vod/key-1/round-a';
const LIST_URL = `${DIR}/index.m3u8`;
const MEDIA_KEY = 'key-1';
const READY: EditorSourceState = { kind: 'ready', mode: 'vod', url: LIST_URL };
const LIVE_READY: EditorSourceState = { kind: 'ready', mode: 'live', url: LIST_URL };

function segment(seq: number, timing: 'v2' | null = 'v2'): PlaylistSegment {
  return {
    seq,
    uri: `${DIR}/seg/${String(seq).padStart(7, '0')}.m4s`,
    mapUri: `${DIR}/init/i.mp4`,
    pdtMs: BASE + (seq - remux.first) * 4_000,
    durationMs: 4_000,
    timing,
    gap: false,
  };
}

const range = (from: number, to: number) =>
  Array.from({ length: to - from + 1 }, (_, i) => from + i);

/** 런마다 seq 목록 — 런 사이는 GAP · 끊김으로 갈린 자리다 */
function listOf(
  runs: number[][],
  options: { mode?: 'live' | 'vod'; timing?: 'v2' | null } = {},
): SourceList {
  const timing = options.timing === undefined ? 'v2' : options.timing;
  const segs = runs.map((run) => run.map((seq) => segment(seq, timing)));
  return {
    mode: options.mode ?? 'vod',
    url: LIST_URL,
    playlist: { runs: segs, rows: segs.flat() },
  };
}

/** 받은 조각 seq — init(MAP) 은 뺀다 */
function fetchedSeqs(spy: ReturnType<typeof vi.fn>): number[] {
  return spy.mock.calls
    .map(([url]) => /\/seg\/(\d+)\.m4s$/.exec(String(url))?.[1])
    .filter((seq): seq is string => seq !== undefined)
    .map(Number);
}

interface MountOptions {
  list?: SourceList | null;
  state?: EditorSourceState;
  listFailed?: boolean;
  initialRange?: { startSeconds: number; endSeconds: number };
  mediaKey?: string;
}

function mount(options: MountOptions = {}) {
  const source = {
    state: options.state ?? READY,
    list: options.list === undefined ? listOf([range(100, 124)]) : options.list,
    listFailed: options.listFailed ?? false,
    reloadList: vi.fn(async (): Promise<EditorPlaylist | null> => null),
    reportSegmentFailure: vi.fn(async () => undefined),
  };
  const initialProps = {
    streamId: 'round-a',
    mediaKey: options.mediaKey ?? MEDIA_KEY,
    base: BASE,
    source,
    initialRange: options.initialRange ?? { startSeconds: 20, endSeconds: 30 },
  };
  const hook = renderHook((props: typeof initialProps) => useEditorVideoPlayback(props), {
    initialProps,
  });
  /** 원천만 바꿔 다시 그린다(목록이 자람 · 국면이 바뀜) */
  const update = (next: Partial<typeof source>) => {
    Object.assign(source, next);
    hook.rerender({ ...initialProps, source: { ...source } });
  };
  return { ...hook, source, update };
}

function videoNode(extra: Record<string, unknown> = {}) {
  return Object.assign(new EventTarget(), {
    currentTime: 0,
    duration: 32,
    paused: true,
    readyState: 0,
    playbackRate: 1,
    play: vi.fn(() => Promise.resolve()),
    pause: vi.fn(),
    removeAttribute: vi.fn(),
    load: vi.fn(),
    ...extra,
  }) as unknown as HTMLVideoElement & { paused: boolean };
}

const flush = () =>
  act(async () => {
    for (let i = 0; i < 10; i += 1) await vi.advanceTimersByTimeAsync(0);
  });

let fetchSpy: ReturnType<typeof vi.fn>;
let made = 0;
const revoke = vi.fn();

beforeEach(() => {
  vi.useFakeTimers();
  vi.clearAllMocks();
  clearEditorMedia();
  heads.clear();
  made = 0;
  fetchSpy = vi.fn(async () => new Response(new Uint8Array([1]), { status: 200 }));
  vi.stubGlobal('fetch', fetchSpy);
  vi.stubGlobal(
    'URL',
    Object.assign(URL, { createObjectURL: () => `blob:${++made}`, revokeObjectURL: revoke }),
  );
  vi.mocked(requestSegmentAudioAccess).mockResolvedValue({ expiresAt: '', segments: [] });
});
afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

describe('useEditorVideoPlayback — 되감기 목록 창', () => {
  it('구간 앞뒤 여유 10초를 덮는 조각을 받고 녹화 재생 서버(/playback)는 부르지 않는다', async () => {
    const { result } = mount();
    await flush();

    // 구간 20–30초 ± 10초 = 10–40초 → 조각 102(8–12초) … 109(36–40초)
    expect(fetchedSeqs(fetchSpy)).toEqual(range(102, 109));
    expect(fetchSpy.mock.calls.filter(([url]) => String(url).includes('/playback/'))).toEqual([]);
    expect(result.current.window).toEqual({ startSeconds: 8, endSeconds: 40 });
    expect(result.current.src).toBe('blob:1');
  });

  it('창은 런 하나 안에 둔다: 구간 앞 여유가 끊긴 자리에 걸리면 런 시작부터 받는다', async () => {
    // 런 100–103(0–16초) · 런 106–124(24–100초). 구간 26–30초의 앞 여유 16–26초는 틈에 걸린다
    mount({
      list: listOf([range(100, 103), range(106, 124)]),
      initialRange: { startSeconds: 26, endSeconds: 30 },
    });
    await flush();
    expect(fetchedSeqs(fetchSpy)).toEqual(range(106, 109));
  });

  it('구간이 통째로 틈 안이면 받지 않고 「녹화 없음」으로 알린다', async () => {
    const { result } = mount({
      list: listOf([range(100, 103), range(125, 128)]),
      initialRange: { startSeconds: 50, endSeconds: 60 },
    });
    await flush();
    expect(fetchSpy).not.toHaveBeenCalled();
    expect(result.current.playback.error).toBe('no-recording');
  });

  it('목록이 미끄러져 창 앞이 목록 머리 앞이 되면 지금 목록으로 창을 다시 정해 머리부터 받는다', async () => {
    const { result, update } = mount();
    await flush();
    fetchSpy.mockClear();
    // 방송 중 1시간 창이 앞을 밀어내 목록 머리가 106(24초)이 됐다. 그 뒤 구간 끝을 창 끝 너머로 늘린다 — 앞 창(8초부터)을
    // 그대로 늘리면 목록 머리 앞 조각을 달라고 하게 된다
    update({ list: listOf([range(106, 124)]) });
    await act(async () => {
      result.current.playback.setBounds({ startSeconds: 20, endSeconds: 45, loop: true });
      await vi.advanceTimersByTimeAsync(1_000);
    });
    await flush();

    expect(fetchedSeqs(fetchSpy)).toEqual(range(106, 113));
    expect(result.current.playback.error).toBeNull();
  });

  it('decideWindow — 지금 목록 머리 앞의 구간은 머리부터의 창이 되고, 구간 전체가 머리 앞이면 빈 창이다', () => {
    const list = listOf([range(106, 124)]);
    const clamped = decideWindow(
      { startSeconds: 10, endSeconds: 30 },
      list,
      BASE,
      MEDIA_KEY,
      false,
    );
    expect(clamped.kind === 'window' && clamped.target.segs.map((s) => s.seq)).toEqual(
      range(106, 109),
    );
    expect(decideWindow({ startSeconds: 2, endSeconds: 12 }, list, BASE, MEDIA_KEY, false)).toEqual(
      {
        kind: 'empty',
      },
    );
  });
});

describe('useEditorVideoPlayback — 방송 중 목록 기다리기', () => {
  it('목록이 구간 끝 + 여유에 못 미치면 받지 않고 4초마다 목록을 다시 받아, 다 덮이면 받는다', async () => {
    // 목록 끝 28초 · 구간 끝 30초 + 여유 10초 = 40초
    const { source, update } = mount({
      state: LIVE_READY,
      list: listOf([range(100, 106)], { mode: 'live' }),
    });
    await flush();
    expect(fetchSpy).not.toHaveBeenCalled();

    await act(async () => {
      await vi.advanceTimersByTimeAsync(4_000);
    });
    expect(source.reloadList).toHaveBeenCalledTimes(1);
    update({ list: listOf([range(100, 110)], { mode: 'live' }) });
    await flush();

    expect(fetchedSeqs(fetchSpy)).toEqual(range(102, 109));
  });

  it('30초를 기다려도 목록이 자라지 않으면 있는 조각까지만 받는다', async () => {
    const { source } = mount({
      state: LIVE_READY,
      list: listOf([range(100, 106)], { mode: 'live' }),
    });
    for (let i = 0; i < 7; i += 1) {
      await act(async () => {
        await vi.advanceTimersByTimeAsync(4_000);
      });
    }
    await flush();
    expect(source.reloadList).toHaveBeenCalledTimes(7);
    expect(fetchedSeqs(fetchSpy)).toEqual(range(102, 106));

    await act(async () => {
      await vi.advanceTimersByTimeAsync(20_000);
    });
    expect(source.reloadList).toHaveBeenCalledTimes(7);
  });

  it('30초를 기다리다 포기한 창도, 뒤에 구간 끝을 창 끝 너머로 늘리면 목록을 다시 받아 자란 목록으로 창을 다시 정한다', async () => {
    // 목록 끝 28초에서 30초를 기다리다 포기해 8–28초 창을 받았다. 목록은 다시 받아야만 자란다
    const { result, source, update } = mount({
      state: LIVE_READY,
      list: listOf([range(100, 106)], { mode: 'live' }),
    });
    for (let i = 0; i < 7; i += 1) {
      await act(async () => {
        await vi.advanceTimersByTimeAsync(4_000);
      });
    }
    await flush();
    expect(result.current.window).toEqual({ startSeconds: 8, endSeconds: 28 });
    const grown = listOf([range(100, 115)], { mode: 'live' });
    source.reloadList.mockImplementation(async () => {
      update({ list: grown });
      return grown.playlist;
    });
    fetchSpy.mockClear();

    // 구간 끝 35초 + 여유 10초 = 45초 → 조각 102(8–12초) … 111(44–48초)
    await act(async () => {
      result.current.playback.setBounds({ startSeconds: 20, endSeconds: 35, loop: true });
      await vi.advanceTimersByTimeAsync(1_000);
    });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(4_000);
    });
    await flush();

    expect(source.reloadList).toHaveBeenCalledTimes(8);
    expect(fetchedSeqs(fetchSpy)).toEqual(range(102, 111));
    expect(result.current.window).toEqual({ startSeconds: 8, endSeconds: 48 });
  });

  it('끝난 방송(vod) 목록은 기다리지 않는다: 더 올라올 조각이 없다', async () => {
    const { source } = mount({ list: listOf([range(100, 106)]) });
    await flush();
    expect(fetchedSeqs(fetchSpy)).toEqual(range(102, 106));
    expect(source.reloadList).not.toHaveBeenCalled();
  });
});

describe('useEditorVideoPlayback — 원천 상태 문구(D3)', () => {
  it('목록을 받는 중이면 「받는 중」, 받다 실패하면 「받지 못함」으로 가른다', async () => {
    const loading = mount({ list: null });
    expect(loading.result.current.playback.error).toBe('list-loading');
    const failed = mount({ list: null, listFailed: true });
    expect(failed.result.current.playback.error).toBe('list-failed');
    await flush();
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it('방송 중 구간 시작이 목록 끝 뒤면 「몇 초 뒤에 보여요」(uploading)다', async () => {
    const { result } = mount({
      state: LIVE_READY,
      list: listOf([range(100, 106)], { mode: 'live' }),
      initialRange: { startSeconds: 40, endSeconds: 50 },
    });
    await flush();
    expect(result.current.playback.error).toBe('uploading');
  });

  it('uploading 이면 4초마다 목록을 다시 받고, 목록이 구간을 덮도록 자라면 창을 받는다', async () => {
    // 목록 끝 28초 · 구간 40–50초 — 카드 자리가 목록 끝 뒤다
    const { result, source, update } = mount({
      state: LIVE_READY,
      list: listOf([range(100, 106)], { mode: 'live' }),
      initialRange: { startSeconds: 40, endSeconds: 50 },
    });
    await flush();
    expect(result.current.playback.error).toBe('uploading');

    await act(async () => {
      await vi.advanceTimersByTimeAsync(4_000);
    });
    expect(source.reloadList).toHaveBeenCalledTimes(1);
    // 목록이 64초까지 자랐다 — 구간 40–50초 ± 10초 = 30–60초 → 조각 107(28–32초) … 114(56–60초)
    update({ list: listOf([range(100, 115)], { mode: 'live' }) });
    await flush();

    expect(result.current.playback.error).toBeNull();
    expect(fetchedSeqs(fetchSpy)).toEqual(range(107, 114));
  });

  it('uploading 이 풀리지 않으면 상한 없이 4초마다 다시 받는다: 60초면 15번', async () => {
    const { source } = mount({
      state: LIVE_READY,
      list: listOf([range(100, 106)], { mode: 'live' }),
      initialRange: { startSeconds: 40, endSeconds: 50 },
    });
    for (let i = 0; i < 15; i += 1) {
      await act(async () => {
        await vi.advanceTimersByTimeAsync(4_000);
      });
    }
    expect(source.reloadList).toHaveBeenCalledTimes(15);
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it('uploading 다시 받기는 원천이 finalizing 으로 바뀌거나 화면을 떠나면 멈춘다', async () => {
    const options = {
      state: LIVE_READY,
      list: listOf([range(100, 106)], { mode: 'live' }),
      initialRange: { startSeconds: 40, endSeconds: 50 },
    };
    const ending = mount(options);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(4_000);
    });
    ending.update({ state: { kind: 'finalizing', cause: 'stream' } });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(20_000);
    });
    expect(ending.result.current.playback.error).toBe('finalizing');
    expect(ending.source.reloadList).toHaveBeenCalledTimes(1);

    const leaving = mount(options);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(8_000);
    });
    leaving.unmount();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(20_000);
    });
    expect(leaving.source.reloadList).toHaveBeenCalledTimes(2);
  });

  it('방송 중 ending 이 되면 받은 창은 그대로 두고 새 창은 받지 않는다', async () => {
    const { result, update } = mount();
    await flush();
    expect(result.current.src).toBe('blob:1');
    fetchSpy.mockClear();

    update({ state: { kind: 'finalizing', cause: 'round', under: READY } });
    await act(async () => {
      result.current.playback.setBounds({ startSeconds: 20, endSeconds: 45, loop: true });
      await vi.advanceTimersByTimeAsync(1_000);
    });
    await flush();

    expect(result.current.playback.error).toBe('finalizing');
    expect(result.current.src).toBe('blob:1');
    expect(fetchSpy).not.toHaveBeenCalled();
  });
});

describe('useEditorVideoPlayback — 조각 실패', () => {
  it('조각이 404 면 그 조각과 창의 목록 주소로 원천에 싣고, 아직 받는 나머지 요청을 끊는다', async () => {
    const pending: AbortSignal[] = [];
    fetchSpy.mockImplementation(async (url: string, init?: RequestInit) => {
      if (url.endsWith('0000104.m4s')) return new Response(null, { status: 404 });
      if (url.endsWith('.m4s')) {
        pending.push(init!.signal!);
        return new Promise<Response>(() => undefined);
      }
      return new Response(new Uint8Array([1]), { status: 200 });
    });
    const { result, source } = mount();
    await flush();

    expect(source.reportSegmentFailure).toHaveBeenCalledWith('gone', 104, LIST_URL);
    expect(pending.length).toBeGreaterThan(0);
    expect(pending.every((signal) => signal.aborted)).toBe(true);
    expect(result.current.playback.error).not.toBeNull();
  });

  it('출입증을 다시 받아도 403 이면 「출입증 거절」로 알리고 원천에 싣는다', async () => {
    fetchSpy.mockImplementation(async (url: string) =>
      url.endsWith('0000103.m4s')
        ? new Response(null, { status: 403 })
        : new Response(new Uint8Array([1]), { status: 200 }),
    );
    const { result, source } = mount();
    await flush();
    expect(source.reportSegmentFailure).toHaveBeenCalledWith(
      'forbiddenAfterRefresh',
      103,
      LIST_URL,
    );
    expect(result.current.playback.error).toBe('access');
  });

  it('다시 싸기가 패킷 수 어긋남 등으로 실패하면 「불러오지 못함」이다', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {});
    vi.mocked(remuxWindow).mockRejectedValueOnce(new Error('재다중화 패킷 수가 다르다'));
    const { result, source } = mount();
    await flush();
    expect(result.current.playback.error).toBe('fatal');
    expect(source.reportSegmentFailure).not.toHaveBeenCalled();
  });
});

describe('useEditorVideoPlayback — ② 머리(판 NULL)', () => {
  it('판 NULL 창이면 창 범위의 소리 주소를 한 번 묻고, 창 seq 행만 머리 판독에 넘기고 없는 seq 는 missing 으로 넘긴다', async () => {
    vi.mocked(requestSegmentAudioAccess).mockResolvedValue({
      expiresAt: '',
      segments: [
        { seq: 101, startAtMs: 0, durationMs: 4_000, url: 'https://s3.example/101' },
        { seq: 102, startAtMs: 0, durationMs: 4_000, url: 'https://s3.example/102' },
        { seq: 103, startAtMs: 0, durationMs: 4_000, url: 'https://s3.example/103' },
      ],
    });
    mount({ list: listOf([range(100, 124)], { timing: null }) });
    await flush();

    expect(requestSegmentAudioAccess).toHaveBeenCalledTimes(1);
    expect(requestSegmentAudioAccess).toHaveBeenCalledWith('round-a', BASE + 8_000, BASE + 40_000);
    const [key, rows, missing, signal] = heads.ensure.mock.calls[0]! as unknown as [
      string,
      { seq: number; url: string }[],
      number[],
      AbortSignal,
    ];
    expect(key).toBe(MEDIA_KEY);
    expect(rows).toEqual([
      { seq: 102, url: 'https://s3.example/102' },
      { seq: 103, url: 'https://s3.example/103' },
    ]);
    expect(missing).toEqual(range(104, 109));
    expect(signal.aborted).toBe(false);
  });

  it('판 2 창은 머리를 읽지 않는다', async () => {
    mount();
    await flush();
    expect(requestSegmentAudioAccess).not.toHaveBeenCalled();
    expect(heads.ensure).not.toHaveBeenCalled();
  });

  it('창이 바뀌거나 화면을 떠나면 머리 판독을 끊는다', async () => {
    const { unmount } = mount({ list: listOf([range(100, 124)], { timing: null }) });
    await flush();
    const signal = (
      heads.ensure.mock.calls[0] as unknown as [string, unknown, unknown, AbortSignal]
    )[3];
    unmount();
    expect(signal.aborted).toBe(true);
  });

  it('머리가 늦게 오면 배치를 다시 셈하고, 멈춘 영상은 같은 편집기 초의 자리로 옮긴다', async () => {
    const key = 'key-late';
    const { result } = mount({ list: listOf([range(100, 124)], { timing: null }), mediaKey: key });
    const node = videoNode();
    act(() => result.current.videoRef(node));
    await flush();
    act(() => result.current.playback.seekTo(21));
    // 머리 전: 원점 = ③ 영상 첫 DTS(근사) — 21초 = 조각 105(20초) 안 1초 = Blob 13초
    expect(node.currentTime).toBeCloseTo(13);
    expect(result.current.placement!.sAtBlob(13)).toBeCloseTo(21);

    // 조각 105 의 ② 머리: 영상 첫 DTS 0.3 · 가장 이른 트랙 0 → m = 0.3 → 원점이 0.3초 앞으로
    act(() => heads.set(key, 105, { state: 'ready', head: { hd: 0, hp: 0, videoDts: 0.3 } }));
    expect(result.current.placement!.sAtBlob(13)).toBeCloseTo(21.3);
    expect(node.currentTime).toBeCloseTo(12.7);
  });
});

describe('useEditorVideoPlayback — 다른 창의 머리 알림', () => {
  it('이 창 조각이 아닌 seq 의 머리가 와도(카드 미리 받기) 다시 배치하지 않고 멈춘 영상을 옮기지 않는다', async () => {
    const key = 'key-other';
    const { result } = mount({ list: listOf([range(100, 124)], { timing: null }), mediaKey: key });
    const node = videoNode();
    act(() => result.current.videoRef(node));
    await flush();
    const before = result.current.placement;
    act(() => {
      node.currentTime = 5.05;
    });

    act(() => heads.set(key, 140, { state: 'ready', head: { hd: 0, hp: 0, videoDts: 0.3 } }));

    expect(result.current.placement).toBe(before);
    expect(node.currentTime).toBe(5.05);
  });
});

describe('useEditorVideoPlayback — 미리 받아 둔 창', () => {
  it('카드가 미리 받아 둔 창이면 다시 받지 않고 그 창으로 배치한다', async () => {
    windowBlobs.set(editorWindowKey(MEDIA_KEY, 102, 109), remux.of(range(102, 109)));
    const { result } = mount();
    await flush();
    expect(fetchedSeqs(fetchSpy)).toEqual([]);
    expect(result.current.src).toBe('blob:1');
    expect(result.current.placement!.sAtBlob(4)).toBeCloseTo(12);
  });

  it('끝 조각이 다른 창은 미리 받은 창이 아니다', async () => {
    windowBlobs.set(editorWindowKey(MEDIA_KEY, 102, 108), remux.of(range(102, 108)));
    mount();
    await flush();
    expect(fetchedSeqs(fetchSpy)).toEqual(range(102, 109));
  });
});

describe('useEditorVideoPlayback — 고름', () => {
  it('재생 위치에 보이는 프레임과 창 안 편집기 초의 프레임을 고른다. 창 밖이면 null 이다', async () => {
    const { result } = mount();
    const node = videoNode();
    act(() => result.current.videoRef(node));
    await flush();
    act(() => {
      node.currentTime = 13.2; // 편집기 21.2초 — 조각 105 의 21.0초 프레임이 보인다
    });

    expect(result.current.playback.pickAtPlayhead()).toMatchObject({ seq: 105, pts: 21 });
    expect(result.current.playback.pickAtS(30.7)).toMatchObject({ seq: 107, pts: 30.5 });
    expect(result.current.playback.pickAtS(50)).toBeNull();
  });

  it('창을 아직 못 받았으면 고를 프레임이 없다', () => {
    fetchSpy.mockImplementation(() => new Promise<Response>(() => undefined));
    const { result } = mount();
    expect(result.current.playback.pickAtPlayhead()).toBeNull();
    expect(result.current.playback.pickAtS(21)).toBeNull();
  });
});

describe('useEditorVideoPlayback: 받은 창 알리기 (POK-389)', () => {
  it('창은 영상을 다 받은 뒤에 알린다: 받는 동안 알리면 옛 영상을 새 창 시각으로 읽어 필름띠가 어긋난다(PR #341 codex)', async () => {
    let finish: () => void = () => undefined;
    const gate = new Promise<void>((resolve) => (finish = resolve));
    fetchSpy.mockImplementation(async () => {
      await gate;
      return new Response(new Uint8Array([1]), { status: 200 });
    });
    const { result } = mount();
    await flush();
    expect(result.current.window).toBeNull();
    // 받으려는 창은 처음부터 있다
    expect(result.current.targetWindow).toEqual({ startSeconds: 8, endSeconds: 40 });

    finish();
    await flush();
    expect(result.current.window).toEqual({ startSeconds: 8, endSeconds: 40 });
  });
});

describe('useEditorVideoPlayback: 창 다시 받기 예약 (POK-389)', () => {
  it('창 끝까지 끌었다가 곧 안쪽으로 돌리면 같은 창을 다시 받지 않는다(PR #341 codex)', async () => {
    const { result } = mount();
    await flush();
    fetchSpy.mockClear();

    await act(async () => {
      result.current.playback.setBounds({ startSeconds: 20, endSeconds: 39.8, loop: true });
      result.current.playback.setBounds({ startSeconds: 20, endSeconds: 30, loop: true });
      await vi.advanceTimersByTimeAsync(1_000);
    });
    await flush();
    expect(fetchSpy).not.toHaveBeenCalled();
  });
});

describe('useEditorVideoPlayback: 옛 영상 주소 지우기 (POK-389)', () => {
  it('새 창을 받는 동안에는 옛 영상 주소를 지우지 않고, 새 영상이 들어온 뒤에 지운다(PR #341 claude)', async () => {
    const { result } = mount();
    await flush();
    expect(result.current.src).toBe('blob:1');

    let finish: () => void = () => undefined;
    const gate = new Promise<void>((resolve) => (finish = resolve));
    fetchSpy.mockImplementation(async () => {
      await gate;
      return new Response(new Uint8Array([1]), { status: 200 });
    });
    // 구간을 창 끝 너머로 늘리면 새 창을 받는다. 받는 동안 <video>는 옛 주소를 그대로 쓴다
    await act(async () => {
      result.current.playback.setBounds({ startSeconds: 20, endSeconds: 45, loop: true });
      await vi.advanceTimersByTimeAsync(1_000);
    });
    await flush();
    expect(result.current.src).toBe('blob:1');
    expect(revoke).not.toHaveBeenCalledWith('blob:1');

    finish();
    await flush();
    expect(result.current.src).toBe('blob:2');
    expect(revoke).toHaveBeenCalledWith('blob:1');
    expect(revoke).not.toHaveBeenCalledWith('blob:2');
  });

  it('화면을 떠나면 받은 영상 주소를 지운다', async () => {
    const { unmount } = mount();
    await flush();
    unmount();
    expect(revoke).toHaveBeenCalledWith('blob:1');
  });
});

describe('useEditorVideoPlayback: 새 창을 받는 동안의 시각 (POK-389)', () => {
  it('새 창을 받는 동안은 아직 도는 옛 영상의 창으로 시각을 옮기고, 새 영상이 들어오면 새 창으로 옮긴다(PR #341 claude)', async () => {
    const { result } = mount();
    const node = videoNode();
    act(() => result.current.videoRef(node));
    await flush();
    // 첫 창: 8–40초(Blob 0 = 8초)
    act(() => {
      node.currentTime = 17;
      node.dispatchEvent(new Event('timeupdate'));
    });
    expect(result.current.playback.currentSeconds).toBe(25);

    let finish: () => void = () => undefined;
    const gate = new Promise<void>((resolve) => (finish = resolve));
    fetchSpy.mockImplementation(async () => {
      await gate;
      return new Response(new Uint8Array([1]), { status: 200 });
    });
    // 시작을 창 앞쪽 끝까지 당기면 새 창(0–40초)을 받는다. 받는 동안 <video>는 옛 창 영상 그대로다
    await act(async () => {
      result.current.playback.setBounds({ startSeconds: 8.2, endSeconds: 30, loop: true });
      await vi.advanceTimersByTimeAsync(1_000);
    });
    act(() => node.dispatchEvent(new Event('timeupdate')));
    expect(result.current.playback.currentSeconds).toBe(25);
    act(() => result.current.playback.seekTo(28));
    expect(node.currentTime).toBe(20);

    // 새 영상이 들어오면 새 창으로 옮기고, 구간 시작으로 간다
    finish();
    await flush();
    act(() => node.dispatchEvent(new Event('loadedmetadata')));
    expect(node.currentTime).toBeCloseTo(8.2);
    expect(result.current.playback.currentSeconds).toBeCloseTo(8.2);
  });
});

describe('useEditorVideoPlayback — 창 Blob 시크 규칙(kty M1 처분 (가))', () => {
  it('② 창 첫 영상 프레임 앞으로는 옮기지 않는다: 빈 편집 구간이면 Safari 가 앞 그림을 남긴다', async () => {
    const { result } = mount();
    const node = videoNode();
    act(() => result.current.videoRef(node));
    await flush();

    act(() => result.current.playback.seekTo(8)); // 창 첫 조각 시작 = Blob 0초 = 첫 영상 프레임

    expect(node.currentTime).toBeCloseTo(0.001, 9);
    expect(result.current.seekLimits).toEqual({ firstFrame: 0, halfFrame: 0.25 });
  });

  it('① 시크 목표는 video.duration − 반 프레임 안이다: Firefox 가 짧게 알린 끝 너머로 가지 않는다', async () => {
    const { result } = mount();
    const node = videoNode({ duration: 31.9 });
    act(() => result.current.videoRef(node));
    await flush();

    act(() => result.current.playback.seekTo(39.99));

    expect(node.currentTime).toBeCloseTo(31.9 - 0.25, 9);
  });

  it('머리가 와 다시 배치해 멈춘 영상을 옮길 때도 같은 규칙으로 묶는다', async () => {
    const key = 'key-m1';
    const { result } = mount({ list: listOf([range(100, 124)], { timing: null }), mediaKey: key });
    const node = videoNode();
    act(() => result.current.videoRef(node));
    await flush();
    act(() => result.current.playback.seekTo(8));

    // 조각 102 머리: m = 0.3 → 편집기 8초는 원점 앞 0.3초 자리지만 창 첫 프레임 앞으로는 가지 않는다
    act(() => heads.set(key, 102, { state: 'ready', head: { hd: 0, hp: 0, videoDts: 0.3 } }));

    expect(node.currentTime).toBeCloseTo(0.001, 9);
  });
});

describe('useEditorVideoPlayback: 구간 반복 (POK-490)', () => {
  /** 구간 20–30초, 창 8–40초를 받아 둔 영상 노드. duration 은 받은 영상의 실제 길이(초) */
  async function loaded(duration: number) {
    const hook = mount();
    const node = videoNode({ duration });
    act(() => hook.result.current.videoRef(node));
    await flush();
    expect(hook.result.current.window).toEqual({ startSeconds: 8, endSeconds: 40 });
    return { ...hook, node, play: node.play as ReturnType<typeof vi.fn> };
  }

  const tick = (node: HTMLVideoElement, videoSeconds: number) =>
    act(() => {
      node.currentTime = videoSeconds;
      node.dispatchEvent(new Event('timeupdate'));
    });

  it('반복이 켜져 있으면 구간 끝에 닿을 때 구간 시작으로 되감는다', async () => {
    const { node } = await loaded(32);
    tick(node, 22.1); // 편집기 30.1초 — 구간 끝을 지났다
    expect(node.currentTime).toBe(12); // 편집기 20초 = Blob 12초
  });

  it('받은 영상이 구간 끝보다 짧으면 영상 끝에서 되감는다: 전에는 영상 끝에서 멈춰 반복이 안 걸렸다', async () => {
    // 영상은 17초(편집기 8–25초)뿐이다 — 구간 끝 30초에 영영 닿지 않는다
    const { node } = await loaded(17);
    tick(node, 16.97);
    expect(node.currentTime).toBe(12);
  });

  it('영상 끝(ended)이 먼저 와도 반복이면 구간 시작으로 되감아 이어 튼다', async () => {
    const { node, play, result } = await loaded(17);
    act(() => {
      node.currentTime = 17;
      node.dispatchEvent(new Event('ended'));
    });
    expect(node.currentTime).toBe(12);
    expect(play).toHaveBeenCalledTimes(1);
    expect(result.current.playback.error).toBeNull();
  });

  it('받은 영상이 구간 시작에도 못 닿으면 영상 끝에서 반복하지 않고 멈춘다(PR #454 1차 리뷰)', async () => {
    const { node, play, result } = await loaded(10);
    act(() => {
      node.dispatchEvent(new Event('play'));
      node.currentTime = 10;
      node.dispatchEvent(new Event('ended'));
    });
    expect(play).not.toHaveBeenCalled();
    expect(result.current.playback.playing).toBe(false);
  });

  it('반복이 꺼져 있으면 영상 끝에서 멈춘다', async () => {
    const { node, play, result } = await loaded(17);
    act(() => result.current.playback.setBounds({ startSeconds: 20, endSeconds: 30, loop: false }));
    act(() => {
      node.dispatchEvent(new Event('play'));
      node.currentTime = 17;
      node.dispatchEvent(new Event('ended'));
    });
    expect(play).not.toHaveBeenCalled();
    expect(result.current.playback.playing).toBe(false);
    expect(node.currentTime).toBe(17);
  });
});

describe('useEditorVideoPlayback: 재생 위치 저장소', () => {
  async function opened() {
    const order: string[] = [];
    revoke.mockImplementation(() => order.push('revoke'));
    let frames: FrameRequestCallback[] = [];
    vi.stubGlobal('requestAnimationFrame', (cb: FrameRequestCallback) => frames.push(cb));
    vi.stubGlobal('cancelAnimationFrame', () => {
      frames = [];
    });
    const frame = () => {
      const due = frames;
      frames = [];
      for (const cb of due) cb(0);
    };
    const node = videoNode({
      pause: vi.fn(() => order.push('pause')),
      removeAttribute: vi.fn((name: string) => order.push(`remove ${name}`)),
      load: vi.fn(() => order.push('load')),
    });
    let renders = 0;
    const source = {
      state: READY,
      list: listOf([range(100, 124)]),
      listFailed: false,
      reloadList: vi.fn(async () => null),
      reportSegmentFailure: vi.fn(async () => undefined),
    };
    const hook = renderHook(() => {
      renders += 1;
      return useEditorVideoPlayback({
        streamId: 'round-a',
        mediaKey: MEDIA_KEY,
        base: BASE,
        source,
        initialRange: { startSeconds: 20, endSeconds: 30 },
      });
    });
    act(() => hook.result.current.videoRef(node));
    await flush();
    return { ...hook, node, frame, order, renders: () => renders };
  }

  const playing = (node: HTMLVideoElement & { paused: boolean }) =>
    act(() => {
      node.paused = false;
      node.dispatchEvent(new Event('play'));
    });

  it('재생 중 프레임마다 위치는 저장소로만 간다: 훅이 다시 돌면 편집기 전체가 초당 60번 다시 그려진다', async () => {
    const { node, frame, result, renders } = await opened();
    playing(node);
    const before = renders();

    act(() => {
      for (let i = 1; i <= 30; i += 1) {
        node.currentTime = 12 + i / 60;
        frame();
      }
    });

    expect(renders()).toBe(before);
    expect(result.current.playback.clock.getSeconds()).toBeCloseTo(20.5);
  });

  it('구간 경계는 프레임마다 본다: 끝을 넘긴 그 프레임에 되감고 허브에도 올린다', async () => {
    const { node, frame, result } = await opened();
    act(() => result.current.playback.seekTo(25));
    expect(result.current.playback.currentSeconds).toBe(25);
    playing(node);

    act(() => {
      node.currentTime = 22.01; // 편집기 30.01초
      frame();
    });

    expect(node.currentTime).toBe(12);
    expect(result.current.playback.clock.getSeconds()).toBe(20);
    expect(result.current.playback.currentSeconds).toBe(20);
  });

  it('끄는 동안은 멈춘 영상의 seeked도 허브를 깨우지 않고, 놓으면 마지막 자리가 올라온다', async () => {
    const { node, result, renders } = await opened();
    const before = renders();

    act(() => {
      for (const seconds of [22, 24, 26]) {
        result.current.playback.scrubTo(seconds);
        node.dispatchEvent(new Event('timeupdate'));
        node.dispatchEvent(new Event('seeked'));
      }
    });

    expect(renders()).toBe(before);
    expect(node.currentTime).toBe(18);
    expect(result.current.playback.clock.getSeconds()).toBe(26);

    act(() => result.current.playback.endScrub());
    expect(result.current.playback.currentSeconds).toBe(26);
  });

  it('멈추면 그 자리가 허브까지 올라온다', async () => {
    const { node, frame, result } = await opened();
    playing(node);
    act(() => {
      node.currentTime = 17;
      frame();
    });
    act(() => {
      node.paused = true;
      node.dispatchEvent(new Event('pause'));
    });
    expect(result.current.playback.currentSeconds).toBe(25);
  });

  it('편집기가 내려가면 영상 노드의 자원을 푼다: src를 지우고 load()까지 해야 디코더가 풀리고, 주소는 그 뒤에 지운다', async () => {
    const { node, order, unmount } = await opened();
    expect(node.load).not.toHaveBeenCalled();

    unmount();

    expect(order).toEqual(['pause', 'remove src', 'load', 'revoke']);
  });
});

describe('useEditorVideoPlayback: 끊겨 기다릴 때 (POK-439)', () => {
  it('재생 중 waiting 뒤 400ms가 지나면 알리고 playing에 내린다', () => {
    fetchSpy.mockImplementation(() => new Promise<Response>(() => undefined));
    const { result } = mount();
    const node = document.createElement('video');
    Object.defineProperty(node, 'paused', { value: false, configurable: true });
    act(() => result.current.videoRef(node));

    act(() => {
      node.dispatchEvent(new Event('waiting'));
      vi.advanceTimersByTime(399);
    });
    expect(result.current.buffering).toBe(false);
    act(() => {
      vi.advanceTimersByTime(1);
    });
    expect(result.current.buffering).toBe(true);
    act(() => {
      node.dispatchEvent(new Event('playing'));
    });
    expect(result.current.buffering).toBe(false);
  });
});

describe('useEditorVideoPlayback: 재생 거절 (POK-389)', () => {
  function rejectingNode(error: DOMException) {
    return Object.assign(document.createElement('video'), {
      play: () => Promise.reject(error),
    }) as HTMLVideoElement;
  }

  it('재생을 누르자마자 멈춰 play()가 AbortError로 거절돼도 오류로 알리지 않는다', async () => {
    fetchSpy.mockImplementation(() => new Promise<Response>(() => undefined));
    const { result } = mount();
    act(() =>
      result.current.videoRef(rejectingNode(new DOMException('interrupted', 'AbortError'))),
    );
    await act(async () => {
      result.current.playback.togglePlay();
      await Promise.resolve();
    });
    expect(result.current.playback.error).toBeNull();
  });

  it('그 밖의 이유로 재생을 못 하면 「불러오지 못함」으로 알린다', async () => {
    fetchSpy.mockImplementation(() => new Promise<Response>(() => undefined));
    const { result } = mount();
    act(() => result.current.videoRef(rejectingNode(new DOMException('bad', 'NotSupportedError'))));
    await act(async () => {
      result.current.playback.togglePlay();
      await Promise.resolve();
    });
    expect(result.current.playback.error).toBe('fatal');
  });
});
