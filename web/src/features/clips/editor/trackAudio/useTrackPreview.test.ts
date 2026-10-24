import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { StrictMode } from 'react';
import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { requestSegmentAudioAccess } from '@/api/clipEditor';
import { TRACK_LOAD_TIMEOUT_MS, useTrackPreview } from './useTrackPreview';

vi.mock('@/api/clipEditor', () => ({ requestSegmentAudioAccess: vi.fn() }));

const fixture = readFileSync(join(__dirname, '__fixtures__', 'three-tracks.m4s'));
// 소리 트랙 하나짜리(최종 믹스만 녹화한 방송). three-tracks.m4s와 같은 ffmpeg 명령에서 소리를 하나만 넣었다:
//   ffmpeg -f lavfi -i testsrc=size=64x36:rate=10:duration=1 -f lavfi -i sine=440:48000 -map 0:v -map 1:a -t 1
//          -c:v libx264 -g 5 -c:a aac -b:a 32k -ac 2 -f mp4 -movflags frag_keyframe+empty_moov+default_base_moof one-track.m4s
const oneTrack = readFileSync(join(__dirname, '__fixtures__', 'one-track.m4s'));
const BASE = 1_000_000;
const WINDOW = { startSeconds: 10, endSeconds: 40 };

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
      decodeAudioData: vi.fn(() =>
        Promise.resolve({
          duration: 1,
          length: 100,
          sampleRate: 100,
          numberOfChannels: 1,
          getChannelData: () => new Float32Array(100).fill(0.5),
        } as unknown as AudioBuffer),
      ),
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
  });

  it('영상이 받은 창의 조각을 묻고, 트랙마다 음량을 걸며, 따라가는 동안 영상 소리를 끈다', async () => {
    const { ctx, gains } = fakeContext();
    const node = video();
    const { result } = renderHook(() =>
      useTrackPreview({
        streamId: 's-1',
        baseMs: BASE,
        window: WINDOW,
        video: node,
        createContext: () => ctx as never,
      }),
    );
    act(() => result.current.setGains([0, 1.3, 0.6]));
    await waitFor(() => expect(result.current.status).toBe('ready'));

    expect(requestSegmentAudioAccess).toHaveBeenCalledWith('s-1', BASE + 10_000, BASE + 40_000);
    // 준비 전에 받은 음량도 준비되자마자 걸린다
    expect(gains.map((g) => g.gain.value)).toEqual([0, 1.3, 0.6]);
    act(() => result.current.setGains([1, 0, 0]));
    expect(gains.map((g) => g.gain.value)).toEqual([1, 0, 0]);
    expect(node.muted).toBe(true);
    // 타임라인 파형: 창(10~40초)을 0.05초 칸으로 잰다. 조각(10~11초) 자리는 소리가 있다
    expect(result.current.levels?.tracks).toHaveLength(3);
    expect(result.current.levels?.startSeconds).toBe(10);
    expect(result.current.levels?.tracks[0]![0]).toBeCloseTo(0.5);
  });

  it('배속 중에는 음량이 영상 소리와 같으면 영상 소리로 듣는다: 소리 노드 배속은 음높이까지 바꾼다(PR #341 2차)', async () => {
    const { ctx, gains } = fakeContext();
    const node = video();
    const { result } = renderHook(() =>
      useTrackPreview({
        streamId: 's-1',
        baseMs: BASE,
        window: WINDOW,
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
        baseMs: BASE,
        window: WINDOW,
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

  it('영상을 다 받기 전에도 조각을 받기 시작하고, 따라 틀기는 영상이 받은 창이 생기면 시작한다(PR #341 2차)', async () => {
    const { ctx } = fakeContext();
    const sources = vi.spyOn(ctx, 'createBufferSource');
    const node = Object.assign(video(), { paused: false, readyState: 4 });
    const { result, rerender } = renderHook(
      ({ playingFromSeconds }: { playingFromSeconds: number | null }) =>
        useTrackPreview({
          streamId: 's-1',
          baseMs: BASE,
          window: WINDOW,
          video: node,
          playingFromSeconds,
          createContext: () => ctx as never,
        }),
      { initialProps: { playingFromSeconds: null as number | null } },
    );
    // 영상 창이 아직 없어도 받을 창으로 조각을 묻는다
    await waitFor(() => expect(result.current.status).toBe('ready'));
    expect(requestSegmentAudioAccess).toHaveBeenCalledWith('s-1', BASE + 10_000, BASE + 40_000);
    // 영상이 아직 돌지 않으니(받은 창 없음) 소리를 걸지 않는다
    act(() => node.dispatchEvent(new Event('playing')));
    expect(sources).not.toHaveBeenCalled();

    // 영상이 창을 받아 돌기 시작하면 그 창으로 옮겨 따라 튼다
    rerender({ playingFromSeconds: 10 });
    expect(sources).toHaveBeenCalled();
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
        baseMs: BASE,
        window: WINDOW,
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
          baseMs: BASE,
          window: WINDOW,
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
        baseMs: BASE,
        window: WINDOW,
        video: node,
        createContext: () => fakeContext().ctx as never,
      }),
    );
    act(() => result.current.setGains([0, 1, 0]));
    expect(result.current.status).toBe('loading');
    expect(node.muted).toBe(true);
    finish({ expiresAt: '', segments: [] });
    // 소리 트랙을 못 꺼냈다(빈 조각) → 못 부름 → 영상 소리로 돌아간다
    await waitFor(() => expect(result.current.status).toBe('unavailable'));
    expect(node.muted).toBe(false);
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
        baseMs: BASE,
        window: WINDOW,
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

  it('창을 다시 받는 동안 앞 창 재생기가 이어 튼다: 새 재생기가 준비되면 그때 앞 것을 버린다(PR #341 claude)', async () => {
    const { ctx, gains } = fakeContext();
    const node = video();
    const { result, rerender } = renderHook(
      ({ window }) =>
        useTrackPreview({
          streamId: 's-1',
          baseMs: BASE,
          window,
          video: node,
          createContext: () => ctx as never,
        }),
      { initialProps: { window: WINDOW } },
    );
    act(() => result.current.setGains([0, 1, 0]));
    await waitFor(() => expect(result.current.status).toBe('ready'));
    const firstPlayerGains = [...gains];
    expect(firstPlayerGains).toHaveLength(3);

    let finish: (value: unknown) => void = () => {};
    vi.mocked(requestSegmentAudioAccess).mockReturnValueOnce(
      new Promise((resolve) => {
        finish = resolve;
      }) as never,
    );
    rerender({ window: { startSeconds: 5, endSeconds: 45 } });
    expect(result.current.status).toBe('loading');
    // 앞 재생기가 그대로 소리를 내니 영상 소리는 꺼 둔 채다. 파형도 그대로 둔다
    expect(node.muted).toBe(true);
    expect(result.current.levels).not.toBeNull();
    for (const gain of firstPlayerGains) expect(gain.disconnect).not.toHaveBeenCalled();

    finish({
      expiresAt: '',
      segments: [
        { seq: 1, startAtMs: BASE + 10_000, durationMs: 1000, url: 'https://s3/seg_1.m4s' },
      ],
    });
    await waitFor(() => expect(result.current.status).toBe('ready'));
    expect(gains).toHaveLength(6);
    for (const gain of firstPlayerGains) expect(gain.disconnect).toHaveBeenCalled();
    expect(result.current.levels?.startSeconds).toBe(5);
  });

  it('창을 다시 받다 실패하면 이어 틀던 앞 재생기도 버리고 영상 소리로 돌아간다: 꺼진 채 무음으로 남지 않게(PR #341 2차)', async () => {
    const { ctx, gains } = fakeContext();
    const node = video();
    const { result, rerender } = renderHook(
      ({ window }) =>
        useTrackPreview({
          streamId: 's-1',
          baseMs: BASE,
          window,
          video: node,
          createContext: () => ctx as never,
        }),
      { initialProps: { window: WINDOW } },
    );
    act(() => result.current.setGains([0, 1, 0]));
    await waitFor(() => expect(result.current.status).toBe('ready'));
    const firstPlayerGains = [...gains];

    vi.mocked(requestSegmentAudioAccess).mockRejectedValueOnce(new Error('503'));
    rerender({ window: { startSeconds: 5, endSeconds: 45 } });
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
          baseMs: BASE,
          window: WINDOW,
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
          baseMs: BASE,
          window: WINDOW,
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
      ({ window }) =>
        useTrackPreview({
          streamId: 's-1',
          baseMs: BASE,
          window,
          video: node,
          createContext: () => fakeContext().ctx as never,
        }),
      { initialProps: { window: WINDOW } },
    );
    await waitFor(() => expect(signals).toHaveLength(1));
    rerender({ window: { startSeconds: 20, endSeconds: 50 } });
    expect(signals[0]!.aborted).toBe(true);
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
        baseMs: BASE,
        window: WINDOW,
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
        baseMs: BASE,
        window: WINDOW,
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
        baseMs: BASE,
        window: WINDOW,
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
        baseMs: BASE,
        window: WINDOW,
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
        baseMs: BASE,
        window: WINDOW,
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
        baseMs: BASE,
        window: null,
        video: node,
        createContext: () => fakeContext().ctx as never,
      }),
    );
    expect(result.current.status).toBe('idle');
    expect(requestSegmentAudioAccess).not.toHaveBeenCalled();
    expect(node.muted).toBe(false);
  });
});
