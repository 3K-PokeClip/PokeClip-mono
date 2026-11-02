import { renderHook, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  fetchAllJumpCards,
  fetchBroadcast,
  requestSegmentAudioAccess,
  type BroadcastRow,
  type JumpCard,
} from '@/api/clipEditor';
import { useAuthStore } from '@/stores/auth';
import type { RecordingTimeline } from '@/features/player/recordingTimeline';
import { clearEditorMedia, editorTimelineOf, loadRecording } from './editorMedia';
import {
  forgetPrefetchLists,
  prefetchCardEditor,
  PREFETCH_PARALLEL,
  PREFETCH_VIDEO_TIMEOUT_MS,
  type PrefetchDeps,
} from './prefetchCardEditor';
import { SegmentsNotReadyError, type TrackPiece } from './trackAudio/loadTrackAudio';
import { useTrackPreview } from './trackAudio/useTrackPreview';
import { useEditorVideoPlayback } from './useEditorVideoPlayback';

vi.mock('@/api/clipEditor', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/clipEditor')>()),
  requestSegmentAudioAccess: vi.fn(),
  fetchAllJumpCards: vi.fn(),
  fetchBroadcast: vi.fn(),
}));
vi.mock('./editorMedia', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./editorMedia')>()),
  loadRecording: vi.fn(),
}));

// 카드의 미리 받기(POK-499): 편집기가 처음 받는 영상 창 · 소리 조각을 같은 주소로 받아 두고, 편집기는 그것을 다시 받지 않는다

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
const recordingOf = (seconds: number): RecordingTimeline => ({
  startMs: STARTED,
  durationSeconds: seconds,
  pieces: [{ fromSeconds: 0, toSeconds: seconds }],
});
const BUFFER = {
  duration: 50,
  length: 100,
  sampleRate: 100,
  numberOfChannels: 1,
  getChannelData: () => new Float32Array(100).fill(0.5),
} as unknown as AudioBuffer;
const TRACKS: TrackPiece[][] = [[{ startSeconds: 40, buffer: BUFFER }]];

function depsWith(overrides: Partial<PrefetchDeps> = {}): PrefetchDeps {
  return {
    fetchJumpCard: vi.fn(() => Promise.resolve(CARD)),
    fetchBroadcast: vi.fn(() => Promise.resolve(BROADCAST)),
    loadRecording: vi.fn(() => Promise.resolve(recordingOf(600))),
    fetchRecordingWindow: vi.fn(() => Promise.resolve(new Blob(['mp4'], { type: 'video/mp4' }))),
    loadSegmentTracks: vi.fn(() => Promise.resolve(TRACKS)),
    createDecoder: () => ({ decodeAudioData: vi.fn() }),
    ...overrides,
  };
}

describe('prefetchCardEditor', () => {
  beforeEach(() => {
    clearEditorMedia();
    forgetPrefetchLists();
  });

  it('녹화가 구간 끝 + 여유까지 올라왔으면 영상 창과 소리 조각을 받아 ready다', async () => {
    const deps = depsWith();
    await expect(prefetchCardEditor('7', '23', deps)).resolves.toBe('ready');
    // 구간 50~80초 ± 여유 10초
    const [, url] = vi.mocked(deps.fetchRecordingWindow).mock.calls[0]!;
    const asked = new URL(url, 'http://localhost');
    expect(Date.parse(asked.searchParams.get('start')!)).toBe(STARTED + 40_000);
    expect(Number(asked.searchParams.get('duration'))).toBe(50);
    expect(deps.loadSegmentTracks).toHaveBeenCalledWith(
      expect.objectContaining({ streamId: '7', fromMs: STARTED + 40_000, toMs: STARTED + 90_000 }),
    );
  });

  it('방송 중인데 녹화가 구간 끝 + 여유에 못 미치면 받지 않고 pending이다(방금 생긴 카드)', async () => {
    const deps = depsWith({ loadRecording: vi.fn(() => Promise.resolve(recordingOf(85))) });
    await expect(prefetchCardEditor('7', '23', deps)).resolves.toBe('pending');
    expect(deps.fetchRecordingWindow).not.toHaveBeenCalled();
    expect(deps.loadSegmentTracks).not.toHaveBeenCalled();
  });

  it('방송 중에 녹화가 아직 없으면 pending이다', async () => {
    const deps = depsWith({ loadRecording: vi.fn(() => Promise.resolve(null)) });
    await expect(prefetchCardEditor('7', '23', deps)).resolves.toBe('pending');
  });

  it('끝난 방송이면 녹화 끝에서 잘린 창도 그대로 받는다: 더 올라올 녹화가 없다', async () => {
    const deps = depsWith({
      fetchBroadcast: vi.fn(() =>
        Promise.resolve({ ...BROADCAST, endedAt: new Date(STARTED + 85_000).toISOString() }),
      ),
      loadRecording: vi.fn(() => Promise.resolve(recordingOf(85))),
    });
    await expect(prefetchCardEditor('7', '23', deps)).resolves.toBe('ready');
    expect(deps.fetchRecordingWindow).toHaveBeenCalledTimes(1);
  });

  it('영상이나 소리 하나라도 실패하면 던지고, 다시 부르면 처음부터 다시 받는다', async () => {
    const fetchRecordingWindow = vi
      .fn<PrefetchDeps['fetchRecordingWindow']>()
      .mockRejectedValueOnce(new Error('500'))
      .mockResolvedValue(new Blob(['mp4']));
    const deps = depsWith({ fetchRecordingWindow });
    await expect(prefetchCardEditor('7', '23', deps)).rejects.toThrow('500');
    await expect(prefetchCardEditor('7', '23', deps)).resolves.toBe('ready');
    expect(fetchRecordingWindow).toHaveBeenCalledTimes(2);
  });

  it('소리 트랙을 하나도 못 꺼내면 실패다: 편집기도 소리를 못 부른다', async () => {
    const deps = depsWith({ loadSegmentTracks: vi.fn(() => Promise.resolve([])) });
    await expect(prefetchCardEditor('7', '23', deps)).rejects.toThrow();
  });

  it('영상 창 받기가 멈추면 시한에 끊고 실패한다: 받기 자리를 쥔 채 끝나지 않으면 「편집」이 실패로도 못 넘어간다', async () => {
    vi.useFakeTimers();
    try {
      const deps = depsWith({
        fetchRecordingWindow: vi.fn(
          (_stream: string, _url: string, signal?: AbortSignal) =>
            new Promise<Blob>((_, reject) =>
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
    // 40~90초 창에서 40~60초만 왔다
    const partial: TrackPiece[][] = [[{ startSeconds: 40, buffer: { ...BUFFER, duration: 20 } }]];
    const deps = depsWith({ loadSegmentTracks: vi.fn(() => Promise.resolve(partial)) });
    await expect(prefetchCardEditor('7', '23', deps)).resolves.toBe('pending');
    // 맡기지 않았으니 다음 확인에서 다시 받는다
    await expect(prefetchCardEditor('7', '23', deps)).resolves.toBe('pending');
    expect(deps.loadSegmentTracks).toHaveBeenCalledTimes(2);
  });

  it('조각 사이가 비어 덩어리가 둘이어도 덜 덮은 것이다', async () => {
    const split: TrackPiece[][] = [
      [
        { startSeconds: 40, buffer: { ...BUFFER, duration: 20 } },
        { startSeconds: 64, buffer: { ...BUFFER, duration: 26 } },
      ],
    ];
    const deps = depsWith({ loadSegmentTracks: vi.fn(() => Promise.resolve(split)) });
    await expect(prefetchCardEditor('7', '23', deps)).resolves.toBe('pending');
  });

  it('끝난 방송이면 덜 덮은 소리도 그대로 맡긴다: 더 올라올 조각이 없다', async () => {
    const partial: TrackPiece[][] = [[{ startSeconds: 40, buffer: { ...BUFFER, duration: 20 } }]];
    const deps = depsWith({
      fetchBroadcast: vi.fn(() =>
        Promise.resolve({ ...BROADCAST, endedAt: new Date(STARTED + 600_000).toISOString() }),
      ),
      loadSegmentTracks: vi.fn(() => Promise.resolve(partial)),
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

describe('미리 받은 카드를 편집기가 열면 다시 받지 않는다', () => {
  const recording = recordingOf(600);
  const timeline = editorTimelineOf(BROADCAST, recording);
  const initialRange = { startSeconds: 50, endSeconds: 80 };
  let fetchSpy: ReturnType<typeof vi.fn>;

  beforeEach(async () => {
    clearEditorMedia();
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

  it('영상 창', () => {
    const { result } = renderHook(() =>
      useEditorVideoPlayback({
        streamId: '7',
        recordingStartMs: timeline.base,
        recordingSeconds: timeline.recordingEndSeconds,
        pieces: timeline.pieces,
        initialRange,
      }),
    );
    expect(fetchSpy).not.toHaveBeenCalled();
    expect(result.current.src).toBe('blob:prefetched');
    expect(result.current.window).toEqual({ startSeconds: 40, endSeconds: 90 });
  });

  it('소리 조각 · 파형', async () => {
    const { result } = renderHook(() =>
      useTrackPreview({
        streamId: '7',
        baseMs: timeline.base,
        window: { startSeconds: 40, endSeconds: 90 },
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

describe('조각 목록만 보고 판정하기', () => {
  beforeEach(() => {
    clearEditorMedia();
    forgetPrefetchLists();
  });

  /** 40~90초 창을 4초 조각으로: 앞의 count개만 올라왔다 */
  const segmentsUpTo = (count: number) =>
    Array.from({ length: count }, (_, i) => ({
      seq: i + 1,
      startAtMs: STARTED + 40_000 + i * 4_000,
      durationMs: 4_000,
      url: `https://s3/${i + 1}`,
    }));

  function depsAskingList(segments: ReturnType<typeof segmentsUpTo>) {
    const body = vi.fn();
    const loadSegmentTracks = vi.fn(
      async (options: Parameters<PrefetchDeps['loadSegmentTracks']>[0]) => {
        if (options.precheck && !options.precheck(segments)) throw new SegmentsNotReadyError();
        body();
        return TRACKS;
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

  it('조각 사이가 비면 덜 덮은 것이다', async () => {
    const gappy = segmentsUpTo(13).filter((segment) => segment.seq !== 6);
    const { body, deps } = depsAskingList(gappy);

    await expect(prefetchCardEditor('7', '23', deps)).resolves.toBe('pending');
    expect(body).not.toHaveBeenCalled();
  });

  it('목록이 구간을 다 덮으면 본문을 받아 ready다', async () => {
    const { body, deps } = depsAskingList(segmentsUpTo(13));

    await expect(prefetchCardEditor('7', '23', deps)).resolves.toBe('ready');
    expect(body).toHaveBeenCalledTimes(1);
  });

  it('끝난 방송은 미리 판정하지 않는다: 덜 덮어도 예전처럼 받는다', async () => {
    const { body, deps } = depsAskingList(segmentsUpTo(5));
    vi.mocked(deps.fetchBroadcast).mockResolvedValue({
      ...BROADCAST,
      endedAt: new Date(STARTED + 600_000).toISOString(),
    });

    await expect(prefetchCardEditor('7', '23', deps)).resolves.toBe('ready');
    expect(body).toHaveBeenCalledTimes(1);
    expect(vi.mocked(deps.loadSegmentTracks).mock.calls[0]![0].precheck).toBeUndefined();
  });
});

describe('카드 목록 · 녹화 목록 나눠 쓰기', () => {
  const CARD_B: JumpCard = { ...CARD, id: '24' };
  const CARD_NEW: JumpCard = { ...CARD, id: '99' };

  beforeEach(() => {
    clearEditorMedia();
    forgetPrefetchLists();
    vi.mocked(fetchAllJumpCards).mockReset().mockResolvedValue([CARD, CARD_B]);
    vi.mocked(fetchBroadcast).mockReset().mockResolvedValue(BROADCAST);
    // 녹화가 구간 끝에 못 미쳐 pending에서 멈춘다: 영상 · 소리는 받지 않고 목록 읽기만 본다
    vi.mocked(loadRecording).mockReset().mockResolvedValue(recordingOf(85));
  });

  afterEach(() => vi.useRealTimers());

  it('같은 방송의 카드 여럿이 카드 목록과 녹화 목록을 한 번씩만 읽는다', async () => {
    await Promise.all([prefetchCardEditor('7', '23'), prefetchCardEditor('7', '24')]);

    expect(fetchAllJumpCards).toHaveBeenCalledTimes(1);
    expect(loadRecording).toHaveBeenCalledTimes(1);
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

  it('방송 중 녹화 목록은 8초만 쓴다: 녹화 기다림의 다시 확인(10초)이 새 녹화 끝을 본다', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    await prefetchCardEditor('7', '23');
    vi.advanceTimersByTime(7_000);
    await prefetchCardEditor('7', '24');
    expect(loadRecording).toHaveBeenCalledTimes(1);

    vi.advanceTimersByTime(2_000);
    await prefetchCardEditor('7', '23');
    expect(loadRecording).toHaveBeenCalledTimes(2);
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
    expect(loadRecording).toHaveBeenCalledTimes(2);
  });
});
