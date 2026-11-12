import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { requestSegmentAudioAccess, type SegmentAudioAccess } from '@/api/clipEditor';
import { useAuthStore } from '@/stores/auth';
import { loadSegmentTracks, TRACK_LOAD_TIMEOUT_MS } from '../trackAudio/useTrackPreview';
import type { PlaylistSegment } from './editorPlaylist';
import { ensureWindowHeads } from './editorWindow';
import { headCacheFor } from './fileHeadProbe';
import { windowAudioAccess } from './windowAudioAccess';

// 창 소리 주소 응답을 ② 머리 판독과 트랙 미리 듣기가 나눠 쓴다(설계 prw2 5절 4 · c9′ r1 9번 → c10b′). 판 NULL 창이면 둘이
// 같은 창 범위를 함께 물었다 — 같은 범위를 묻는 중이면 그 응답을 같이 기다린다.

vi.mock('@/api/clipEditor', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/clipEditor')>()),
  requestSegmentAudioAccess: vi.fn(),
}));

const fixture = readFileSync(
  join(__dirname, '..', 'trackAudio', '__fixtures__', 'three-tracks.m4s'),
);
const BASE = 1_000_000;

const segment = (seq: number): PlaylistSegment => ({
  seq,
  uri: `https://cdn/seg/${seq}.m4s`,
  mapUri: 'https://cdn/init.mp4',
  pdtMs: BASE + seq * 1000,
  durationMs: 1000,
  timing: null,
  gap: false,
});

const ACCESS: SegmentAudioAccess = {
  expiresAt: '2026-10-23T00:05:00Z',
  segments: [1, 2].map((seq) => ({
    seq,
    startAtMs: BASE + seq * 1000,
    durationMs: 1000,
    url: `https://s3/seg_${seq}.m4s`,
  })),
};

/** 응답을 손으로 푸는 요청 — 묻는 중인 동안을 시험이 정한다 */
function pendingAccess() {
  let resolve: (access: SegmentAudioAccess) => void = () => undefined;
  vi.mocked(requestSegmentAudioAccess).mockImplementation(
    () =>
      new Promise((r) => {
        resolve = r;
      }),
  );
  return (access: SegmentAudioAccess) => resolve(access);
}

describe('windowAudioAccess', () => {
  beforeEach(() => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve(new Response(new Uint8Array(fixture)))),
    );
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.mocked(requestSegmentAudioAccess).mockReset();
  });

  it('같은 창 범위를 묻는 중이면 그 응답을 같이 기다린다', async () => {
    const answer = pendingAccess();
    const segs = [segment(1), segment(2)];

    const a = windowAudioAccess('s-1', segs);
    const b = windowAudioAccess('s-1', segs);
    answer(ACCESS);

    await expect(Promise.all([a, b])).resolves.toEqual([ACCESS, ACCESS]);
    expect(requestSegmentAudioAccess).toHaveBeenCalledTimes(1);
    expect(requestSegmentAudioAccess).toHaveBeenCalledWith('s-1', BASE + 1000, BASE + 3000);
  });

  it('응답이 온 뒤에 다시 물으면 새로 묻는다: 서명 주소는 5분짜리라 다시 묻기(만료 · 기다림 고리)는 새 응답이어야 한다', async () => {
    vi.mocked(requestSegmentAudioAccess).mockResolvedValue(ACCESS);
    const segs = [segment(1), segment(2)];

    await windowAudioAccess('s-1', segs);
    await windowAudioAccess('s-1', segs);

    expect(requestSegmentAudioAccess).toHaveBeenCalledTimes(2);
  });

  it('범위나 방송이 다르면 따로 묻는다', async () => {
    vi.mocked(requestSegmentAudioAccess).mockResolvedValue(ACCESS);

    await Promise.all([
      windowAudioAccess('s-1', [segment(1)]),
      windowAudioAccess('s-1', [segment(1), segment(2)]),
      windowAudioAccess('s-2', [segment(1)]),
    ]);

    expect(requestSegmentAudioAccess).toHaveBeenCalledTimes(3);
  });

  it('실패도 같이 받고, 실패한 요청은 남기지 않는다', async () => {
    vi.mocked(requestSegmentAudioAccess).mockRejectedValueOnce(new Error('503'));
    vi.mocked(requestSegmentAudioAccess).mockResolvedValueOnce(ACCESS);
    const segs = [segment(1)];

    await expect(
      Promise.all([windowAudioAccess('s-1', segs), windowAudioAccess('s-1', segs)]),
    ).rejects.toThrow('503');
    await expect(windowAudioAccess('s-1', segs)).resolves.toBe(ACCESS);
  });

  it('계정이 바뀌면 묻는 중인 앞 계정 요청을 같이 쓰지 않는다: 새 계정 권한으로 새로 묻는다(c10b′ r1 1번)', async () => {
    const answerA = pendingAccess();
    const segs = [segment(1), segment(2)];
    const a = windowAudioAccess('s-1', segs);

    useAuthStore.getState().clearTokens();
    const answerB = pendingAccess();
    const b = windowAudioAccess('s-1', segs);
    const OTHER: SegmentAudioAccess = { ...ACCESS, expiresAt: '2026-10-23T00:09:00Z' };
    answerB(OTHER);
    answerA(ACCESS);

    expect(requestSegmentAudioAccess).toHaveBeenCalledTimes(2);
    await expect(b).resolves.toBe(OTHER);
    await expect(a).resolves.toBe(ACCESS);
  });

  it('계정이 바뀐 뒤 앞 계정 요청이 끝나도 새 계정의 묻는 중인 요청은 그대로 같이 쓴다', async () => {
    const answerA = pendingAccess();
    const segs = [segment(1), segment(2)];
    const a = windowAudioAccess('s-1', segs);
    useAuthStore.getState().clearTokens();
    const answerB = pendingAccess();
    const b = windowAudioAccess('s-1', segs);

    answerA(ACCESS);
    await a;
    const again = windowAudioAccess('s-1', segs);
    answerB(ACCESS);

    await expect(Promise.all([b, again])).resolves.toEqual([ACCESS, ACCESS]);
    expect(requestSegmentAudioAccess).toHaveBeenCalledTimes(2);
  });

  it('묻던 쪽이 멈추면(signal) 그 요청을 같이 쓰지 않는다: 같은 범위를 다시 물으면 새로 묻는다(c10b′ r1 4번)', async () => {
    pendingAccess();
    const segs = [segment(1), segment(2)];
    const stop = new AbortController();
    const stalled = windowAudioAccess('s-1', segs, stop.signal);

    stop.abort();

    await expect(stalled).rejects.toThrow();
    vi.mocked(requestSegmentAudioAccess).mockResolvedValue(ACCESS);
    await expect(windowAudioAccess('s-1', segs)).resolves.toBe(ACCESS);
    expect(requestSegmentAudioAccess).toHaveBeenCalledTimes(2);
  });

  it('소리 주소 요청이 끝나지 않아 트랙 받기가 시한에 멈추면, 그 창을 다시 받을 때 새로 묻는다', async () => {
    vi.useFakeTimers();
    try {
      vi.mocked(requestSegmentAudioAccess).mockImplementation(() => new Promise(() => undefined));
      const segs = [segment(1), segment(2)];
      const options = {
        streamId: 's-1',
        segs,
        anchors: async () => new Map([[1, 0]]),
        decode: vi.fn(),
      };
      const stalled = loadSegmentTracks(options);
      stalled.catch(() => undefined);

      await vi.advanceTimersByTimeAsync(TRACK_LOAD_TIMEOUT_MS + 10);
      await expect(stalled).rejects.toThrow('track audio load stopped');
      const leave = new AbortController();
      void loadSegmentTracks({ ...options, signal: leave.signal }).catch(() => undefined);
      await vi.advanceTimersByTimeAsync(10);

      expect(requestSegmentAudioAccess).toHaveBeenCalledTimes(2);
      // 다음 시험이 멈춘 요청을 같이 쓰지 않게 떠난다
      leave.abort();
    } finally {
      vi.useRealTimers();
    }
  });

  it('판 NULL 창의 ② 머리 판독과 트랙 소리 받기를 함께 시작하면 소리 주소는 한 번만 묻는다', async () => {
    vi.mocked(requestSegmentAudioAccess).mockResolvedValue(ACCESS);
    const segs = [segment(1), segment(2)];
    const decode = vi.fn(() =>
      Promise.resolve({ duration: 1, length: 1, numberOfChannels: 1 } as AudioBuffer),
    );

    await Promise.all([
      ensureWindowHeads('s-1', 'shared-key', segs),
      loadSegmentTracks({
        streamId: 's-1',
        segs,
        anchors: async () =>
          new Map([
            [1, 0],
            [2, 1],
          ]),
        decode,
      }),
    ]);

    expect(requestSegmentAudioAccess).toHaveBeenCalledTimes(1);
    expect(headCacheFor('shared-key').get(1)?.state).toBe('ready');
  });
});
