import { renderHook, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { requestSegmentAudioAccess, type BroadcastRow, type JumpCard } from '@/api/clipEditor';
import type { RecordingTimeline } from '@/features/player/recordingTimeline';
import { clearEditorMedia, editorTimelineOf } from './editorMedia';
import { prefetchCardEditor, PREFETCH_PARALLEL, type PrefetchDeps } from './prefetchCardEditor';
import type { TrackPiece } from './trackAudio/loadTrackAudio';
import { useTrackPreview } from './trackAudio/useTrackPreview';
import { useEditorVideoPlayback } from './useEditorVideoPlayback';

vi.mock('@/api/clipEditor', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/clipEditor')>()),
  requestSegmentAudioAccess: vi.fn(),
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
  duration: 1,
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
  beforeEach(() => clearEditorMedia());

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

  it('같은 카드를 받는 중에 또 부르면 그 받기를 같이 기다린다', async () => {
    const deps = depsWith();
    const [a, b] = [prefetchCardEditor('7', '23', deps), prefetchCardEditor('7', '23', deps)];
    expect(a).toBe(b);
    await a;
    expect(deps.fetchJumpCard).toHaveBeenCalledTimes(1);
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
