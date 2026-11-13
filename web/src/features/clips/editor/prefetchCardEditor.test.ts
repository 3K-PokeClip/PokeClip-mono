import { renderHook, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  fetchAllJumpCards,
  fetchBroadcast,
  requestSegmentAudioAccess,
  type BroadcastRow,
  type JumpCard,
} from '@/api/clipEditor';
import { jsonResponse } from '@/test/mockFetch';
import { useAuthStore } from '@/stores/auth';
import {
  clearEditorMedia,
  editorWindowKey,
  holdEditorWindowFetch,
  segmentTrackKey,
  segmentTracks,
  windowBlobs,
} from './editorMedia';
import {
  forgetPrefetchLists,
  prefetchCardEditor,
  PREFETCH_PARALLEL,
  PREFETCH_VIDEO_TIMEOUT_MS,
  type PrefetchDeps,
} from './prefetchCardEditor';
import type { EditorSourceState } from './source/editorSourceState';
import type { PlaylistSegment } from './source/editorPlaylist';
import type { RemuxedWindow } from './source/remuxWindow';
import type { WindowPlacement } from './source/windowPlacement';
import type { EditorSourceSnapshot, SourceList } from './source/useEditorSource';
import {
  SegmentsNotReadyError,
  windowAnchors,
  type TrackAudioLoad,
  type TrackPiece,
} from './trackAudio/loadTrackAudio';
import { useTrackPreview } from './trackAudio/useTrackPreview';
import { decideWindow, ensureWindowHeads, fetchEditorWindow } from './source/editorWindow';
import { headCacheFor } from './source/fileHeadProbe';
import { cardEndpoints, displayRange } from './source/rangeEndpoints';
import { useEditorVideoPlayback } from './useEditorVideoPlayback';

vi.mock('@/api/clipEditor', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/clipEditor')>()),
  requestSegmentAudioAccess: vi.fn(),
  fetchAllJumpCards: vi.fn(),
  fetchBroadcast: vi.fn(),
}));
// 원천 한 번 읽기 — 기본은 실물, 「나눠 쓰기」 시험이 답을 정한다
const actualSource = vi.hoisted(() => ({
  read: null as null | typeof import('./source/useEditorSource').readEditorSourceOnce,
}));
vi.mock('./source/useEditorSource', async (importOriginal) => {
  const actual = await importOriginal<typeof import('./source/useEditorSource')>();
  actualSource.read = actual.readEditorSourceOnce;
  return { ...actual, readEditorSourceOnce: vi.fn(actual.readEditorSourceOnce) };
});
vi.mock('./source/broadcastStreamer', () => ({
  streamerIdOf: vi.fn(async () => 7),
  editorXResolver: vi.fn(() => async () => ({ kind: 'known', id: '7' })),
}));
/** 다시 싸기 가짜 — 조각 seq 로 표를 짓는다(조각마다 4초 · 첫 DTS = (seq − 100)·4초) */
const remux = vi.hoisted(() => ({
  of: (seqs: readonly number[]): RemuxedWindow => {
    const segments = seqs.map((seq) => {
      const dts = (seq - 100) * 4;
      const frames = Array.from({ length: 8 }, (_, i) => ({ pts: dts + i * 0.5, dur: 0.5 }));
      return { seq, videoDts: dts, frames, mediaStart: dts, mediaEnd: dts + 4 };
    });
    return { blob: new Blob(['mp4']), shiftSeconds: segments[0]!.videoDts, segments };
  },
}));
vi.mock('./source/remuxWindow', () => ({
  remuxWindow: vi.fn(async (bytes: { segments: { seq: number }[] }) =>
    remux.of(bytes.segments.map((seg) => seg.seq)),
  ),
}));

const { readEditorSourceOnce } = await import('./source/useEditorSource');
const { streamerIdOf } = await import('./source/broadcastStreamer');

// 카드의 미리 받기(POK-499): 편집기가 처음 받는 영상 창 · 소리 조각을 같은 열쇠로 받아 두고, 편집기는 그것을 다시 받지 않는다.
// 영상 창은 ③ 되감기 목록에서 정한다(POK-473) — 녹화 재생 서버(/playback)는 부르지 않는다.

const STARTED = Date.parse('2026-10-29T01:00:00Z');
const BROADCAST: BroadcastRow = {
  streamId: '7',
  status: 'LIVE',
  relation: 'OWNER',
  startedAt: new Date(STARTED).toISOString(),
  endedAt: null,
  vodExpiresAt: null,
  timelineOriginAt: new Date(STARTED).toISOString(),
};
const ENDED: BroadcastRow = { ...BROADCAST, endedAt: new Date(STARTED + 600_000).toISOString() };
const CARD: JumpCard = {
  id: '23',
  streamId: '7',
  source: 'auto',
  streamTimestampMs: 60_000,
  window: { startMs: 50_000, endMs: 80_000 },
  score: 80,
  evidence: null,
  eventSeq: null,
  createdAt: new Date(STARTED + 80_000).toISOString(),
};
const DIR = 'https://media.example/dvr/7';
const LIST_URL = `${DIR}/index.m3u8`;

const range = (from: number, to: number) =>
  Array.from({ length: to - from + 1 }, (_, i) => from + i);

/** seq 100 부터 4초 조각 — PDT = STARTED + (seq − 100)·4초 */
function listOf(seqs: number[], mode: 'live' | 'vod' = 'live'): SourceList {
  const rows: PlaylistSegment[] = seqs.map((seq) => ({
    seq,
    uri: `${DIR}/seg/${String(seq).padStart(7, '0')}.m4s`,
    mapUri: `${DIR}/init/i.mp4`,
    pdtMs: STARTED + (seq - 100) * 4_000,
    durationMs: 4_000,
    timing: 'v2',
    gap: false,
  }));
  return { mode, url: LIST_URL, playlist: { runs: [rows], rows } };
}

function snapshotOf(
  list: SourceList | null,
  state: EditorSourceState = { kind: 'ready', mode: list?.mode ?? 'live', url: LIST_URL },
  base: number | null = STARTED,
): EditorSourceSnapshot {
  return { state, list, base };
}

/** 카드 창 50–80초 ± 여유 10초 = 40–90초 → 조각 110(40–44초) … 122(88–92초) */
const WINDOW_SEQS = range(110, 122);
const BUFFER = {
  duration: 52,
  length: 100,
  sampleRate: 100,
  numberOfChannels: 1,
  getChannelData: () => new Float32Array(100).fill(0.5),
} as unknown as AudioBuffer;
/** 창 Blob 시각 0–52초를 한 덩어리로 덮는 소리(창 40–92초 · Δ 40초) */
const TRACKS: TrackPiece[][] = [[{ startSeconds: 0, buffer: BUFFER }]];

/** 소리 받기 결과(TrackAudioLoad) — 빠진 행 · 확보 기다림 없이 */
const loadOf = (
  tracks: TrackPiece[][],
  extra: Partial<Pick<TrackAudioLoad, 'missingSeqs' | 'capture'>> = {},
): TrackAudioLoad => ({
  tracks,
  missingSeqs: [],
  capture: 'none',
  reuse: { segments: new Map(), capture: null, decoded: new Map() },
  ...extra,
});
const LOAD = loadOf(TRACKS);

function depsWith(overrides: Partial<PrefetchDeps> = {}): PrefetchDeps {
  return {
    fetchJumpCard: vi.fn(() => Promise.resolve(CARD)),
    fetchBroadcast: vi.fn(() => Promise.resolve(BROADCAST)),
    readSource: vi.fn(() => Promise.resolve(snapshotOf(listOf(range(100, 160))))),
    fetchEditorWindow: vi.fn((_key: string, segs: readonly PlaylistSegment[]) =>
      Promise.resolve(remux.of(segs.map((seg) => seg.seq))),
    ),
    ensureWindowHeads: vi.fn(() => Promise.resolve()),
    loadSegmentTracks: vi.fn(() => Promise.resolve(LOAD)),
    readFragmentVideoDts: vi.fn(() => Promise.resolve(0)),
    createDecoder: () => ({ decodeAudioData: vi.fn() }),
    ...overrides,
  };
}

const seqsOf = (segs: readonly PlaylistSegment[]) => segs.map((seg) => seg.seq);

describe('prefetchCardEditor', () => {
  beforeEach(() => {
    clearEditorMedia();
    forgetPrefetchLists();
  });

  it('목록이 구간 끝 + 여유까지 올라왔으면 영상 창 조각 · ② 머리 · 소리 조각을 받아 ready다', async () => {
    const deps = depsWith();
    await expect(prefetchCardEditor('7', '23', deps)).resolves.toBe('ready');
    const [key, segs] = vi.mocked(deps.fetchEditorWindow).mock.calls[0]!;
    expect(key).toBe('7');
    expect(seqsOf(segs)).toEqual(WINDOW_SEQS);
    expect(vi.mocked(deps.ensureWindowHeads).mock.calls[0]!.slice(0, 2)).toEqual(['7', '7']);
    expect(windowBlobs.get(editorWindowKey('7', 110, 122))).toBeDefined();
    // 소리는 창 조각(110–122)을 받아 그 창을 다시 싼 결과의 Blob 닻에 놓는다
    const [options] = vi.mocked(deps.loadSegmentTracks).mock.calls[0]!;
    expect(options.streamId).toBe('7');
    expect(seqsOf(options.segs as PlaylistSegment[])).toEqual(WINDOW_SEQS);
    await expect(options.anchors()).resolves.toEqual(windowAnchors(remux.of(WINDOW_SEQS)));
    // 맡긴 소리의 열쇠는 창 조각 seq 범위다(편집기의 미리 듣기가 같은 창에서 같은 열쇠를 낸다)
    expect(segmentTracks.get(segmentTrackKey('7', 110, 122))).toBe(LOAD);
  });

  it('편집기와 미리 받기는 같은 카드에서 같은 범위(cardEndpoints — estimate) · 같은 창 열쇠를 낸다', async () => {
    const list = listOf(range(100, 160));
    await prefetchCardEditor('7', '23', depsWith({ readSource: async () => snapshotOf(list) }));

    // 편집기 쪽(StudioScreen → useEditorVideoPlayback)과 같은 셈
    const editorRange = displayRange(cardEndpoints(CARD, STARTED), headCacheFor('7'), STARTED);
    const editor = decideWindow(editorRange, list, STARTED, '7', true);

    expect(editor.kind).toBe('window');
    expect(editor.kind === 'window' && windowBlobs.get(editor.target.key)).toBeTruthy();
  });

  it('clip 이 창 두 끝(playerWindow)을 주면 미리 받기도 그 범위로 받아 편집기 열쇠와 같다(판 NULL · 카드 창과 수 초 다름)', async () => {
    // 판 NULL 래칫으로 창 두 끝이 카드 창(50–80초)보다 7.3초 뒤다 → 여유 10초를 붙인 창 47.3–97.3초 = 조각 111–124
    const card: JumpCard = {
      ...CARD,
      playerWindow: { startMs: STARTED + 57_300, endMs: STARTED + 87_300 },
    };
    const live = listOf(range(100, 160));
    const rows = live.playlist.rows.map((row) => ({ ...row, timing: null }));
    const list: SourceList = { ...live, playlist: { runs: [rows], rows } };
    await prefetchCardEditor(
      '7',
      '23',
      depsWith({ fetchJumpCard: async () => card, readSource: async () => snapshotOf(list) }),
    );

    const editorRange = displayRange(cardEndpoints(card, STARTED), headCacheFor('7'), STARTED);
    const editor = decideWindow(editorRange, list, STARTED, '7', true);

    expect(editor.kind === 'window' && editor.target.key).toBe(editorWindowKey('7', 111, 124));
    expect(windowBlobs.get(editorWindowKey('7', 111, 124))).toBeDefined();
    // 카드 창(estimate)으로 받은 창은 편집기가 꺼내 쓰지 않는다 — 받지 않는다
    expect(windowBlobs.get(editorWindowKey('7', 110, 122))).toBeUndefined();
  });

  it('방송 중인데 목록이 구간 끝 + 여유에 못 미치면 받지 않고 pending이다(방금 생긴 카드)', async () => {
    // 목록 끝 84초 < 구간 끝 80초 + 여유 10초
    const deps = depsWith({
      readSource: vi.fn(() => Promise.resolve(snapshotOf(listOf(range(100, 120))))),
    });
    await expect(prefetchCardEditor('7', '23', deps)).resolves.toBe('pending');
    expect(deps.fetchEditorWindow).not.toHaveBeenCalled();
    expect(deps.loadSegmentTracks).not.toHaveBeenCalled();
  });

  it('방송 중에 목록을 아직 못 받았거나 원천이 서지 않았으면 pending이다', async () => {
    const noList = depsWith({ readSource: vi.fn(() => Promise.resolve(snapshotOf(null))) });
    await expect(prefetchCardEditor('7', '23', noList)).resolves.toBe('pending');
    forgetPrefetchLists();
    const checking = depsWith({
      readSource: vi.fn(() => Promise.resolve(snapshotOf(null, { kind: 'checking' }))),
    });
    await expect(prefetchCardEditor('7', '23', checking)).resolves.toBe('pending');
  });

  it('끝난 방송이 회차 정리 중이면(base 를 아직 못 정함) pending이다', async () => {
    const deps = depsWith({
      fetchBroadcast: vi.fn(() => Promise.resolve(ENDED)),
      readSource: vi.fn(() =>
        Promise.resolve(
          snapshotOf(
            null,
            { kind: 'finalizing', cause: 'round', under: { kind: 'checking' } },
            null,
          ),
        ),
      ),
    });
    await expect(prefetchCardEditor('7', '23', deps)).resolves.toBe('pending');
  });

  it('원천이 끝내 막혔으면(없음 · 만료 · 1시간 밖) 받지 않고 ready다: 기다려도 오지 않고 편집기가 알린다', async () => {
    for (const state of [{ kind: 'goneOrNone' }, { kind: 'expired' }] as EditorSourceState[]) {
      forgetPrefetchLists();
      const deps = depsWith({ readSource: vi.fn(() => Promise.resolve(snapshotOf(null, state))) });
      await expect(prefetchCardEditor('7', '23', deps)).resolves.toBe('ready');
      expect(deps.fetchEditorWindow).not.toHaveBeenCalled();
    }
    // 방송 중 카드 자리가 목록 첫 PDT 앞(최근 1시간 밖) — 편집기도 창을 받지 않는다
    const outside = depsWith({
      readSource: vi.fn(() => Promise.resolve(snapshotOf(listOf(range(130, 200))))),
    });
    await expect(prefetchCardEditor('7', '23', outside)).resolves.toBe('ready');
    expect(outside.fetchEditorWindow).not.toHaveBeenCalled();
  });

  it('되감기 제어면이 없는 환경이면 받을 것이 없어 ready다', async () => {
    const deps = depsWith({
      readSource: vi.fn(() => Promise.resolve(snapshotOf(null, { kind: 'unavailable' }))),
    });
    await expect(prefetchCardEditor('7', '23', deps)).resolves.toBe('ready');
  });

  it('끝난 방송이면 목록 끝에서 잘린 창도 그대로 받는다: 더 올라올 조각이 없다', async () => {
    const deps = depsWith({
      fetchBroadcast: vi.fn(() => Promise.resolve(ENDED)),
      readSource: vi.fn(() => Promise.resolve(snapshotOf(listOf(range(100, 120), 'vod')))),
    });
    await expect(prefetchCardEditor('7', '23', deps)).resolves.toBe('ready');
    expect(seqsOf(vi.mocked(deps.fetchEditorWindow).mock.calls[0]![1])).toEqual(range(110, 120));
  });

  it('영상이나 소리 하나라도 실패하면 던지고, 다시 부르면 처음부터 다시 받는다', async () => {
    const fetchWindow = vi
      .fn<PrefetchDeps['fetchEditorWindow']>()
      .mockRejectedValueOnce(new Error('500'))
      .mockResolvedValue(remux.of(WINDOW_SEQS));
    const deps = depsWith({ fetchEditorWindow: fetchWindow });
    await expect(prefetchCardEditor('7', '23', deps)).rejects.toThrow('500');
    await expect(prefetchCardEditor('7', '23', deps)).resolves.toBe('ready');
    expect(fetchWindow).toHaveBeenCalledTimes(2);
  });

  it('② 머리 판독이 끝나지 않아도 영상 시한에 끊고 나아간다: 머리는 편집기가 다시 읽는다', async () => {
    vi.useFakeTimers();
    try {
      const deps = depsWith({
        ensureWindowHeads: vi.fn(() => new Promise<void>(() => undefined)),
      });
      const result = prefetchCardEditor('7', '23', deps);
      const settled = expect(result).resolves.toBe('ready');
      await vi.advanceTimersByTimeAsync(PREFETCH_VIDEO_TIMEOUT_MS);
      await settled;
    } finally {
      vi.useRealTimers();
    }
  });

  it('소리 트랙을 하나도 못 꺼내면 실패다: 편집기도 소리를 못 부른다', async () => {
    const deps = depsWith({ loadSegmentTracks: vi.fn(() => Promise.resolve(loadOf([]))) });
    await expect(prefetchCardEditor('7', '23', deps)).rejects.toThrow();
  });

  it('영상 창 받기가 멈추면 시한에 끊고 실패한다: 받기 자리를 쥔 채 끝나지 않으면 「편집」이 실패로도 못 넘어간다', async () => {
    vi.useFakeTimers();
    try {
      const deps = depsWith({
        fetchEditorWindow: vi.fn(
          (_key: string, _segs: readonly PlaylistSegment[], signal?: AbortSignal) =>
            new Promise<RemuxedWindow>((_, reject) =>
              signal?.addEventListener('abort', () => reject(new Error('stopped'))),
            ),
        ),
      });
      const result = prefetchCardEditor('7', '23', deps);
      const settled = expect(result).rejects.toThrow('stopped');
      await vi.advanceTimersByTimeAsync(PREFETCH_VIDEO_TIMEOUT_MS);
      await settled;
    } finally {
      vi.useRealTimers();
    }
  });

  it('방송 중에 소리 조각이 덜 올라와 창을 다 못 덮으면 맡기지 않고 pending이다', async () => {
    // 40–92초 창(Blob 0–52초)에서 앞 20초만 왔다
    const partial: TrackPiece[][] = [[{ startSeconds: 0, buffer: { ...BUFFER, duration: 20 } }]];
    const deps = depsWith({ loadSegmentTracks: vi.fn(() => Promise.resolve(loadOf(partial))) });
    await expect(prefetchCardEditor('7', '23', deps)).resolves.toBe('pending');
    // 맡기지 않았으니 다음 확인에서 다시 받는다
    await expect(prefetchCardEditor('7', '23', deps)).resolves.toBe('pending');
    expect(deps.loadSegmentTracks).toHaveBeenCalledTimes(2);
  });

  it('조각 사이가 비어 덩어리가 둘이어도 덜 덮은 것이다', async () => {
    const split: TrackPiece[][] = [
      [
        { startSeconds: 0, buffer: { ...BUFFER, duration: 20 } },
        { startSeconds: 24, buffer: { ...BUFFER, duration: 28 } },
      ],
    ];
    const deps = depsWith({ loadSegmentTracks: vi.fn(() => Promise.resolve(loadOf(split))) });
    await expect(prefetchCardEditor('7', '23', deps)).resolves.toBe('pending');
  });

  it('겹침을 잘라 덩어리가 둘이어도 빈틈없이 이어 창을 덮으면 다 덮은 것이다: 맡기고 다시 받지 않는다(E9)', async () => {
    // 판 2 이음 겹침으로 앞 덩어리 꼬리를 프레임 경계에서 잘랐다 — 다음 덩어리는 앞 덩어리 끝(20초)에서 프레임 하나가 안 되는
    // 20ms 뒤에 붙는다(32kHz 프레임 32ms)
    const cut: TrackPiece[][] = [
      [
        { startSeconds: 0, buffer: { ...BUFFER, duration: 20 } },
        { startSeconds: 20.02, buffer: { ...BUFFER, duration: 31.98 } },
      ],
    ];
    const deps = depsWith({ loadSegmentTracks: vi.fn(() => Promise.resolve(loadOf(cut))) });
    await expect(prefetchCardEditor('7', '23', deps)).resolves.toBe('ready');
    await expect(prefetchCardEditor('7', '23', deps)).resolves.toBe('ready');
    expect(deps.loadSegmentTracks).toHaveBeenCalledTimes(1);
  });

  it('끝난 방송이면 덜 덮은 소리도 그대로 맡긴다: 더 올라올 조각이 없다', async () => {
    const partial: TrackPiece[][] = [[{ startSeconds: 0, buffer: { ...BUFFER, duration: 20 } }]];
    const deps = depsWith({
      fetchBroadcast: vi.fn(() => Promise.resolve(ENDED)),
      readSource: vi.fn(() => Promise.resolve(snapshotOf(listOf(range(100, 160), 'vod')))),
      loadSegmentTracks: vi.fn(() => Promise.resolve(loadOf(partial))),
    });
    await expect(prefetchCardEditor('7', '23', deps)).resolves.toBe('ready');
  });

  it('같은 카드를 받는 중에 또 부르면 그 받기를 같이 기다린다', async () => {
    const deps = depsWith();
    const [a, b] = [prefetchCardEditor('7', '23', deps), prefetchCardEditor('7', '23', deps)];
    expect(a).toBe(b);
    await a;
    expect(deps.fetchJumpCard).toHaveBeenCalledTimes(1);
  });

  describe('줄에서 빠지기', () => {
    function depsHeldBack() {
      let release = () => {};
      const held = new Promise<JumpCard>((resolve) => {
        release = () => resolve(CARD);
      });
      const fetchJumpCard = vi.fn((_streamId: string, _cardId: string) => held);
      const asked = () => fetchJumpCard.mock.calls.map(([, cardId]) => cardId);
      return { deps: depsWith({ fetchJumpCard }), fetchJumpCard, asked, release: () => release() };
    }

    it('차례를 기다리다 끊기면 받지 않고, 뒤 카드가 그 차례를 받는다', async () => {
      const { deps, fetchJumpCard, asked, release } = depsHeldBack();
      const running = Array.from({ length: PREFETCH_PARALLEL }, (_, i) =>
        prefetchCardEditor('7', `run-${i}`, deps),
      );
      const abandon = new AbortController();
      const leaving = prefetchCardEditor('7', 'leaving', deps, abandon.signal);
      const staying = prefetchCardEditor('7', 'staying', deps);
      await waitFor(() => expect(fetchJumpCard).toHaveBeenCalledTimes(PREFETCH_PARALLEL));

      abandon.abort();
      await expect(leaving).rejects.toMatchObject({ name: 'AbortError' });
      release();
      await Promise.all([...running, staying]);

      expect(asked()).toContain('staying');
      expect(asked()).not.toContain('leaving');
    });

    it('같이 기다리는 쪽이 남아 있으면 줄에서 빠지지 않는다', async () => {
      const { deps, fetchJumpCard, asked, release } = depsHeldBack();
      const running = Array.from({ length: PREFETCH_PARALLEL }, (_, i) =>
        prefetchCardEditor('7', `run-${i}`, deps),
      );
      const first = new AbortController();
      const second = new AbortController();
      void prefetchCardEditor('7', 'shared', deps, first.signal);
      const shared = prefetchCardEditor('7', 'shared', deps, second.signal);
      await waitFor(() => expect(fetchJumpCard).toHaveBeenCalledTimes(PREFETCH_PARALLEL));

      first.abort();
      release();

      await expect(shared).resolves.toBe('ready');
      await Promise.all(running);
      expect(asked()).toContain('shared');
    });

    it('이미 받기 시작했으면 끊어도 끝까지 받는다: 지금까지 받은 것을 버리지 않는다', async () => {
      const { deps, fetchJumpCard, release } = depsHeldBack();
      const abandon = new AbortController();
      const started = prefetchCardEditor('7', 'started', deps, abandon.signal);
      await waitFor(() => expect(fetchJumpCard).toHaveBeenCalled());

      abandon.abort();
      release();

      await expect(started).resolves.toBe('ready');
    });
  });

  it(`한 번에 ${PREFETCH_PARALLEL}장까지만 받고 나머지는 줄을 선다`, async () => {
    const hold: (() => void)[] = [];
    const deps = depsWith({
      fetchJumpCard: vi.fn(
        () => new Promise<JumpCard>((resolve) => hold.push(() => resolve(CARD))),
      ),
    });
    const all = ['1', '2', '3', '4'].map((id) => prefetchCardEditor('7', id, deps));
    await waitFor(() => expect(deps.fetchJumpCard).toHaveBeenCalledTimes(PREFETCH_PARALLEL));
    hold.shift()!();
    await waitFor(() => expect(deps.fetchJumpCard).toHaveBeenCalledTimes(PREFETCH_PARALLEL + 1));
    // 남은 줄을 다 풀어 끝낸다
    await waitFor(() => {
      hold.splice(0).forEach((release) => release());
      expect(deps.fetchJumpCard).toHaveBeenCalledTimes(4);
    });
    hold.splice(0).forEach((release) => release());
    await Promise.all(all);
  });
});

// 편집기가 창(영상 · 소리)을 받는 동안은 카드 미리 받기가 쉰다(M3 — 둘이 겹칠 때 메모리가 가장 높았다). 새 카드는 줄에서
// 기다리고, 받던 카드는 끊어(abort) 줄 앞에 다시 선다 — 반쯤 받은 것은 맡기지 않는다. 편집기가 놓으면 다시 흐른다
describe('편집기가 창을 받는 동안 카드 미리 받기는 쉰다', () => {
  const holds: (() => void)[] = [];
  const holdFetch = () => {
    const release = holdEditorWindowFetch();
    holds.push(release);
    return release;
  };

  beforeEach(() => {
    clearEditorMedia();
    forgetPrefetchLists();
  });
  afterEach(() => {
    holds.splice(0).forEach((release) => release());
  });

  /** 카드 id 마다 20초씩 뒤의 카드(창이 서로 다르다) */
  const cardOf = (_streamId: string, cardId: string): Promise<JumpCard> => {
    const shift = Number(cardId) * 20_000;
    return Promise.resolve({
      ...CARD,
      id: cardId,
      streamTimestampMs: CARD.streamTimestampMs + shift,
      window: { startMs: CARD.window.startMs + shift, endMs: CARD.window.endMs + shift },
    });
  };

  /** 영상 창 받기 가짜 — 끝내기 전까지 걸려 있고, 신호가 끊으면 AbortError 로 끝난다. 동시에 받는 수를 잰다 */
  function heldVideo() {
    const pending: { signal: AbortSignal | undefined; finish: () => void }[] = [];
    let running = 0;
    let peak = 0;
    const fetchEditorWindow = vi.fn(
      (_key: string, segs: readonly PlaylistSegment[], signal?: AbortSignal) =>
        new Promise<RemuxedWindow>((resolve, reject) => {
          let settled = false;
          const settle = () => {
            if (settled) return false;
            settled = true;
            running -= 1;
            return true;
          };
          running += 1;
          peak = Math.max(peak, running);
          pending.push({
            signal,
            finish: () => {
              if (settle()) resolve(remux.of(seqsOf(segs)));
            },
          });
          signal?.addEventListener(
            'abort',
            () => {
              if (settle()) reject(new DOMException('aborted', 'AbortError'));
            },
            { once: true },
          );
        }),
    );
    return {
      fetchEditorWindow,
      pending,
      running: () => running,
      peak: () => peak,
      finishAll: () => pending.splice(0).forEach((entry) => entry.finish()),
    };
  }

  it('편집기가 받는 동안 새 카드는 시작하지 않고, 놓으면 시작한다', async () => {
    const deps = depsWith();
    const release = holdFetch();

    const result = prefetchCardEditor('7', '23', deps);
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(deps.fetchJumpCard).not.toHaveBeenCalled();

    release();
    await expect(result).resolves.toBe('ready');
    expect(deps.fetchJumpCard).toHaveBeenCalledTimes(1);
  });

  it('받던 카드는 끊고 반쯤 받은 것은 맡기지 않으며, 놓으면 처음부터 다시 받는다', async () => {
    const video = heldVideo();
    const audioSignals: (AbortSignal | undefined)[] = [];
    const deps = depsWith({
      fetchEditorWindow: video.fetchEditorWindow,
      loadSegmentTracks: vi.fn((options) => {
        audioSignals.push(options.signal);
        return Promise.resolve(LOAD);
      }),
    });
    const result = prefetchCardEditor('7', '23', deps);
    await waitFor(() => expect(video.pending).toHaveLength(1));
    const [first] = video.pending;

    const release = holdFetch();
    expect(first!.signal?.aborted).toBe(true);
    expect(audioSignals[0]?.aborted).toBe(true);
    await waitFor(() => expect(video.running()).toBe(0));
    expect(windowBlobs.get(editorWindowKey('7', 110, 122))).toBeUndefined();
    expect(segmentTracks.get(segmentTrackKey('7', 110, 122))).toBeUndefined();
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(video.fetchEditorWindow).toHaveBeenCalledTimes(1);

    release();
    await waitFor(() => expect(video.fetchEditorWindow).toHaveBeenCalledTimes(2));
    video.finishAll();
    await expect(result).resolves.toBe('ready');
    expect(segmentTracks.get(segmentTrackKey('7', 110, 122))).toBe(LOAD);
  });

  it('같은 카드를 기다리는 쪽들은 끊겼다 다시 받은 결과를 함께 받는다', async () => {
    const video = heldVideo();
    const deps = depsWith({ fetchEditorWindow: video.fetchEditorWindow });
    const a = prefetchCardEditor('7', '23', deps);
    await waitFor(() => expect(video.pending).toHaveLength(1));

    const release = holdFetch();
    const b = prefetchCardEditor('7', '23', deps);
    expect(b).toBe(a);
    release();
    await waitFor(() => expect(video.pending).toHaveLength(2));
    video.finishAll();

    await expect(Promise.all([a, b])).resolves.toEqual(['ready', 'ready']);
    expect(deps.fetchJumpCard).toHaveBeenCalledTimes(2);
  });

  it('기다리는 쪽이 모두 떠난 받기는 끊기면 다시 받지 않는다', async () => {
    const video = heldVideo();
    const deps = depsWith({ fetchEditorWindow: video.fetchEditorWindow });
    const abandon = new AbortController();
    const result = prefetchCardEditor('7', '23', deps, abandon.signal);
    await waitFor(() => expect(video.pending).toHaveLength(1));
    abandon.abort();

    const release = holdFetch();
    await expect(result).rejects.toMatchObject({ name: 'AbortError' });
    release();
    await new Promise((resolve) => setTimeout(resolve, 20));

    expect(video.fetchEditorWindow).toHaveBeenCalledTimes(1);
  });

  it(`놓은 뒤에도 한 번에 ${PREFETCH_PARALLEL}장까지만 받는다`, async () => {
    const video = heldVideo();
    const deps = depsWith({
      fetchJumpCard: vi.fn(cardOf),
      fetchEditorWindow: video.fetchEditorWindow,
    });
    const all = ['0', '1', '2', '3', '4'].map((id) => prefetchCardEditor('7', id, deps));
    await waitFor(() => expect(video.running()).toBe(PREFETCH_PARALLEL));

    const release = holdFetch();
    await waitFor(() => expect(video.running()).toBe(0));
    release();
    await waitFor(() => expect(video.running()).toBe(PREFETCH_PARALLEL));
    await waitFor(() => {
      video.finishAll();
      expect(deps.fetchEditorWindow).toHaveBeenCalledTimes(PREFETCH_PARALLEL + 5);
    });
    video.finishAll();

    await expect(Promise.all(all)).resolves.toEqual(Array(5).fill('ready'));
    expect(video.peak()).toBe(PREFETCH_PARALLEL);
  });

  it('놓자마자 같은 틱에 다시 잡으면(StrictMode 정리 → 다시 붙기) 깨운 받기도 시작하지 않는다', async () => {
    const deps = depsWith();
    const first = holdFetch();
    const result = prefetchCardEditor('7', '23', deps);

    first();
    const second = holdFetch();
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(deps.fetchJumpCard).not.toHaveBeenCalled();
    expect(deps.fetchEditorWindow).not.toHaveBeenCalled();

    second();
    await expect(result).resolves.toBe('ready');
  });

  it('차례를 받자마자 같은 틱에 편집기가 잡아도 받기를 시작하지 않는다', async () => {
    const deps = depsWith();
    const result = prefetchCardEditor('7', '23', deps);
    const release = holdFetch();

    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(deps.fetchJumpCard).not.toHaveBeenCalled();
    expect(deps.fetchEditorWindow).not.toHaveBeenCalled();

    release();
    await expect(result).resolves.toBe('ready');
  });

  it('끊긴 받기는 안쪽 소리 작업이 실제로 끝난 뒤에 자리를 돌려준다: 옛 · 새 작업이 겹치지 않는다', async () => {
    const video = heldVideo();
    let settleOld = () => {};
    let audioCalls = 0;
    const deps = depsWith({
      fetchEditorWindow: video.fetchEditorWindow,
      // 첫 소리 받기는 풀던 덩어리가 끝나야(끊긴 뒤에도) 끝난다 — decodeAudioData 는 중간에 못 멈춘다
      loadSegmentTracks: vi.fn((options) => {
        audioCalls += 1;
        if (audioCalls > 1) return Promise.resolve(LOAD);
        return new Promise<TrackAudioLoad>((_, reject) => {
          settleOld = () => reject(options.signal!.reason);
        });
      }),
    });
    const result = prefetchCardEditor('7', '23', deps);
    await waitFor(() => expect(video.pending).toHaveLength(1));

    holdFetch()();
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(video.fetchEditorWindow).toHaveBeenCalledTimes(1);

    settleOld();
    await waitFor(() => expect(video.fetchEditorWindow).toHaveBeenCalledTimes(2));
    video.finishAll();
    await expect(result).resolves.toBe('ready');
  });

  it('편집기가 받다 실패하거나 화면을 떠나 놓아도 다시 흐른다: 잡은 쪽이 모두 놓을 때까지만 쉰다', async () => {
    const deps = depsWith();
    const failed = holdFetch();
    const left = holdFetch();
    const result = prefetchCardEditor('7', '23', deps);

    failed();
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(deps.fetchJumpCard).not.toHaveBeenCalled();
    left();

    await expect(result).resolves.toBe('ready');
  });
});

describe('미리 받은 카드를 편집기가 열면 다시 받지 않는다', () => {
  const initialRange = { startSeconds: 50, endSeconds: 80 };
  let fetchSpy: ReturnType<typeof vi.fn>;

  beforeEach(async () => {
    clearEditorMedia();
    forgetPrefetchLists();
    await prefetchCardEditor('7', '23', depsWith());
    fetchSpy = vi.fn(() => new Promise<Response>(() => undefined));
    vi.stubGlobal('fetch', fetchSpy);
    vi.stubGlobal(
      'URL',
      Object.assign(URL, {
        createObjectURL: vi.fn(() => 'blob:prefetched'),
        revokeObjectURL: vi.fn(),
      }),
    );
  });
  afterEach(() => vi.unstubAllGlobals());

  it('영상 창', async () => {
    const list = listOf(range(100, 160));
    const { result } = renderHook(() =>
      useEditorVideoPlayback({
        streamId: '7',
        mediaKey: '7',
        base: STARTED,
        source: {
          state: { kind: 'ready', mode: 'live', url: LIST_URL },
          list,
          listFailed: false,
          reloadList: vi.fn(async () => list.playlist),
          reportSegmentFailure: vi.fn(async () => undefined),
        },
        initialRange,
      }),
    );
    await waitFor(() => expect(result.current.src).toBe('blob:prefetched'));
    expect(fetchSpy).not.toHaveBeenCalled();
    expect(result.current.window).toEqual({ startSeconds: 40, endSeconds: 92 });
  });

  it('소리 조각 · 파형', async () => {
    const { result } = renderHook(() =>
      useTrackPreview({
        streamId: '7',
        window: {
          key: editorWindowKey('7', 110, 122),
          segs: listOf(WINDOW_SEQS).playlist.rows,
          startSeconds: 40,
          endSeconds: 92,
        },
        // 편집기 영상이 같은 창을 받았다(창 S 40초 = Blob 0초)
        loaded: {
          key: editorWindowKey('7', 110, 122),
          remuxed: remux.of(WINDOW_SEQS),
          placement: { blobAtS: (s: number) => s - 40 } as unknown as WindowPlacement,
        },
        video: null,
        createContext: () =>
          ({
            currentTime: 0,
            state: 'running',
            destination: {},
            resume: vi.fn(),
            close: vi.fn(() => Promise.resolve()),
            createGain: () => ({ gain: { value: 1 }, connect: vi.fn(), disconnect: vi.fn() }),
          }) as unknown as ReturnType<
            NonNullable<Parameters<typeof useTrackPreview>[0]['createContext']>
          >,
      }),
    );
    await waitFor(() => expect(result.current.status).toBe('ready'));
    expect(result.current.levels).not.toBeNull();
    expect(requestSegmentAudioAccess).not.toHaveBeenCalled();
    expect(fetchSpy).not.toHaveBeenCalled();
  });
});

describe('미리 받기는 녹화 재생 서버(/playback)를 부르지 않는다', () => {
  const CONTROL = 'https://media.example';
  const VOD_LIST = `${CONTROL}/vod/7/7/index.m3u8`;

  beforeEach(() => {
    clearEditorMedia();
    forgetPrefetchLists();
    vi.stubEnv('NEXT_PUBLIC_MEDIA_CONTROL_BASE_URL', CONTROL);
  });
  afterEach(() => {
    vi.unstubAllEnvs();
    vi.unstubAllGlobals();
  });

  it('fetch 가짜로 원천 · 목록 · 조각을 실제로 받아도 /playback/ 요청은 0이다', async () => {
    const rows = range(100, 160).map(
      (seq, i) =>
        `#EXT-X-PROGRAM-DATE-TIME:${new Date(STARTED + i * 4_000).toISOString()}\n#EXTINF:4.000,\n` +
        `${CONTROL}/vod/7/7/seg/${String(seq).padStart(7, '0')}.m4s`,
    );
    const playlist = [
      '#EXTM3U',
      '#EXT-X-VERSION:6',
      `#EXT-X-MAP:URI="${CONTROL}/vod/7/7/init/i.mp4"`,
      ...rows,
      '#EXT-X-ENDLIST',
      '',
    ].join('\n');
    const fetchSpy = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.endsWith('/rounds/7/rewind')) {
        return jsonResponse(200, {
          ready: true,
          mode: 'vod',
          url: VOD_LIST,
          firstPdt: new Date(STARTED).toISOString(),
          headPdt: new Date(STARTED + 244_000).toISOString(),
        });
      }
      if (url === VOD_LIST) return new Response(playlist, { status: 200 });
      return new Response(new Uint8Array([1]), { status: 200 });
    });
    vi.stubGlobal('fetch', fetchSpy);
    const deps = depsWith({
      fetchBroadcast: vi.fn(() => Promise.resolve(ENDED)),
      readSource: (broadcast) =>
        actualSource.read!(broadcast, async () => ({ kind: 'known', id: '7' })),
      fetchEditorWindow,
      ensureWindowHeads,
    });

    await expect(prefetchCardEditor('7', '23', deps)).resolves.toBe('ready');

    const urls = fetchSpy.mock.calls.map(([url]) => String(url));
    expect(urls.filter((url) => url.endsWith('.m4s'))).toHaveLength(WINDOW_SEQS.length);
    expect(urls.filter((url) => url.includes('/playback/'))).toEqual([]);
  });
});

describe('조각 목록만 보고 판정하기', () => {
  beforeEach(() => {
    clearEditorMedia();
    forgetPrefetchLists();
  });

  /** 40–92초 창(조각 110–122)을 4초 조각으로: 앞의 count개만 올라왔다. pdtStepMs 로 조각 사이 PDT 간격을 바꾼다 */
  const segmentsUpTo = (count: number, pdtStepMs = 4_000) =>
    WINDOW_SEQS.slice(0, count).map((seq, i) => ({
      seq,
      startAtMs: STARTED + 40_000 + i * pdtStepMs,
      durationMs: 4_000,
      url: `https://s3/${seq}`,
    }));

  function depsAskingList(segments: ReturnType<typeof segmentsUpTo>) {
    const body = vi.fn();
    const loadSegmentTracks = vi.fn(
      async (options: Parameters<PrefetchDeps['loadSegmentTracks']>[0]) => {
        if (options.precheck && !options.precheck(segments)) throw new SegmentsNotReadyError();
        body();
        return LOAD;
      },
    );
    return { body, deps: depsWith({ loadSegmentTracks }) };
  }

  it('방송 중에 목록이 구간을 덜 덮으면 본문을 받지 않고 pending이다', async () => {
    const { body, deps } = depsAskingList(segmentsUpTo(5));

    await expect(prefetchCardEditor('7', '23', deps)).resolves.toBe('pending');
    await expect(prefetchCardEditor('7', '23', deps)).resolves.toBe('pending');

    expect(body).not.toHaveBeenCalled();
  });

  it('창 조각 하나라도 응답에 없으면(② 미업로드) 덜 덮은 것이다', async () => {
    const gappy = segmentsUpTo(13).filter((segment) => segment.seq !== 115);
    const { body, deps } = depsAskingList(gappy);

    await expect(prefetchCardEditor('7', '23', deps)).resolves.toBe('pending');
    expect(body).not.toHaveBeenCalled();
  });

  it('창 밖 이웃 조각이 같이 와도 창 조각이 빠졌으면 덜 덮은 것이다', async () => {
    const neighbor = {
      seq: 123,
      startAtMs: STARTED + 92_000,
      durationMs: 4_000,
      url: 'https://s3/123',
    };
    const { body, deps } = depsAskingList([
      ...segmentsUpTo(13).filter((segment) => segment.seq !== 122),
      neighbor,
    ]);

    await expect(prefetchCardEditor('7', '23', deps)).resolves.toBe('pending');
    expect(body).not.toHaveBeenCalled();
  });

  it('목록이 구간을 다 덮으면 본문을 받아 ready다', async () => {
    const { body, deps } = depsAskingList(segmentsUpTo(13));

    await expect(prefetchCardEditor('7', '23', deps)).resolves.toBe('ready');
    expect(body).toHaveBeenCalledTimes(1);
  });

  it('판 NULL 래칫으로 조각 사이 PDT 가 벌어져도(조각마다 +200ms) 창 조각이 다 올라왔으면 다 덮은 것이다', async () => {
    const { body, deps } = depsAskingList(segmentsUpTo(13, 4_200));

    await expect(prefetchCardEditor('7', '23', deps)).resolves.toBe('ready');
    expect(body).toHaveBeenCalledTimes(1);
  });

  it('판 2 건너뜀으로 PDT 가 한 번 3초 뛰어도 창 조각이 다 올라왔으면 다 덮은 것이다', async () => {
    const jumped = segmentsUpTo(13).map((segment) =>
      segment.seq >= 116 ? { ...segment, startAtMs: segment.startAtMs + 3_000 } : segment,
    );
    const { body, deps } = depsAskingList(jumped);

    await expect(prefetchCardEditor('7', '23', deps)).resolves.toBe('ready');
    expect(body).toHaveBeenCalledTimes(1);
  });

  it('끝난 방송은 미리 판정하지 않는다: 덜 덮어도 예전처럼 받는다', async () => {
    const { body, deps } = depsAskingList(segmentsUpTo(5));
    vi.mocked(deps.fetchBroadcast).mockResolvedValue(ENDED);
    vi.mocked(deps.readSource).mockResolvedValue(snapshotOf(listOf(range(100, 160), 'vod')));

    await expect(prefetchCardEditor('7', '23', deps)).resolves.toBe('ready');
    expect(body).toHaveBeenCalledTimes(1);
    expect(vi.mocked(deps.loadSegmentTracks).mock.calls[0]![0].precheck).toBeUndefined();
  });
});

describe('미리 받기의 끝 소리 확보(설계 prw2 7절 c10b′)', () => {
  beforeEach(() => {
    clearEditorMedia();
    forgetPrefetchLists();
  });

  it('확보 닻은 원천 목록에서 확보 seq 의 ③ 을 찾아 그 머리 tfdt − Δ 다(창은 다시 싸지 않는다)', async () => {
    const readFragmentVideoDts = vi.fn(() => Promise.resolve(52.5));
    const deps = depsWith({ readFragmentVideoDts });
    await prefetchCardEditor('7', '23', deps);

    const [options] = vi.mocked(deps.loadSegmentTracks).mock.calls[0]!;
    // 창 110–122 · Δ = 조각 110 의 DTS 40초 — 123 의 ③ 머리 52.5초는 Blob 12.5초다
    await expect(options.captureAnchor!(123)).resolves.toBe(12.5);
    expect(readFragmentVideoDts).toHaveBeenCalledWith(
      expect.objectContaining({ seq: 123, uri: `${DIR}/seg/0000123.m4s` }),
    );
    expect(deps.fetchEditorWindow).toHaveBeenCalledTimes(1);
  });

  it('확보 seq 의 ③ 이 목록에 아직 없으면 기다림이다: 맡긴 소리가 기다림을 들고 가 편집기가 이어 묻는다', async () => {
    const waiting = loadOf(TRACKS, { capture: 'waiting' });
    const deps = depsWith({
      readSource: vi.fn(() => Promise.resolve(snapshotOf(listOf(range(100, 122))))),
      loadSegmentTracks: vi.fn(() => Promise.resolve(waiting)),
    });
    // 목록 끝 92초 = 구간 끝 + 여유 90초를 넘는다(창이 굳는다)
    await expect(prefetchCardEditor('7', '23', deps)).resolves.toBe('ready');

    const [options] = vi.mocked(deps.loadSegmentTracks).mock.calls[0]!;
    await expect(options.captureAnchor!(123)).resolves.toBe('wait');
    expect(deps.readFragmentVideoDts).not.toHaveBeenCalled();
    expect(segmentTracks.get(segmentTrackKey('7', 110, 122))).toBe(waiting);
  });
});

describe('카드 목록 · 원천 나눠 쓰기', () => {
  const CARD_B: JumpCard = { ...CARD, id: '24' };
  const CARD_NEW: JumpCard = { ...CARD, id: '99' };

  beforeEach(() => {
    clearEditorMedia();
    forgetPrefetchLists();
    vi.mocked(fetchAllJumpCards).mockReset().mockResolvedValue([CARD, CARD_B]);
    vi.mocked(fetchBroadcast).mockReset().mockResolvedValue(BROADCAST);
    vi.mocked(streamerIdOf).mockClear();
    // 목록이 구간 끝에 못 미쳐 pending에서 멈춘다: 영상 · 소리는 받지 않고 목록 읽기만 본다
    vi.mocked(readEditorSourceOnce)
      .mockReset()
      .mockResolvedValue(snapshotOf(listOf(range(100, 120))));
  });

  afterEach(() => vi.useRealTimers());

  it('같은 방송의 카드 여럿이 카드 목록과 원천을 한 번씩만 읽고, 채널 번호도 한 번만 정한다', async () => {
    await Promise.all([prefetchCardEditor('7', '23'), prefetchCardEditor('7', '24')]);

    expect(fetchAllJumpCards).toHaveBeenCalledTimes(1);
    expect(readEditorSourceOnce).toHaveBeenCalledTimes(1);
    expect(streamerIdOf).toHaveBeenCalledTimes(1);
  });

  it('목록에 없는 카드는 낡은 목록일 수 있어 한 번 다시 읽고 찾는다: 방송 중 새로 생긴 카드', async () => {
    await prefetchCardEditor('7', '23');
    vi.mocked(fetchAllJumpCards).mockResolvedValue([CARD, CARD_B, CARD_NEW]);

    await expect(prefetchCardEditor('7', '99')).resolves.toBe('pending');
    expect(fetchAllJumpCards).toHaveBeenCalledTimes(2);
  });

  it('다시 읽어도 없는 카드는 없는 카드다: 읽기는 한 번만 더 간다', async () => {
    await prefetchCardEditor('7', '23');

    await expect(prefetchCardEditor('7', 'nope')).rejects.toThrow('not found');
    expect(fetchAllJumpCards).toHaveBeenCalledTimes(2);
  });

  it('방금 읽은 목록에 없는 카드는 곧바로 없다고 한다: 같은 순간에 두 번 읽지 않는다', async () => {
    await expect(prefetchCardEditor('7', 'nope')).rejects.toThrow('not found');
    expect(fetchAllJumpCards).toHaveBeenCalledTimes(1);
  });

  it('카드 목록은 30초 뒤 다시 읽는다', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    await prefetchCardEditor('7', '23');
    vi.advanceTimersByTime(29_000);
    await prefetchCardEditor('7', '24');
    expect(fetchAllJumpCards).toHaveBeenCalledTimes(1);

    vi.advanceTimersByTime(2_000);
    await prefetchCardEditor('7', '23');
    expect(fetchAllJumpCards).toHaveBeenCalledTimes(2);
  });

  it('방송 중 원천(③ 목록)은 4초만 쓴다: 편집기가 목록을 다시 받는 간격과 같다', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    await prefetchCardEditor('7', '23');
    vi.advanceTimersByTime(3_000);
    await prefetchCardEditor('7', '24');
    expect(readEditorSourceOnce).toHaveBeenCalledTimes(1);

    vi.advanceTimersByTime(2_000);
    await prefetchCardEditor('7', '23');
    expect(readEditorSourceOnce).toHaveBeenCalledTimes(2);
  });

  it('끝난 방송의 원천은 30초 쓴다', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.mocked(fetchBroadcast).mockResolvedValue(ENDED);
    vi.mocked(readEditorSourceOnce).mockResolvedValue(snapshotOf(null, { kind: 'checking' }));
    await prefetchCardEditor('7', '23');
    vi.advanceTimersByTime(29_000);
    await prefetchCardEditor('7', '24');
    expect(readEditorSourceOnce).toHaveBeenCalledTimes(1);
  });

  it('읽기가 실패하면 기억하지 않는다: 다음 부름이 다시 읽는다', async () => {
    vi.mocked(fetchAllJumpCards).mockRejectedValueOnce(new Error('500'));

    await expect(prefetchCardEditor('7', '23')).rejects.toThrow('500');
    await expect(prefetchCardEditor('7', '23')).resolves.toBe('pending');
    expect(fetchAllJumpCards).toHaveBeenCalledTimes(2);
  });

  it('계정이 바뀌면 비운다', async () => {
    await prefetchCardEditor('7', '23');
    useAuthStore.getState().clearTokens();

    await prefetchCardEditor('7', '24');

    expect(fetchAllJumpCards).toHaveBeenCalledTimes(2);
    expect(readEditorSourceOnce).toHaveBeenCalledTimes(2);
    expect(streamerIdOf).toHaveBeenCalledTimes(2);
  });
});
