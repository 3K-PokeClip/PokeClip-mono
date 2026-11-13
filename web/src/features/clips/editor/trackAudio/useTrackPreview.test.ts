import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { StrictMode } from 'react';
import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { requestSegmentAudioAccess, type SegmentAudioAccess } from '@/api/clipEditor';
import {
  clearEditorMedia,
  editorWindowFetching,
  segmentTrackKey,
  segmentTracks,
  watchEditorWindowFetch,
} from '../editorMedia';
import type { EditorPlaylist, PlaylistSegment } from '../source/editorPlaylist';
import { ensureHeads, failMissingHeads, headCacheFor } from '../source/fileHeadProbe';
import type { RemuxedWindow } from '../source/remuxWindow';
import type { WindowPlacement } from '../source/windowPlacement';
import type { TrackAudioLoad, TrackPiece } from './loadTrackAudio';
import {
  AUDIO_WAIT_LIMIT,
  AUDIO_WAIT_MS,
  loadSegmentTracks,
  TRACK_LOAD_TIMEOUT_MS,
  useTrackPreview,
  windowLevels,
  type LoadedTrackWindow,
  type TrackWindow,
} from './useTrackPreview';

vi.mock('@/api/clipEditor', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/clipEditor')>()),
  requestSegmentAudioAccess: vi.fn(),
}));

const fixture = readFileSync(join(__dirname, '__fixtures__', 'three-tracks.m4s'));
// 소리 트랙 하나짜리(최종 믹스만 녹화한 방송). three-tracks.m4s와 같은 ffmpeg 명령에서 소리를 하나만 넣었다:
//   ffmpeg -f lavfi -i testsrc=size=64x36:rate=10:duration=1 -f lavfi -i sine=440:48000 -map 0:v -map 1:a -t 1
//          -c:v libx264 -g 5 -c:a aac -b:a 32k -ac 2 -f mp4 -movflags frag_keyframe+empty_moov+default_base_moof one-track.m4s
const oneTrack = readFileSync(join(__dirname, '__fixtures__', 'one-track.m4s'));
const BASE = 1_000_000;

const segment = (seq: number, pdtMs: number): PlaylistSegment => ({
  seq,
  uri: `https://cdn/seg/${seq}.m4s`,
  mapUri: 'https://cdn/init.mp4',
  pdtMs,
  durationMs: 1000,
  timing: 'v2',
  gap: false,
});

/** 창 하나 — 조각 seq 들(1초씩)과 그 S 범위. 첫 조각 PDT = BASE + startSeconds */
function windowOf(startSeconds: number, seqs: readonly number[]): TrackWindow {
  return {
    key: `m:${seqs[0]}-${seqs.at(-1)}`,
    segs: seqs.map((seq, i) => segment(seq, BASE + (startSeconds + i) * 1000)),
    startSeconds,
    endSeconds: startSeconds + seqs.length,
  };
}

/**
 * 영상이 받은 그 창 — 다시 싼 결과(Δ = 첫 조각 ③ DTS · 닻 0, 1, …)와 배치(S → Blob = S − 창 시작). 시험 조각(three-tracks.m4s)은
 * ② 영상 · 소리 첫 시각이 모두 0 이라 조각 k 의 소리는 Blob k 초에 놓인다
 */
function loadedOf(win: TrackWindow): LoadedTrackWindow {
  const dts = 500;
  const remuxed: RemuxedWindow = {
    blob: new Blob(['mp4']),
    shiftSeconds: dts,
    segments: win.segs.map((seg, i) => ({
      seq: seg.seq,
      videoDts: dts + i,
      frames: [],
      mediaStart: dts + i,
      mediaEnd: dts + i + 1,
    })),
  };
  const placement = {
    blobAtS: (s: number) => s - win.startSeconds,
  } as unknown as WindowPlacement;
  return { key: win.key, remuxed, placement };
}

/** 카드가 미리 받아 풀어 둔 소리 한 덩어리(1초) */
const CACHED_BUFFER = {
  duration: 1,
  length: 100,
  sampleRate: 100,
  numberOfChannels: 1,
  getChannelData: () => new Float32Array(100).fill(0.5),
} as unknown as AudioBuffer;

/** 카드가 미리 받아 맡긴 소리(TrackAudioLoad) — 빠진 행 · 확보 기다림 없이 다 받은 것 */
const cachedLoad = (
  tracks: TrackPiece[][],
  extra: Partial<Pick<TrackAudioLoad, 'missingSeqs' | 'capture'>> = {},
): TrackAudioLoad => ({
  tracks,
  missingSeqs: [],
  capture: 'none',
  reuse: { segments: new Map(), capture: null, decoded: new Map() },
  ...extra,
});

const WINDOW = windowOf(10, [1]);
const LOADED = loadedOf(WINDOW);
/** 1초 조각 셋(seq 1–3 · S 10–13초) */
const THREE = windowOf(10, [1, 2, 3]);

/** ADTS 빈도 번호 → 표본 빈도(Hz) */
const FREQUENCIES = [
  96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350,
];

/**
 * 가짜 풀기 — 실물처럼 프레임 수 × 1024 / 설정 빈도 길이의 소리(100Hz 표본 · 0.5)를 돌려준다. 머리를 버리지 않는다(Chrome 꼴):
 * 길이가 프레임과 어긋나면 loadTrackAudio 가 그만큼 버린 것으로 보고 시작을 민다
 */
function fakeDecoded(adts: ArrayBuffer): AudioBuffer {
  const bytes = new Uint8Array(adts);
  let frames = 0;
  for (let offset = 0; offset < bytes.length; frames++) {
    offset +=
      ((bytes[offset + 3]! & 0x03) << 11) | (bytes[offset + 4]! << 3) | (bytes[offset + 5]! >> 5);
  }
  const duration = (frames * 1024) / FREQUENCIES[(bytes[2]! >> 2) & 0x0f]!;
  const length = Math.round(duration * 100);
  return {
    duration,
    length,
    sampleRate: 100,
    numberOfChannels: 1,
    getChannelData: () => new Float32Array(length).fill(0.5),
  } as unknown as AudioBuffer;
}

function fakeContext() {
  const gains: { gain: { value: number }; disconnect: ReturnType<typeof vi.fn> }[] = [];
  return {
    gains,
    ctx: {
      currentTime: 0,
      state: 'running',
      destination: {},
      resume: vi.fn(),
      close: vi.fn(function (this: { state: string }) {
        this.state = 'closed';
        return Promise.resolve();
      }),
      decodeAudioData: vi.fn((adts: ArrayBuffer) => Promise.resolve(fakeDecoded(adts))),
      createGain() {
        const node = { gain: { value: 1 }, connect: vi.fn(), disconnect: vi.fn() };
        gains.push(node);
        return node;
      },
      createBufferSource: () => ({
        playbackRate: { value: 1 },
        connect: vi.fn(),
        disconnect: vi.fn(),
        start: vi.fn(),
        stop: vi.fn(),
      }),
    },
  };
}

const video = () =>
  Object.assign(new EventTarget(), {
    paused: true,
    seeking: false,
    currentTime: 0,
    playbackRate: 1,
    muted: false,
  }) as unknown as HTMLVideoElement;

describe('useTrackPreview', () => {
  beforeEach(() => {
    vi.mocked(requestSegmentAudioAccess).mockResolvedValue({
      expiresAt: '2026-10-23T00:05:00Z',
      segments: [
        { seq: 1, startAtMs: BASE + 10_000, durationMs: 1000, url: 'https://s3/seg_1.m4s' },
      ],
    });
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve(new Response(new Uint8Array(fixture)))),
    );
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.mocked(requestSegmentAudioAccess).mockReset();
    clearEditorMedia();
  });

  it('영상이 받은 창의 조각을 묻고, 트랙마다 음량을 걸며, 따라가는 동안 영상 소리를 끈다', async () => {
    const { ctx, gains } = fakeContext();
    const node = video();
    const { result } = renderHook(() =>
      useTrackPreview({
        streamId: 's-1',
        window: WINDOW,
        loaded: LOADED,
        video: node,
        createContext: () => ctx as never,
      }),
    );
    act(() => result.current.setGains([0, 1.3, 0.6]));
    await waitFor(() => expect(result.current.status).toBe('ready'));

    // 창 조각이 덮는 범위 [첫 PDT, 마지막 PDT + EXTINF) 를 표지 없이 묻는다
    expect(vi.mocked(requestSegmentAudioAccess).mock.calls).toEqual([
      ['s-1', BASE + 10_000, BASE + 11_000],
    ]);
    // 준비 전에 받은 음량도 준비되자마자 걸린다
    expect(gains.map((g) => g.gain.value)).toEqual([0, 1.3, 0.6]);
    act(() => result.current.setGains([1, 0, 0]));
    expect(gains.map((g) => g.gain.value)).toEqual([1, 0, 0]);
    expect(node.muted).toBe(true);
    // 타임라인 파형: 창(10~11초)을 0.05초 칸으로 잰다 — 편집기 초로 그리고, 소리는 Blob 시각(0~1초)에서 잰다
    expect(result.current.levels?.tracks).toHaveLength(3);
    expect(result.current.levels?.startSeconds).toBe(10);
    expect(result.current.levels?.stepSeconds).toBeCloseTo(0.05, 9);
    expect(result.current.levels?.tracks[0]![0]).toBeCloseTo(0.5);
  });

  it('소리 주소는 창 조각이 덮는 범위만 묻는다: 확보 후보(창 뒤 조각)만큼 넓히지 않고 표지(axis)도 싣지 않는다', async () => {
    const win = windowOf(10, [1, 2, 3]);
    const { result } = renderHook(() =>
      useTrackPreview({
        streamId: 's-1',
        window: win,
        loaded: loadedOf(win),
        video: video(),
        createContext: () => fakeContext().ctx as never,
      }),
    );
    await waitFor(() => expect(result.current.status).not.toBe('loading'));

    expect(vi.mocked(requestSegmentAudioAccess).mock.calls).toEqual([
      ['s-1', BASE + 10_000, BASE + 13_000],
    ]);
  });

  it('파형은 창 S 범위를 Blob 시각으로 바꿔 잰다: 편집기 초 5,000초 창도 Blob 0초부터 놓인 소리로 비지 않는다', async () => {
    const win = windowOf(5_000, [1]);
    const { result } = renderHook(() =>
      useTrackPreview({
        streamId: 's-1',
        window: win,
        loaded: loadedOf(win),
        video: video(),
        createContext: () => fakeContext().ctx as never,
      }),
    );
    await waitFor(() => expect(result.current.status).toBe('ready'));

    expect(result.current.levels?.startSeconds).toBe(5_000);
    expect(result.current.levels?.tracks[0]).toHaveLength(20);
    expect(result.current.levels?.tracks[0]![0]).toBeCloseTo(0.5);
    expect(result.current.levels?.tracks[0]![19]).toBeCloseTo(0.5);
  });

  it('배속 중에는 음량이 영상 소리와 같으면 영상 소리로 듣는다: 소리 노드 배속은 음높이까지 바꾼다(PR #341 2차)', async () => {
    const { ctx, gains } = fakeContext();
    const node = video();
    const { result } = renderHook(() =>
      useTrackPreview({
        streamId: 's-1',
        window: WINDOW,
        loaded: LOADED,
        video: node,
        createContext: () => ctx as never,
      }),
    );
    act(() => result.current.setGains([1, 0, 0]));
    await waitFor(() => expect(result.current.status).toBe('ready'));
    expect(node.muted).toBe(true);

    // 2×: 영상 소리(음높이 그대로)가 같은 소리를 낸다 → 재생기는 쉬고 영상 소리를 켠다
    act(() => {
      node.playbackRate = 2;
      node.dispatchEvent(new Event('ratechange'));
    });
    expect(node.muted).toBe(false);
    expect(gains.map((g) => g.gain.value)).toEqual([0, 0, 0]);

    // 음량을 바꿔 두면 그 음량이 들리는 쪽(재생기)으로 돌아간다
    act(() => result.current.setGains([1, 0.5, 0]));
    expect(node.muted).toBe(true);
    expect(gains.map((g) => g.gain.value)).toEqual([1, 0.5, 0]);

    // 1×로 돌리면 늘 재생기다
    act(() => {
      result.current.setGains([1, 0, 0]);
      node.playbackRate = 1;
      node.dispatchEvent(new Event('ratechange'));
    });
    expect(node.muted).toBe(true);
    expect(gains.map((g) => g.gain.value)).toEqual([1, 0, 0]);
  });

  it('음량만 바뀌면 화면을 다시 그리지 않고 재생기에 바로 건다: 음량 숫자를 끄는 동안 값이 초당 수십 번 온다(PR #341 3차)', async () => {
    const { ctx, gains } = fakeContext();
    let renders = 0;
    const { result } = renderHook(() => {
      renders += 1;
      return useTrackPreview({
        streamId: 's-1',
        window: WINDOW,
        loaded: LOADED,
        video: video(),
        createContext: () => ctx as never,
      });
    });
    act(() => result.current.setGains([0, 1, 0]));
    await waitFor(() => expect(result.current.status).toBe('ready'));

    const before = renders;
    act(() => result.current.setGains([0, 0.9, 0]));
    act(() => result.current.setGains([0, 0.8, 0.3]));
    expect(renders).toBe(before);
    expect(gains.map((g) => g.gain.value)).toEqual([0, 0.8, 0.3]);
  });

  it('영상 창이 오기 전에도 조각을 받기 시작하고, 그 창의 Blob 닻이 오면 놓아 ready 다(PR #341 2차)', async () => {
    const { ctx } = fakeContext();
    const { result, rerender } = renderHook(
      ({ loaded }: { loaded: LoadedTrackWindow | null }) =>
        useTrackPreview({
          streamId: 's-1',
          window: WINDOW,
          loaded,
          video: video(),
          createContext: () => ctx as never,
        }),
      { initialProps: { loaded: null as LoadedTrackWindow | null } },
    );
    // 영상 창이 아직 없어도 받을 창으로 조각을 묻고 받는다
    await waitFor(() => expect(fetch).toHaveBeenCalled());
    expect(requestSegmentAudioAccess).toHaveBeenCalled();
    // 다른 창(앞 창)을 받은 영상은 이 창의 닻이 아니다
    rerender({ loaded: loadedOf(windowOf(20, [9])) });
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(result.current.status).toBe('loading');

    rerender({ loaded: LOADED });
    await waitFor(() => expect(result.current.status).toBe('ready'));
  });

  it('미리 받아 둔 소리도 그 창을 받은 영상의 닻이 올 때까지 놓지 않는다: 앞 창 영상에 새 창 소리를 틀지 않는다', async () => {
    const { ctx, gains } = fakeContext();
    const node = video();
    // 카드가 미리 받아 둔 창(seq 7–8)의 소리 — 영상 창 캐시는 따로 버려져(예산이 다르다) 영상은 아직 앞 창을 튼다
    const next = windowOf(20, [7, 8]);
    segmentTracks.set(
      segmentTrackKey('s-1', 7, 8),
      cachedLoad([[{ startSeconds: 0, buffer: CACHED_BUFFER }]]),
    );
    const { result, rerender } = renderHook(
      ({ loaded }: { loaded: LoadedTrackWindow | null }) =>
        useTrackPreview({
          streamId: 's-1',
          window: next,
          loaded,
          video: node,
          createContext: () => ctx as never,
        }),
      { initialProps: { loaded: LOADED as LoadedTrackWindow | null } },
    );
    await new Promise((resolve) => setTimeout(resolve, 20));

    // 앞 창 영상이면 재생기를 만들지 않고 영상 소리를 그대로 둔다. 캐시에서 꺼냈으니 다시 받지도 않는다
    expect(result.current.status).toBe('loading');
    expect(gains).toHaveLength(0);
    expect(node.muted).toBe(false);
    expect(requestSegmentAudioAccess).not.toHaveBeenCalled();

    rerender({ loaded: loadedOf(next) });
    await waitFor(() => expect(result.current.status).toBe('ready'));
    expect(gains).toHaveLength(1);
    expect(node.muted).toBe(true);
  });

  it('그 창 영상을 받다 실패하면(③ 받기 · 다시 싸기) 닻을 더 기다리지 않고 「못 부름」으로 영상 소리를 되살린다', async () => {
    const node = video();
    const { result, rerender } = renderHook(
      ({ failedKey }: { failedKey: string | null }) =>
        useTrackPreview({
          streamId: 's-1',
          window: WINDOW,
          loaded: null,
          failedKey,
          video: node,
          createContext: () => fakeContext().ctx as never,
        }),
      { initialProps: { failedKey: null as string | null } },
    );
    act(() => result.current.setGains([0, 1, 0]));
    // ② 받기는 끝났고 닻(영상 창)을 기다린다 — 음량을 바꿔 둔 편집본이라 영상 소리는 꺼져 있다
    await waitFor(() => expect(fetch).toHaveBeenCalled());
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(result.current.status).toBe('loading');
    expect(node.muted).toBe(true);

    // 다른 창(앞 창)의 실패는 이 창의 닻과 무관하다
    rerender({ failedKey: 'm:9-9' });
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(result.current.status).toBe('loading');

    rerender({ failedKey: WINDOW.key });
    await waitFor(() => expect(result.current.status).toBe('unavailable'));
    expect(node.muted).toBe(false);
    expect(result.current.levels).toBeNull();
  });

  it('미리 받아 둔 소리로 닻을 기다리다 그 창 영상이 실패해도 「못 부름」이다', async () => {
    const node = video();
    segmentTracks.set(
      segmentTrackKey('s-1', 1, 1),
      cachedLoad([[{ startSeconds: 0, buffer: CACHED_BUFFER }]]),
    );
    const { result, rerender } = renderHook(
      ({ failedKey }: { failedKey: string | null }) =>
        useTrackPreview({
          streamId: 's-1',
          window: WINDOW,
          loaded: null,
          failedKey,
          video: node,
          createContext: () => fakeContext().ctx as never,
        }),
      { initialProps: { failedKey: null as string | null } },
    );
    act(() => result.current.setGains([0, 1, 0]));
    expect(result.current.status).toBe('loading');

    rerender({ failedKey: WINDOW.key });
    await waitFor(() => expect(result.current.status).toBe('unavailable'));
    expect(node.muted).toBe(false);
  });

  it('재생기는 영상 노드의 Blob 시각(currentTime)을 그대로 따라간다: 편집기 초로 옮기지 않는다', async () => {
    const { ctx } = fakeContext();
    const sources = vi.spyOn(ctx, 'createBufferSource');
    const node = Object.assign(video(), { paused: false, readyState: 4, currentTime: 0.5 });
    const { result } = renderHook(() =>
      useTrackPreview({
        streamId: 's-1',
        window: WINDOW,
        loaded: LOADED,
        video: node,
        createContext: () => ctx as never,
      }),
    );
    await waitFor(() => expect(result.current.status).toBe('ready'));

    // 조각 소리는 Blob 0초에 놓였다 — 영상 0.5초 자리면 그 덩어리의 0.5초부터 튼다
    expect(sources).toHaveBeenCalled();
    const started = sources.mock.results.map(
      (r) => (r.value as { start: ReturnType<typeof vi.fn> }).start,
    );
    expect(started[0]).toHaveBeenCalledWith(0, 0.5);
  });

  it('소리 문맥을 깨우지 못하면 「못 부름」으로 바꿔 영상 소리를 되살린다(PR #341 codex)', async () => {
    const { ctx } = fakeContext();
    Object.assign(ctx, {
      state: 'suspended',
      resume: vi.fn(() => Promise.reject(new DOMException('closed', 'InvalidStateError'))),
    });
    const node = Object.assign(video(), { readyState: 4 });
    const { result } = renderHook(() =>
      useTrackPreview({
        streamId: 's-1',
        window: WINDOW,
        loaded: LOADED,
        video: node,
        createContext: () => ctx as never,
      }),
    );
    await waitFor(() => expect(result.current.status).toBe('ready'));
    expect(node.muted).toBe(true);

    act(() => {
      Object.assign(node, { paused: false });
      node.dispatchEvent(new Event('playing'));
    });
    await waitFor(() => expect(result.current.status).toBe('unavailable'));
    expect(ctx.resume).toHaveBeenCalled();
    expect(node.muted).toBe(false);
  });

  it('붙였다 떼었다 다시 붙여도(개발 모드 StrictMode) 닫힌 소리 문맥을 쓰지 않는다', async () => {
    // 닫힌 문맥은 시계가 0에 멈춰 소리가 안 나고, 어긋남 검사가 0.25초마다 다시 건다(로컬 실화면에서 소리 없음으로 재현)
    const made: ReturnType<typeof fakeContext>['ctx'][] = [];
    const { result } = renderHook(
      () =>
        useTrackPreview({
          streamId: 's-1',
          window: WINDOW,
          loaded: LOADED,
          video: video(),
          createContext: () => {
            const { ctx } = fakeContext();
            made.push(ctx);
            return ctx as never;
          },
        }),
      { wrapper: StrictMode },
    );
    await waitFor(() => expect(result.current.status).toBe('ready'));
    const used = made.filter((ctx) => ctx.decodeAudioData.mock.calls.length > 0);
    expect(used.length).toBeGreaterThan(0);
    expect(used.at(-1)!.state).toBe('running');
  });

  it('음량을 바꿔 두었으면 불러오는 동안에도 영상 소리를 끈다: 뺀 트랙까지 섞인 최종 믹스가 들리지 않게(로컬 리뷰 1라운드)', async () => {
    const node = video();
    let finish: (value: unknown) => void = () => {};
    vi.mocked(requestSegmentAudioAccess).mockReturnValueOnce(
      new Promise((resolve) => {
        finish = resolve;
      }) as never,
    );
    const { result } = renderHook(() =>
      useTrackPreview({
        streamId: 's-1',
        window: WINDOW,
        loaded: LOADED,
        video: node,
        createContext: () => fakeContext().ctx as never,
      }),
    );
    act(() => result.current.setGains([0, 1, 0]));
    expect(result.current.status).toBe('loading');
    expect(node.muted).toBe(true);
    finish({ expiresAt: '', segments: [] });
    // 창 조각의 ② 가 아직 하나도 없다 → 기다림(무음 그대로 · 못 부름 알림 없음 — kty 결정 (나) c10b′ r1 5번)
    await waitFor(() => expect(result.current.status).toBe('waiting'));
    expect(node.muted).toBe(true);
  });

  it('음량이 영상 소리와 같으면(첫 트랙만 100%) 불러오는 동안 영상 소리를 그대로 둔다: 조각을 다 받는 수십 초 동안 조용하지 않게(PR #341 claude)', async () => {
    const node = video();
    let finish: (value: unknown) => void = () => {};
    vi.mocked(requestSegmentAudioAccess).mockReturnValueOnce(
      new Promise((resolve) => {
        finish = resolve;
      }) as never,
    );
    const { result } = renderHook(() =>
      useTrackPreview({
        streamId: 's-1',
        window: WINDOW,
        loaded: LOADED,
        video: node,
        createContext: () => fakeContext().ctx as never,
      }),
    );
    act(() => result.current.setGains([1, 0, 0]));
    expect(result.current.status).toBe('loading');
    expect(node.muted).toBe(false);
    // 트랙을 하나 더 넣으면 영상 소리와 달라진다: 그때부터는 끈다
    act(() => result.current.setGains([1, 0.5, 0]));
    expect(node.muted).toBe(true);
    act(() => result.current.setGains([1, 0, 0]));
    expect(node.muted).toBe(false);

    finish({
      expiresAt: '',
      segments: [
        { seq: 1, startAtMs: BASE + 10_000, durationMs: 1000, url: 'https://s3/seg_1.m4s' },
      ],
    });
    // 재생기가 준비되면 소리는 재생기가 낸다
    await waitFor(() => expect(result.current.status).toBe('ready'));
    expect(node.muted).toBe(true);
  });

  it('창이 바뀌면 앞 창 재생기를 바로 버린다: 새 창 소리가 오면 그 창 Blob 시각으로 붙는다(설계 prw2 3.3 · L2)', async () => {
    const { ctx, gains } = fakeContext();
    const node = video();
    const { result, rerender } = renderHook(
      ({ window, loaded }: { window: TrackWindow; loaded: LoadedTrackWindow | null }) =>
        useTrackPreview({
          streamId: 's-1',
          window,
          loaded,
          video: node,
          createContext: () => ctx as never,
        }),
      { initialProps: { window: WINDOW, loaded: LOADED as LoadedTrackWindow | null } },
    );
    act(() => result.current.setGains([1, 0, 0]));
    await waitFor(() => expect(result.current.status).toBe('ready'));
    const firstPlayerGains = [...gains];
    expect(firstPlayerGains).toHaveLength(3);
    expect(node.muted).toBe(true);

    // in 을 20초 앞으로 끌어 새 창을 받기 시작했다 — 영상은 아직 앞 창(LOADED)을 튼다
    const next = windowOf(-10, [5]);
    vi.mocked(requestSegmentAudioAccess).mockResolvedValueOnce({
      expiresAt: '2026-10-23T00:05:00Z',
      segments: [{ seq: 5, startAtMs: BASE - 10_000, durationMs: 1000, url: 'https://s3/seg_5' }],
    });
    rerender({ window: next, loaded: LOADED });
    expect(result.current.status).toBe('loading');
    // 앞 재생기는 즉시 멈추고(새 창 Blob 시각을 따라가 틀린 소리를 내지 않게) 영상 소리가 난다. 파형은 그대로 둔다
    for (const gain of firstPlayerGains) expect(gain.disconnect).toHaveBeenCalled();
    expect(node.muted).toBe(false);
    expect(result.current.levels).not.toBeNull();

    rerender({ window: next, loaded: loadedOf(next) });
    await waitFor(() => expect(result.current.status).toBe('ready'));
    expect(gains).toHaveLength(6);
    expect(node.muted).toBe(true);
    expect(result.current.levels?.startSeconds).toBe(-10);
  });

  it('창을 다시 받다 실패하면 영상 소리로 돌아가고 파형도 비운다: 꺼진 채 무음으로 남지 않게(PR #341 2차)', async () => {
    const { ctx, gains } = fakeContext();
    const node = video();
    const { result, rerender } = renderHook(
      ({ window }: { window: TrackWindow }) =>
        useTrackPreview({
          streamId: 's-1',
          window,
          loaded: LOADED,
          video: node,
          createContext: () => ctx as never,
        }),
      { initialProps: { window: WINDOW } },
    );
    act(() => result.current.setGains([0, 1, 0]));
    await waitFor(() => expect(result.current.status).toBe('ready'));
    const firstPlayerGains = [...gains];

    vi.mocked(requestSegmentAudioAccess).mockRejectedValueOnce(new Error('503'));
    rerender({ window: windowOf(5, [3]) });
    await waitFor(() => expect(result.current.status).toBe('unavailable'));
    for (const gain of firstPlayerGains) expect(gain.disconnect).toHaveBeenCalled();
    expect(node.muted).toBe(false);
    expect(result.current.levels).toBeNull();
  });

  it('조각 받기가 멈춰 있으면 시한 뒤 「못 부름」으로 영상 소리를 되살린다(로컬 리뷰 2라운드)', async () => {
    vi.useFakeTimers();
    try {
      // 서명 주소 GET이 끝나지 않는다(느린 망 · 붙잡힌 연결)
      vi.mocked(fetch).mockImplementation(() => new Promise<Response>(() => {}));
      const node = video();
      const { result } = renderHook(() =>
        useTrackPreview({
          streamId: 's-1',
          window: WINDOW,
          loaded: LOADED,
          video: node,
          createContext: () => fakeContext().ctx as never,
        }),
      );
      act(() => result.current.setGains([0, 1, 0]));
      expect(node.muted).toBe(true);
      await act(async () => {
        await vi.advanceTimersByTimeAsync(TRACK_LOAD_TIMEOUT_MS + 10);
      });
      expect(result.current.status).toBe('unavailable');
      expect(node.muted).toBe(false);
    } finally {
      vi.useRealTimers();
    }
  });

  it('느려도 조각이 계속 오면 끊지 않는다: 시한은 진척이 멈춘 시간이다(PR #341 2차)', async () => {
    vi.useFakeTimers();
    try {
      vi.mocked(requestSegmentAudioAccess).mockResolvedValue({
        expiresAt: '2026-10-23T00:05:00Z',
        segments: [1, 2, 3].map((seq) => ({
          seq,
          startAtMs: BASE + 9_000 + seq * 1000,
          durationMs: 1000,
          url: `https://s3/seg_${seq}.m4s`,
        })),
      });
      // 조각마다 40초 걸린다(동시에 넷까지라 셋이 함께 40초). 전체 시한이면 60초에 끊겼다
      let calls = 0;
      vi.mocked(fetch).mockImplementation(() => {
        calls += 1;
        const delay = calls * 40_000;
        return new Promise<Response>((resolve) =>
          setTimeout(() => resolve(new Response(new Uint8Array(fixture))), delay),
        );
      });
      const { result } = renderHook(() =>
        useTrackPreview({
          streamId: 's-1',
          window: THREE,
          loaded: loadedOf(THREE),
          video: video(),
          createContext: () => fakeContext().ctx as never,
        }),
      );
      // 40 · 80 · 120초에 하나씩 온다: 사이는 늘 40초라 시한(60초)에 안 걸린다
      await act(async () => {
        await vi.advanceTimersByTimeAsync(130_000);
      });
      expect(result.current.status).toBe('ready');
    } finally {
      vi.useRealTimers();
    }
  });

  it('창이 바뀌면 앞 창의 조각 받기를 멈춘다(로컬 리뷰 2라운드)', async () => {
    const signals: AbortSignal[] = [];
    vi.mocked(fetch).mockImplementation((_url, init) => {
      signals.push((init as RequestInit).signal!);
      return new Promise<Response>(() => {});
    });
    const node = video();
    const { rerender } = renderHook(
      ({ window }: { window: TrackWindow }) =>
        useTrackPreview({
          streamId: 's-1',
          window,
          loaded: LOADED,
          video: node,
          createContext: () => fakeContext().ctx as never,
        }),
      { initialProps: { window: WINDOW } },
    );
    await waitFor(() => expect(signals).toHaveLength(1));
    rerender({ window: windowOf(20, [7]) });
    expect(signals[0]!.aborted).toBe(true);
  });

  it('조각이 403(서명 주소 만료)이면 주소를 새로 받아 이어 받는다(PR #341 3차)', async () => {
    vi.mocked(requestSegmentAudioAccess)
      .mockResolvedValueOnce({
        expiresAt: '2026-10-23T00:05:00Z',
        segments: [{ seq: 1, startAtMs: BASE + 10_000, durationMs: 1000, url: 'https://s3/old' }],
      })
      .mockResolvedValueOnce({
        expiresAt: '2026-10-23T00:10:00Z',
        segments: [{ seq: 1, startAtMs: BASE + 10_000, durationMs: 1000, url: 'https://s3/new' }],
      });
    vi.mocked(fetch).mockImplementation((url) =>
      Promise.resolve(
        String(url) === 'https://s3/old'
          ? new Response(null, { status: 403 })
          : new Response(new Uint8Array(fixture)),
      ),
    );
    const { result } = renderHook(() =>
      useTrackPreview({
        streamId: 's-1',
        window: WINDOW,
        loaded: LOADED,
        video: video(),
        createContext: () => fakeContext().ctx as never,
      }),
    );
    await waitFor(() => expect(result.current.status).toBe('ready'));
    expect(requestSegmentAudioAccess).toHaveBeenCalledTimes(2);
  });

  it('조각 하나가 실패하면 받는 중인 다른 조각도 멈춘다(PR #341 claude)', async () => {
    vi.mocked(requestSegmentAudioAccess).mockResolvedValue({
      expiresAt: '2026-10-23T00:05:00Z',
      segments: [1, 2, 3].map((seq) => ({
        seq,
        startAtMs: BASE + 9_000 + seq * 1000,
        durationMs: 1000,
        url: `https://s3/seg_${seq}.m4s`,
      })),
    });
    const signals: AbortSignal[] = [];
    vi.mocked(fetch).mockImplementation((url, init) => {
      if (String(url).endsWith('seg_1.m4s'))
        return Promise.resolve(new Response(null, { status: 503 }));
      signals.push((init as RequestInit).signal!);
      return new Promise<Response>(() => {});
    });
    const { result } = renderHook(() =>
      useTrackPreview({
        streamId: 's-1',
        window: THREE,
        loaded: loadedOf(THREE),
        video: video(),
        createContext: () => fakeContext().ctx as never,
      }),
    );
    await waitFor(() => expect(result.current.status).toBe('unavailable'));
    expect(signals).toHaveLength(2);
    for (const signal of signals) expect(signal.aborted).toBe(true);
  });

  it('소리 문맥을 못 만들면(브라우저가 안 줌) 묻지도 않고 못 부름이다: 영상 소리가 그대로 난다', async () => {
    const node = video();
    const { result } = renderHook(() =>
      useTrackPreview({
        streamId: 's-1',
        window: WINDOW,
        loaded: LOADED,
        video: node,
        createContext: () => {
          throw new Error('no AudioContext');
        },
      }),
    );
    await waitFor(() => expect(result.current.status).toBe('unavailable'));
    expect(requestSegmentAudioAccess).not.toHaveBeenCalled();
    expect(node.muted).toBe(false);
  });

  it('주소를 못 받거나 소리 트랙을 하나도 못 꺼내면 못 부름이다', async () => {
    vi.mocked(requestSegmentAudioAccess).mockRejectedValueOnce(new Error('503'));
    const first = renderHook(() =>
      useTrackPreview({
        streamId: 's-1',
        window: WINDOW,
        loaded: LOADED,
        video: video(),
        createContext: () => fakeContext().ctx as never,
      }),
    );
    await waitFor(() => expect(first.result.current.status).toBe('unavailable'));

    // 소리 트랙을 못 꺼내는 조각(상자가 없다)
    vi.mocked(fetch).mockResolvedValue(new Response(new Uint8Array(16)));
    const second = renderHook(() =>
      useTrackPreview({
        streamId: 's-1',
        window: WINDOW,
        loaded: LOADED,
        video: video(),
        createContext: () => fakeContext().ctx as never,
      }),
    );
    await waitFor(() => expect(second.result.current.status).toBe('unavailable'));
  });

  it('소리 트랙이 하나뿐이어도 재생기로 튼다: 그 트랙의 음량·넣기가 미리보기에 들린다(PR #341 2차)', async () => {
    vi.mocked(fetch).mockResolvedValue(new Response(new Uint8Array(oneTrack)));
    const { ctx, gains } = fakeContext();
    const node = video();
    const { result } = renderHook(() =>
      useTrackPreview({
        streamId: 's-1',
        window: WINDOW,
        loaded: LOADED,
        video: node,
        createContext: () => ctx as never,
      }),
    );
    await waitFor(() => expect(result.current.status).toBe('ready'));
    expect(node.muted).toBe(true);
    expect(result.current.levels?.tracks).toHaveLength(1);
    // 음량을 반으로 내리면 재생기 음량도 반이다(영상 소리 100%로 남지 않는다)
    act(() => result.current.setGains([0.5]));
    expect(gains.map((g) => g.gain.value)).toEqual([0.5]);
  });

  it('영상이 아직 창을 못 받았으면(첫 창을 받는 중 · 녹화 틈) 묻지 않고 기다린다: 실패로 알리지 않는다(PR #341 claude)', () => {
    const node = video();
    const { result } = renderHook(() =>
      useTrackPreview({
        streamId: 's-1',
        window: null,
        loaded: null,
        video: node,
        createContext: () => fakeContext().ctx as never,
      }),
    );
    expect(result.current.status).toBe('idle');
    expect(requestSegmentAudioAccess).not.toHaveBeenCalled();
    expect(node.muted).toBe(false);
  });
});

// --- ② 기다림 고리 · 끝 소리 확보(설계 prw2 7절 c10b′ · 계획 검증 묶음 C 뒤) ---

/** 창 조각 seq 들의 소리 주소 응답 — 창 조각과 같은 PDT 자리(1초씩) · 확보 칸은 덧붙인다 */
function accessFor(
  seqs: readonly number[],
  extra: {
    audioCapture?: string;
    audioCaptureSegment?: SegmentAudioAccess['segments'][number];
  } = {},
): SegmentAudioAccess {
  return {
    expiresAt: '2026-10-23T00:05:00Z',
    segments: seqs.map((seq) => ({
      seq,
      startAtMs: BASE + (9 + seq) * 1000,
      durationMs: 1000,
      url: `https://s3/seg_${seq}.m4s`,
    })),
    ...extra,
  } as SegmentAudioAccess;
}

/** 확보 조각 seq 4 의 ② 행 */
const CAPTURE_4 = {
  seq: 4,
  startAtMs: BASE + 13_000,
  durationMs: 1000,
  url: 'https://s3/seg_4.m4s',
};

/** 판 NULL 창(② 머리를 읽는 창) — seq 1–2 · S 10–12초 */
function unversionedWindow(): TrackWindow {
  const win = windowOf(10, [1, 2]);
  return { ...win, segs: win.segs.map((seg) => ({ ...seg, timing: null })) };
}

/** 목록 — 한 런(seq 1…) · gap 이면 그 seq 를 GAP 줄로 */
function playlistOf(seqs: readonly number[], gapSeq?: number): EditorPlaylist {
  const rows = seqs.map((seq) => ({
    ...segment(seq, BASE + (9 + seq) * 1000),
    gap: seq === gapSeq,
  }));
  return { runs: [rows.filter((row) => !row.gap)], rows };
}

const fetchedUrls = () => vi.mocked(fetch).mock.calls.map(([url]) => String(url));

/** 가짜 시계를 흘려 받기 · 고리를 진행시킨다 */
async function advance(ms: number) {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms);
  });
}

describe('useTrackPreview — ② 기다림 고리 · 끝 소리 확보', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.mocked(requestSegmentAudioAccess).mockResolvedValue(accessFor([1, 2, 3]));
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve(new Response(new Uint8Array(fixture)))),
    );
  });
  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
    vi.mocked(requestSegmentAudioAccess).mockReset();
    vi.restoreAllMocks();
    clearEditorMedia();
  });

  it('고리는 10초마다 · 30회(약 5분)까지 다시 묻는다', () => {
    expect(AUDIO_WAIT_MS).toBe(10_000);
    expect(AUDIO_WAIT_LIMIT).toBe(30);
  });

  it('창 seq 의 ② 행이 빠지면 10초 뒤 다시 묻고, 오면 그 조각만 받아 재생기를 새로 짓는다', async () => {
    vi.mocked(requestSegmentAudioAccess)
      .mockResolvedValueOnce(accessFor([1, 3]))
      .mockResolvedValue(accessFor([1, 2, 3]));
    const { ctx, gains } = fakeContext();
    const { result } = renderHook(() =>
      useTrackPreview({
        streamId: 's-1',
        window: THREE,
        loaded: loadedOf(THREE),
        video: video(),
        createContext: () => ctx as never,
      }),
    );
    await advance(50);
    expect(result.current.status).toBe('ready');
    expect(gains).toHaveLength(3);

    await advance(AUDIO_WAIT_MS - 100);
    expect(requestSegmentAudioAccess).toHaveBeenCalledTimes(1);
    await advance(150);
    expect(requestSegmentAudioAccess).toHaveBeenCalledTimes(2);
    // 받아 둔 조각(1 · 3)은 다시 받지 않는다
    expect(fetchedUrls().sort()).toEqual(
      ['https://s3/seg_1.m4s', 'https://s3/seg_2.m4s', 'https://s3/seg_3.m4s'].sort(),
    );
    expect(gains).toHaveLength(6);
    expect(result.current.status).toBe('ready');

    // 사유가 없으면 고리가 멈춘다
    await advance(AUDIO_WAIT_MS * 3);
    expect(requestSegmentAudioAccess).toHaveBeenCalledTimes(2);
  });

  it(`상한 ${AUDIO_WAIT_LIMIT}회에 멈추고 경고는 한 번 · 끝내 빠진 행이면 영상 소리로 물러나고 끝내 없는 머리는 failed 다`, async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const win = unversionedWindow();
    vi.mocked(requestSegmentAudioAccess).mockResolvedValue(accessFor([1]));
    const node = video();
    const { result } = renderHook(() =>
      useTrackPreview({
        streamId: 's-1',
        mediaKey: 'mk-cap',
        window: win,
        loaded: loadedOf(win),
        video: node,
        createContext: () => fakeContext().ctx as never,
      }),
    );
    act(() => result.current.setGains([0, 1, 0]));
    await advance(50);
    expect(result.current.status).toBe('ready');
    expect(node.muted).toBe(true);

    await advance(AUDIO_WAIT_MS * AUDIO_WAIT_LIMIT + 1000);

    expect(requestSegmentAudioAccess).toHaveBeenCalledTimes(1 + AUDIO_WAIT_LIMIT);
    const gaveUp = warn.mock.calls.filter(([message]) => String(message).includes('그만'));
    expect(gaveUp).toHaveLength(1);
    expect(result.current.status).toBe('unavailable');
    expect(node.muted).toBe(false);
    expect(headCacheFor('mk-cap').get(2)).toEqual({ state: 'failed' });

    await advance(AUDIO_WAIT_MS * 5);
    expect(requestSegmentAudioAccess).toHaveBeenCalledTimes(1 + AUDIO_WAIT_LIMIT);
  });

  it('판 NULL 창 seq 의 머리가 missing 이면 다시 판독한다 — 그 소리 주소는 트랙 다시 받기와 한 번만 묻는다', async () => {
    const win = unversionedWindow();
    await ensureHeads('mk-head', [], [2]);
    vi.mocked(requestSegmentAudioAccess).mockResolvedValue(accessFor([1, 2]));
    const { result } = renderHook(() =>
      useTrackPreview({
        streamId: 's-1',
        mediaKey: 'mk-head',
        window: win,
        loaded: loadedOf(win),
        video: video(),
        createContext: () => fakeContext().ctx as never,
      }),
    );
    await advance(50);
    expect(result.current.status).toBe('ready');
    expect(headCacheFor('mk-head').get(2)).toEqual({ state: 'missing' });

    await advance(AUDIO_WAIT_MS + 50);

    expect(requestSegmentAudioAccess).toHaveBeenCalledTimes(2);
    expect(headCacheFor('mk-head').get(2)?.state).toBe('ready');
    await advance(AUDIO_WAIT_MS * 3);
    expect(requestSegmentAudioAccess).toHaveBeenCalledTimes(2);
  });

  it('응답에 창 조각 행이 하나도 없으면 기다림이다: 음량을 바꿔 둔 편집본은 무음 · 못 부름으로 알리지 않는다(kty 결정 (나) c10b′ r1 5번)', async () => {
    vi.mocked(requestSegmentAudioAccess).mockResolvedValue(accessFor([]));
    const node = video();
    const { result } = renderHook(() =>
      useTrackPreview({
        streamId: 's-1',
        window: THREE,
        loaded: loadedOf(THREE),
        video: node,
        createContext: () => fakeContext().ctx as never,
      }),
    );
    act(() => result.current.setGains([0, 1, 0]));

    await advance(50);
    expect(result.current.status).toBe('waiting');
    expect(node.muted).toBe(true);
    await advance(AUDIO_WAIT_MS * 3);
    expect(result.current.status).toBe('waiting');
    expect(node.muted).toBe(true);

    // 행이 오면 그 소리로 튼다
    vi.mocked(requestSegmentAudioAccess).mockResolvedValue(accessFor([1, 2, 3]));
    await advance(AUDIO_WAIT_MS + 50);
    expect(result.current.status).toBe('ready');
  });

  it('응답에 창 조각 행이 하나도 없어도 음량이 영상 소리와 같으면 기다리는 동안 영상 소리를 그대로 둔다', async () => {
    vi.mocked(requestSegmentAudioAccess).mockResolvedValue(accessFor([]));
    const node = video();
    const { result } = renderHook(() =>
      useTrackPreview({
        streamId: 's-1',
        window: THREE,
        loaded: loadedOf(THREE),
        video: node,
        createContext: () => fakeContext().ctx as never,
      }),
    );
    act(() => result.current.setGains([1, 0, 0]));

    await advance(50);

    expect(result.current.status).toBe('waiting');
    expect(node.muted).toBe(false);
  });

  it(`응답에 행이 끝내 하나도 없으면 상한 ${AUDIO_WAIT_LIMIT}회에 영상 소리로 물러나 못 부름으로 알린다(경고 한 번)`, async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    vi.mocked(requestSegmentAudioAccess).mockResolvedValue(accessFor([]));
    const node = video();
    const { result } = renderHook(() =>
      useTrackPreview({
        streamId: 's-1',
        window: THREE,
        loaded: loadedOf(THREE),
        video: node,
        createContext: () => fakeContext().ctx as never,
      }),
    );
    act(() => result.current.setGains([0, 1, 0]));

    await advance(AUDIO_WAIT_MS * AUDIO_WAIT_LIMIT - 1000);
    expect(result.current.status).toBe('waiting');
    await advance(2000);

    expect(result.current.status).toBe('unavailable');
    expect(node.muted).toBe(false);
    expect(warn.mock.calls.filter(([message]) => String(message).includes('그만'))).toHaveLength(1);
  });

  it('고리는 failed 머리를 다시 읽지 않는다: 다시 판독은 missing · 아직 안 읽은 seq 만이다(c10b′ r1 3번 · 보안 M-1)', async () => {
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    const win = { ...THREE, segs: THREE.segs.map((seg) => ({ ...seg, timing: null })) };
    await ensureHeads('mk-failed', [], [2]);
    failMissingHeads('mk-failed', [2]);
    // seq 3 의 ② 행이 끝내 안 와 고리가 상한까지 돈다. seq 2 의 ② 머리는 읽으면 늘 실패한다(Range 404)
    vi.mocked(requestSegmentAudioAccess).mockResolvedValue(accessFor([1, 2]));
    vi.mocked(fetch).mockImplementation((url, init) =>
      Promise.resolve(
        String(url).endsWith('seg_2.m4s') && new Headers(init?.headers).has('Range')
          ? new Response(null, { status: 404 })
          : new Response(new Uint8Array(fixture)),
      ),
    );
    const headReads = () =>
      vi
        .mocked(fetch)
        .mock.calls.filter(
          ([url, init]) =>
            String(url).endsWith('seg_2.m4s') && new Headers(init?.headers).has('Range'),
        ).length;
    renderHook(() =>
      useTrackPreview({
        streamId: 's-1',
        mediaKey: 'mk-failed',
        window: win,
        loaded: loadedOf(win),
        video: video(),
        createContext: () => fakeContext().ctx as never,
      }),
    );

    await advance(AUDIO_WAIT_MS * AUDIO_WAIT_LIMIT + 1000);

    expect(requestSegmentAudioAccess).toHaveBeenCalledTimes(1 + AUDIO_WAIT_LIMIT);
    expect(headReads()).toBe(0);
    expect(headCacheFor('mk-failed').get(2)).toEqual({ state: 'failed' });
    // 아직 안 읽은 seq 1 은 고리가 읽는다
    expect(headCacheFor('mk-failed').get(1)?.state).toBe('ready');
  });

  it('확보가 not_ready 면 다시 묻고, attached 가 되면 ③ 머리 tfdt 만 읽어(창은 다시 싸지 않음) 확보 소리를 붙인다', async () => {
    vi.mocked(requestSegmentAudioAccess)
      .mockResolvedValueOnce(accessFor([1, 2, 3], { audioCapture: 'not_ready' }))
      .mockResolvedValue(
        accessFor([1, 2, 3], { audioCapture: 'attached', audioCaptureSegment: CAPTURE_4 }),
      );
    const { ctx, gains } = fakeContext();
    const { result } = renderHook(() =>
      useTrackPreview({
        streamId: 's-1',
        window: THREE,
        loaded: loadedOf(THREE),
        list: playlistOf([1, 2, 3, 4]),
        reloadList: vi.fn(async () => null),
        video: video(),
        createContext: () => ctx as never,
      }),
    );
    await advance(50);
    expect(result.current.status).toBe('ready');
    expect(fetchedUrls()).not.toContain('https://s3/seg_4.m4s');

    await advance(AUDIO_WAIT_MS + 50);

    // ③ 은 init(MAP) · 조각 앞부분만 Range 로 · 쿠키 자격(mediaCredentials)으로 읽는다
    const third = vi
      .mocked(fetch)
      .mock.calls.filter(([url]) => String(url).startsWith('https://cdn/'));
    expect(third.map(([url]) => String(url)).sort()).toEqual(
      ['https://cdn/init.mp4', 'https://cdn/seg/4.m4s'].sort(),
    );
    expect(third.map(([, init]) => init?.credentials)).toEqual(['same-origin', 'same-origin']);
    expect(third.map(([, init]) => new Headers(init?.headers).get('Range'))).toEqual([
      'bytes=0-65535',
      'bytes=0-65535',
    ]);
    expect(fetchedUrls()).toContain('https://s3/seg_4.m4s');
    expect(gains).toHaveLength(6);
    await advance(AUDIO_WAIT_MS * 3);
    expect(requestSegmentAudioAccess).toHaveBeenCalledTimes(2);
  });

  it('확보 seq 의 ③ 이 들고 있는 목록에 없으면 목록을 다시 받아 찾고, 그래도 없으면 기다린다', async () => {
    vi.mocked(requestSegmentAudioAccess).mockResolvedValue(
      accessFor([1, 2, 3], { audioCapture: 'attached', audioCaptureSegment: CAPTURE_4 }),
    );
    const reloadList = vi.fn(async () => playlistOf([1, 2, 3]));
    renderHook(() =>
      useTrackPreview({
        streamId: 's-1',
        window: THREE,
        loaded: loadedOf(THREE),
        list: playlistOf([1, 2, 3]),
        reloadList,
        video: video(),
        createContext: () => fakeContext().ctx as never,
      }),
    );
    await advance(50);
    expect(reloadList).toHaveBeenCalledTimes(1);
    expect(fetchedUrls()).not.toContain('https://s3/seg_4.m4s');

    // 다시 받은 목록에 나타나면 그 차례에 붙는다
    reloadList.mockResolvedValue(playlistOf([1, 2, 3, 4]));
    await advance(AUDIO_WAIT_MS + 50);
    expect(requestSegmentAudioAccess).toHaveBeenCalledTimes(2);
    expect(fetchedUrls()).toContain('https://s3/seg_4.m4s');
  });

  it('확보 seq 의 ③ 이 GAP 줄이면 확보를 포기하고 다시 묻지 않는다', async () => {
    vi.mocked(requestSegmentAudioAccess).mockResolvedValue(
      accessFor([1, 2, 3], { audioCapture: 'attached', audioCaptureSegment: CAPTURE_4 }),
    );
    renderHook(() =>
      useTrackPreview({
        streamId: 's-1',
        window: THREE,
        loaded: loadedOf(THREE),
        list: playlistOf([1, 2, 3, 4], 4),
        reloadList: vi.fn(async () => null),
        video: video(),
        createContext: () => fakeContext().ctx as never,
      }),
    );
    await advance(AUDIO_WAIT_MS * 3);

    expect(requestSegmentAudioAccess).toHaveBeenCalledTimes(1);
    expect(fetchedUrls().filter((url) => /seg[_/]4\./.test(url))).toEqual([]);
  });

  it.each([
    ['skipped(R28 — 확보 안 함 · 재조회 멈춤)', { audioCapture: 'skipped' }],
    ['칸 없음(PR-B2 전 clip)', {}],
  ])('확보가 %s 이고 빠진 것이 없으면 다시 묻지 않는다', async (_name, extra) => {
    vi.mocked(requestSegmentAudioAccess).mockResolvedValue(accessFor([1, 2, 3], extra));
    renderHook(() =>
      useTrackPreview({
        streamId: 's-1',
        window: THREE,
        loaded: loadedOf(THREE),
        list: playlistOf([1, 2, 3, 4]),
        reloadList: vi.fn(async () => null),
        video: video(),
        createContext: () => fakeContext().ctx as never,
      }),
    );
    await advance(AUDIO_WAIT_MS * 3);

    expect(requestSegmentAudioAccess).toHaveBeenCalledTimes(1);
  });

  it('창이 바뀌면 앞 창의 고리를 멈춘다', async () => {
    vi.mocked(requestSegmentAudioAccess).mockImplementation(async (_id, fromMs) =>
      fromMs === BASE + 10_000 ? accessFor([1]) : accessFor([7]),
    );
    const other = windowOf(16, [7]);
    const { rerender } = renderHook(
      ({ window }: { window: TrackWindow }) =>
        useTrackPreview({
          streamId: 's-1',
          window,
          loaded: loadedOf(window),
          video: video(),
          createContext: () => fakeContext().ctx as never,
        }),
      { initialProps: { window: THREE } },
    );
    await advance(50);
    rerender({ window: other });
    await advance(AUDIO_WAIT_MS * 3);

    const asked = vi.mocked(requestSegmentAudioAccess).mock.calls.map(([, fromMs]) => fromMs);
    expect(asked).toEqual([BASE + 10_000, BASE + 16_000]);
  });

  it('미리 받아 맡긴 소리가 확보를 기다리던 것이면 편집기가 이어서 다시 묻는다', async () => {
    segmentTracks.set(
      segmentTrackKey('s-1', 1, 3),
      cachedLoad([[{ startSeconds: 0, buffer: CACHED_BUFFER }]], { capture: 'waiting' }),
    );
    vi.mocked(requestSegmentAudioAccess).mockResolvedValue(accessFor([1, 2, 3]));
    const { result } = renderHook(() =>
      useTrackPreview({
        streamId: 's-1',
        window: THREE,
        loaded: loadedOf(THREE),
        video: video(),
        createContext: () => fakeContext().ctx as never,
      }),
    );
    await advance(50);
    expect(result.current.status).toBe('ready');
    expect(requestSegmentAudioAccess).not.toHaveBeenCalled();

    await advance(AUDIO_WAIT_MS + 50);
    expect(requestSegmentAudioAccess).toHaveBeenCalledTimes(1);
  });
});

// 편집기가 창 소리를 처음 받는 동안은 카드 미리 받기가 쉰다(M3 — 둘이 겹칠 때 메모리가 가장 높았다). 받기 끝 · 실패 · 화면
// 떠남에서 꼭 풀려야 카드 미리 받기가 영원히 멈추지 않는다. 고리의 다시 받기는 잡지 않는다(10초마다 카드 받기를 끊지 않게)
describe('useTrackPreview — 창 소리를 받는 동안 카드 미리 받기 쉬게 하기', () => {
  let seen: boolean[];
  let stopWatching: () => void;

  beforeEach(() => {
    vi.useFakeTimers();
    vi.mocked(requestSegmentAudioAccess).mockResolvedValue(accessFor([1, 2, 3]));
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve(new Response(new Uint8Array(fixture)))),
    );
    seen = [];
    stopWatching = watchEditorWindowFetch((fetching) => seen.push(fetching));
  });
  afterEach(() => {
    stopWatching();
    vi.useRealTimers();
    vi.unstubAllGlobals();
    vi.mocked(requestSegmentAudioAccess).mockReset();
    clearEditorMedia();
  });

  const mountPreview = (loaded: LoadedTrackWindow | null) =>
    renderHook(
      ({ loaded: current }: { loaded: LoadedTrackWindow | null }) =>
        useTrackPreview({
          streamId: 's-1',
          window: THREE,
          loaded: current,
          video: video(),
          createContext: () => fakeContext().ctx as never,
        }),
      { initialProps: { loaded } },
    );

  it('창 소리를 처음 받는 동안(닻 기다림 · 풀기 포함) 잡고, 놓아 ready 가 되면 놓는다', async () => {
    const { result, rerender } = mountPreview(null);
    await advance(50);
    expect(editorWindowFetching()).toBe(true);

    rerender({ loaded: loadedOf(THREE) });
    await advance(50);

    expect(result.current.status).toBe('ready');
    expect(editorWindowFetching()).toBe(false);
    expect(seen).toEqual([true, false]);
  });

  it('받다 실패해도 놓는다', async () => {
    vi.mocked(requestSegmentAudioAccess).mockRejectedValueOnce(new Error('503'));
    const { result } = mountPreview(loadedOf(THREE));
    await advance(50);

    expect(result.current.status).toBe('unavailable');
    expect(editorWindowFetching()).toBe(false);
  });

  it('받는 중에 화면을 떠나도 놓는다', async () => {
    const { unmount } = mountPreview(null);
    await advance(50);
    expect(editorWindowFetching()).toBe(true);

    unmount();

    expect(editorWindowFetching()).toBe(false);
  });

  it('카드가 미리 받아 둔 소리면 받지 않으므로 잡지 않는다', async () => {
    segmentTracks.set(
      segmentTrackKey('s-1', 1, 3),
      cachedLoad([[{ startSeconds: 0, buffer: CACHED_BUFFER }]]),
    );
    const { result } = mountPreview(loadedOf(THREE));
    await advance(50);

    expect(result.current.status).toBe('ready');
    expect(seen).toEqual([]);
  });

  it('② 기다림 고리의 다시 받기는 잡지 않는다: 10초마다 카드 미리 받기를 끊지 않는다', async () => {
    vi.mocked(requestSegmentAudioAccess)
      .mockResolvedValueOnce(accessFor([1, 3]))
      .mockResolvedValue(accessFor([1, 2, 3]));
    const { result } = mountPreview(loadedOf(THREE));
    await advance(50);
    expect(seen).toEqual([true, false]);

    await advance(AUDIO_WAIT_MS + 50);

    expect(requestSegmentAudioAccess).toHaveBeenCalledTimes(2);
    expect(result.current.status).toBe('ready');
    expect(seen).toEqual([true, false]);
  });
});

// 끊으면(바깥 신호 · 창 바뀜 · 시한) 안쪽 받기 · 풀기도 멈추고, 안쪽이 실제로 끝난 뒤에 거부한다 — 먼저 거부하면 쥔 쪽(카드
// 미리 받기의 차례)이 자리를 넘긴 뒤에도 안에서 남은 덩어리를 계속 풀어 옛 · 새 작업이 겹친다(메모리 r1 H2)
describe('loadSegmentTracks — 끊으면 안쪽이 멈춘 뒤에 끝난다', () => {
  beforeEach(() => {
    vi.mocked(requestSegmentAudioAccess).mockResolvedValue(accessFor([1]));
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve(new Response(new Uint8Array(fixture)))),
    );
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.mocked(requestSegmentAudioAccess).mockReset();
  });

  it('풀던 덩어리가 끝날 때까지 거부하지 않고, 남은 덩어리는 풀지 않는다', async () => {
    const stop = new AbortController();
    const finishing: (() => void)[] = [];
    const decode = vi.fn(
      (adts: Uint8Array) =>
        new Promise<AudioBuffer>((resolve) =>
          finishing.push(() => resolve(fakeDecoded(adts.buffer as ArrayBuffer))),
        ),
    );
    let settled = false;
    const load = loadSegmentTracks({
      streamId: 's-1',
      segs: windowOf(10, [1]).segs,
      anchors: () => Promise.resolve(new Map([[1, 0]])),
      decode,
      signal: stop.signal,
    }).finally(() => {
      settled = true;
    });
    load.catch(() => undefined);
    await waitFor(() => expect(decode).toHaveBeenCalled());
    const started = decode.mock.calls.length;

    stop.abort();
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(settled).toBe(false);

    finishing.splice(0).forEach((finish) => finish());
    await expect(load).rejects.toThrow('track audio load stopped');
    expect(decode).toHaveBeenCalledTimes(started);
  });

  it('닻(영상 창)을 기다리는 중에 끊어도 끝난다: 안쪽이 닻에 묶여 남지 않는다', async () => {
    const stop = new AbortController();
    const load = loadSegmentTracks({
      streamId: 's-1',
      segs: windowOf(10, [1]).segs,
      anchors: () => new Promise(() => undefined),
      decode: (adts) => Promise.resolve(fakeDecoded(adts.buffer as ArrayBuffer)),
      signal: stop.signal,
    });
    await waitFor(() => expect(fetch).toHaveBeenCalled());
    await new Promise((resolve) => setTimeout(resolve, 10));

    stop.abort();

    await expect(load).rejects.toThrow('track audio load stopped');
  });
});

describe('windowLevels', () => {
  /** 4초 덩어리(100Hz) — loudAt 초 자리 0.05초만 0.9, 나머지 0.1 */
  const piece = (startSeconds: number, loudAt: number): TrackPiece => {
    const data = new Float32Array(400).fill(0.1);
    data.fill(0.9, loudAt * 100, loudAt * 100 + 5);
    const buffer = {
      duration: 4,
      length: 400,
      sampleRate: 100,
      numberOfChannels: 1,
      getChannelData: () => data,
    } as unknown as AudioBuffer;
    return { startSeconds, buffer };
  };

  it('S 칸마다 그 칸의 Blob 구간을 잰다: PDT 0 → 7초 · Blob 0 → 4초 두 조각에서 봉우리가 참 S 자리에 서고 죽은 자리는 빈다', () => {
    // 조각 1: S 5000–5004 → Blob 0–4 · 조각 2: S 5007–5011 → Blob 4–8. S 5004–5007 은 죽은 자리(다음 조각 첫 화면 Blob 4)
    const blobAtS = (s: number) => {
      const r = s - 5000;
      if (r < 4) return Math.max(0, r);
      return r < 7 ? 4 : r - 3;
    };
    // 봉우리: 조각 1 의 1초(S 5001) · 조각 2 의 1초(Blob 5 = S 5008)
    const tracks = [[piece(0, 1), piece(4, 1)]];

    const levels = windowLevels(tracks, { blobAtS }, { startSeconds: 5000, endSeconds: 5011 });

    expect(levels.startSeconds).toBe(5000);
    expect(levels.stepSeconds).toBeCloseTo(0.05, 9);
    const cells = Array.from(levels.tracks[0]!);
    expect(cells).toHaveLength(220);
    const loud = cells.flatMap((level, i) => (level > 0.5 ? [5000 + i * levels.stepSeconds] : []));
    expect(loud.map((s) => Number(s.toFixed(2)))).toEqual([5001, 5008]);
    // 죽은 자리 S 5004–5007 = 칸 80–139 는 소리가 없다
    expect(cells.slice(80, 140).every((level) => level === 0)).toBe(true);
    expect(cells[79]).toBeCloseTo(0.1);
    expect(cells[140]).toBeCloseTo(0.1);
  });
});
