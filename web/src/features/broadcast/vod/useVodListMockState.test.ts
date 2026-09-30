import { renderHook, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { withToastProvider } from '@/test/testProviders';
import { useVodListMockState } from './useVodListMockState';

// 지난 방송 목록의 「다시 보기 가능」 판정(POK-233). 녹화 목록은 영상 경로 전체라 같은 키를 나눠 쓰는 다른 방송 녹화도 섞여 온다.

const HOUR = 3_600_000;
const MINE_START = Date.parse('2026-09-29T10:00:00Z');

const api = vi.hoisted(() => ({
  spans: [] as { startMs: number; durationSeconds: number }[],
  asked: [] as string[],
}));
vi.mock('@/api/mediaPlayback', () => ({
  playbackConfigured: () => true,
  fetchRecordingSpans: (key: string) => {
    api.asked.push(key);
    return Promise.resolve(api.spans);
  },
}));
vi.mock('@/api/clipEditor', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/clipEditor')>()),
  fetchAllJumpCards: () => Promise.resolve([]),
  fetchAllBroadcasts: () =>
    Promise.resolve([
      {
        streamId: 'S-mine',
        ingestStreamId: 'key-shared',
        status: 'ended',
        relation: 'OWNER',
        startedAt: new Date(MINE_START).toISOString(),
        endedAt: new Date(MINE_START + HOUR).toISOString(),
        vodExpiresAt: null,
      },
      {
        streamId: 'S-other',
        ingestStreamId: 'key-shared',
        status: 'ended',
        relation: 'OWNER',
        startedAt: new Date(MINE_START - 24 * HOUR).toISOString(),
        endedAt: new Date(MINE_START - 23 * HOUR).toISOString(),
        vodExpiresAt: null,
      },
    ]),
}));

afterEach(() => {
  api.spans = [];
  api.asked = [];
});

describe('useVodListMockState — 녹화 있음 판정', () => {
  it('같은 키의 다른 방송 녹화만 있으면 「다시 보기 가능」으로 올리지 않는다 — 열면 빈 화면이다', async () => {
    api.spans = [{ startMs: MINE_START - 24 * HOUR, durationSeconds: 1800 }];
    const { result } = renderHook(() => useVodListMockState(), { wrapper: withToastProvider });
    await waitFor(() => expect(result.current.totalCount).toBe(2));
    const mine = result.current.broadcasts.find((b) => b.streamId === 'S-mine');
    const other = result.current.broadcasts.find((b) => b.streamId === 'S-other');
    expect(mine?.status).toBe('ended');
    expect(other?.status).toBe('vod_ready');
  });

  it('제 방송 시간과 겹치는 녹화가 있으면 「다시 보기 가능」이다', async () => {
    api.spans = [{ startMs: MINE_START + 60_000, durationSeconds: 1800 }];
    const { result } = renderHook(() => useVodListMockState(), { wrapper: withToastProvider });
    await waitFor(() =>
      expect(result.current.broadcasts.find((b) => b.streamId === 'S-mine')?.status).toBe(
        'vod_ready',
      ),
    );
  });

  it('같은 키를 나눠 쓰는 방송 둘은 녹화 목록을 한 번만 묻는다 — 답이 같다(PR #208 codex)', async () => {
    const { result } = renderHook(() => useVodListMockState(), { wrapper: withToastProvider });
    await waitFor(() => expect(result.current.totalCount).toBe(2));
    expect(api.asked).toEqual(['key-shared']);
  });
});
