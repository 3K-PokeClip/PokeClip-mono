import { act, renderHook, waitFor } from '@testing-library/react';
import type { QueryClient } from '@tanstack/react-query';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createTestQueryClient, makeQueryWrapper } from '@/test/testProviders';

// 라이브 상태 훅의 데이터 층(POK-251 정비): 끝난 방송의 굳은 차트 · 방송 정보를 React Query 캐시에서 다시 쓰고, 같은 카드 · 같은
// 카드 시각은 다시 싣지 않는다. 서버 응답은 apiFetch를 가로채 흉내 낸다
const api = vi.hoisted(() => ({
  calls: [] as string[],
}));
vi.mock('@/api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/client')>();
  const json = (body: unknown) =>
    new Response(JSON.stringify(body), { headers: { 'Content-Type': 'application/json' } });
  return {
    ...actual,
    apiFetch: async (path: string) => {
      api.calls.push(path);
      const { pathname } = new URL(path, 'http://localhost');
      if (pathname.endsWith('/jump-cards')) return json({ cards: [] });
      if (pathname.endsWith('/chat-chart'))
        return json({
          bucketSeconds: 30,
          appliedOffsetMs: 0,
          buckets: [{ start: '2026-10-31T00:00:00.000Z', chats: 3, donations: 0 }],
        });
      if (pathname.endsWith('/broadcast-info')) return json({ latest: null, series: [] });
      // 그 밖(이벤트 통로 · 연동 · 시점 기록)은 없다: 훅은 다음 주기에 다시 묻는다
      throw new Error(`unexpected ${pathname}`);
    },
  };
});

import {
  publishLiveData,
  resetLiveData,
  useLiveField,
} from '@/features/broadcast/livenow/liveDataStore';
import { useLiveMockState, type LiveCardSnapshot } from './useLiveMockState';

const count = (suffix: string) =>
  api.calls.filter((p) => new URL(p, 'http://localhost').pathname.endsWith(suffix)).length;

function useUnderTest() {
  return { state: useLiveMockState(true), chart: useLiveField('chart') };
}

describe('useLiveMockState: 끝난 방송의 캐시', () => {
  let queryClient: QueryClient;
  beforeEach(() => {
    api.calls.length = 0;
    resetLiveData();
    queryClient = createTestQueryClient();
  });

  const endedAt = (agoMs: number) => {
    const end = Date.now() - agoMs;
    // 2시간 방송이라 차트는 1시간 창 둘이다
    publishLiveData({
      streamId: 's1',
      status: 'ended',
      startedAt: end - 2 * 3_600_000,
      endedAt: end,
    });
  };

  async function enter() {
    const rendered = renderHook(useUnderTest, { wrapper: makeQueryWrapper(queryClient) });
    await waitFor(() => expect(rendered.result.current.chart).not.toBeNull());
    return rendered;
  }

  it('끝난 지 오래된 방송은 다시 들어와도 차트 창과 방송 정보를 다시 읽지 않는다', async () => {
    endedAt(60 * 60_000);
    const first = await enter();
    await waitFor(() => expect(count('/broadcast-info')).toBe(1));
    expect(count('/chat-chart')).toBe(2);
    first.unmount();

    const second = await enter();
    // 읽기가 더 있었다면 이 사이에 나갔다
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 100));
    });
    expect(second.result.current.chart?.buckets).toHaveLength(2);
    expect(count('/chat-chart')).toBe(2);
    expect(count('/broadcast-info')).toBe(1);
  });

  it('끝난 지 ENDED_SETTLE_MS(2분)가 안 지났으면 캐시하지 않는다: 수집기가 꼬리를 아직 쓸 수 있다', async () => {
    endedAt(30_000);
    const first = await enter();
    await waitFor(() => expect(count('/broadcast-info')).toBe(1));
    expect(count('/chat-chart')).toBe(2);
    first.unmount();

    await enter();
    await waitFor(() => expect(count('/broadcast-info')).toBe(2));
    expect(count('/chat-chart')).toBe(4);
  });
});

describe('useLiveMockState: 카드 시각', () => {
  beforeEach(() => {
    api.calls.length = 0;
    resetLiveData();
    publishLiveData({ streamId: 's1', status: 'live', startedAt: Date.now() - 600_000 });
  });

  const card = (over: Partial<LiveCardSnapshot> = {}): LiveCardSnapshot => ({
    id: 1,
    streamId: 's1',
    source: 'auto',
    streamTimestampMs: 5_000,
    window: { startMs: 0, endMs: 10_000 },
    score: 1,
    evidence: null,
    eventSeq: 1,
    createdAt: '2026-10-31T00:00:00.000Z',
    ...over,
  });

  function mount() {
    const queryClient = createTestQueryClient();
    return renderHook(
      () => ({ state: useLiveMockState(false), times: useLiveField('cardTimes') }),
      { wrapper: makeQueryWrapper(queryClient) },
    );
  }

  it('카드 시각이 그대로면 저장소에 다시 싣지 않는다: 새 배열은 구독자를 깨운다', async () => {
    const { result } = mount();
    await waitFor(() => expect(result.current.state.cardsStatus).toBe('loaded'));
    act(() => result.current.state.mergeCard(card()));
    await waitFor(() => expect(result.current.times).toHaveLength(1));
    const first = result.current.times;

    // 내용이 같은 카드: 상태가 그대로다
    act(() => result.current.state.mergeCard(card()));
    expect(result.current.times).toBe(first);

    // 점수가 바뀐 카드(순번이 오른다): 카드는 바뀌지만 카드 시각은 같다
    act(() => result.current.state.mergeCard(card({ score: 9, eventSeq: 2 })));
    await waitFor(() => expect(result.current.state.highlights[0]?.score).toBe(9));
    expect(result.current.times).toBe(first);

    // 시각이 바뀐 카드는 새 값이다
    act(() => result.current.state.mergeCard(card({ streamTimestampMs: 8_000, eventSeq: 3 })));
    await waitFor(() => expect(result.current.times).not.toBe(first));
  });
});
