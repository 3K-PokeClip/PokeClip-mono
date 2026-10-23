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
  /** 출입증 받기와 녹화 목록 묻기를 부른 순서(POK-317) */
  order: [] as string[],
  /** 목록 끝에 더할 방송 줄 */
  extra: [] as Record<string, unknown>[],
  /** 목록·카드 읽기에 넘긴 끊기 신호 */
  signals: [] as (AbortSignal | undefined)[],
}));
vi.mock('@/api/mediaPlayback', () => ({
  playbackConfigured: () => true,
  onPlaybackForbidden: () => undefined,
  lookupRecordingSpans: (key: string) => {
    api.asked.push(key);
    api.order.push(`list:${key}`);
    return Promise.resolve(api.spans);
  },
}));
vi.mock('@/api/playbackAccess', () => ({
  ensurePlaybackAccessForList: (items: { streamId: string; mediaKey: string }[]) => {
    api.order.push(`access:${items.map((i) => i.mediaKey).join(',')}`);
    // 출입증 답이 늦게 온다: 목록이 그 답을 기다리는지 잰다
    const answer = new Promise<{ kind: 'granted'; expiresAtMs: null }>((resolve) =>
      setTimeout(() => {
        api.order.push('access-answered');
        resolve({ kind: 'granted', expiresAtMs: null });
      }, 0),
    );
    return new Map(items.map((i) => [i.mediaKey, answer]));
  },
}));
vi.mock('@/api/clipEditor', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/clipEditor')>()),
  fetchAllJumpCards: (_streamId: string, signal?: AbortSignal) => {
    api.signals.push(signal);
    return Promise.resolve([]);
  },
  fetchAllBroadcasts: (_state: string, _streamerId: number | null, signal?: AbortSignal) => {
    api.signals.push(signal);
    return Promise.resolve([
      {
        streamId: 'S-mine',
        ingestStreamId: 'key-shared',
        status: 'ended',
        relation: 'OWNER',
        startedAt: new Date(MINE_START).toISOString(),
        endedAt: new Date(MINE_START + HOUR).toISOString(),
        vodExpiresAt: null,
        title: '새벽 랭크',
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
      ...api.extra,
    ]);
  },
}));

afterEach(() => {
  api.spans = [];
  api.asked = [];
  api.order = [];
  api.extra = [];
  api.signals = [];
});

describe('useVodListMockState — 내려갈 때', () => {
  it('읽던 목록·카드를 끊는다: 채널을 바꿔 다시 그려지면 옛 채널 요청을 이어 가지 않는다(PR #342 2차 리뷰)', async () => {
    const { result, unmount } = renderHook(() => useVodListMockState(), {
      wrapper: withToastProvider,
    });
    await waitFor(() => expect(result.current.totalCount).toBe(2));
    expect(api.signals.length).toBeGreaterThan(1);
    expect(api.signals.every((s) => s !== undefined && !s.aborted)).toBe(true);

    unmount();

    expect(api.signals.every((s) => s?.aborted)).toBe(true);
  });
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

  it('녹화 목록을 묻기 전에 확인할 방송들의 출입증을 한 번에 받는다: CDN 뒤 녹화 목록은 쿠키가 없으면 403이다(POK-317)', async () => {
    const { result } = renderHook(() => useVodListMockState(), { wrapper: withToastProvider });
    await waitFor(() => expect(result.current.totalCount).toBe(2));
    expect(api.order).toEqual([
      'access:key-shared,key-shared',
      'access-answered',
      'list:key-shared',
    ]);
  });

  it('행 제목은 치지직 제목이고, 제목이 없는 방송은 번호다(POK-259)', async () => {
    const { result } = renderHook(() => useVodListMockState(), { wrapper: withToastProvider });
    await waitFor(() => expect(result.current.visuals['S-mine']?.title).toBe('새벽 랭크'));
    expect(result.current.visuals['S-other']?.title).toBe('S-other');
  });

  it('시작 시각 없는 방송은 끝 12시간 밖의 다른 날 녹화로 「다시 보기 가능」이 되지 않는다: 다시보기와 같은 하한이다', async () => {
    // 다시보기만 하한을 좁혀, 목록은 열어 주는데 들어가면 「녹화 영상을 찾지 못했어요」였다(리뷰 6라운드)
    api.extra = [
      {
        streamId: 'S-placeholder',
        ingestStreamId: 'key-shared',
        status: 'ended',
        relation: 'OWNER',
        startedAt: null,
        endedAt: new Date(MINE_START + 3 * HOUR).toISOString(),
        vodExpiresAt: null,
      },
    ];
    api.spans = [{ startMs: MINE_START - 24 * HOUR, durationSeconds: 1800 }];
    const { result } = renderHook(() => useVodListMockState(), { wrapper: withToastProvider });
    await waitFor(() => expect(result.current.totalCount).toBe(3));
    expect(result.current.broadcasts.find((b) => b.streamId === 'S-placeholder')?.status).toBe(
      'ended',
    );
  });
});
