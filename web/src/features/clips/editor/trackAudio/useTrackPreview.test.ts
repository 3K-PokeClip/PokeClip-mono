import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { StrictMode } from 'react';
import { renderHook, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { requestSegmentAudioAccess } from '@/api/clipEditor';
import { useTrackPreview } from './useTrackPreview';

vi.mock('@/api/clipEditor', () => ({ requestSegmentAudioAccess: vi.fn() }));

const fixture = readFileSync(join(__dirname, '__fixtures__', 'three-tracks.m4s'));
const BASE = 1_000_000;
const WINDOW = { startSeconds: 10, endSeconds: 40 };

function fakeContext() {
  const gains: { gain: { value: number } }[] = [];
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
    result.current.setGains([0, 1.3, 0.6]);
    await waitFor(() => expect(result.current.status).toBe('ready'));

    expect(requestSegmentAudioAccess).toHaveBeenCalledWith('s-1', BASE + 10_000, BASE + 40_000);
    // 준비 전에 받은 음량도 준비되자마자 걸린다
    expect(gains.map((g) => g.gain.value)).toEqual([0, 1.3, 0.6]);
    result.current.setGains([1, 0, 0]);
    expect(gains.map((g) => g.gain.value)).toEqual([1, 0, 0]);
    expect(node.muted).toBe(true);
    // 타임라인 파형: 창(10~40초)을 0.05초 칸으로 잰다. 조각(10~11초) 자리는 소리가 있다
    expect(result.current.levels?.tracks).toHaveLength(3);
    expect(result.current.levels?.startSeconds).toBe(10);
    expect(result.current.levels?.tracks[0]![0]).toBeCloseTo(0.5);
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

  it('주소를 못 받거나 소리 트랙이 하나뿐이면 못 부름이다', async () => {
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

    // 소리 트랙 하나짜리 조각
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
});
