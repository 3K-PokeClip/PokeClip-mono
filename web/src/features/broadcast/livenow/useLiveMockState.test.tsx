import { act, render, renderHook, screen, waitFor } from '@testing-library/react';
import type { QueryClient } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createTestQueryClient, makeQueryWrapper } from '@/test/testProviders';

// 라이브 상태 훅의 데이터 층(POK-251 정비): 끝난 방송의 굳은 차트 · 방송 정보를 React Query 캐시에서 다시 쓰고, 같은 카드 · 같은
// 카드 시각은 다시 싣지 않는다. 서버 응답은 apiFetch를 가로채 흉내 낸다
const api = vi.hoisted(() => ({
  calls: [] as string[],
  /** 이벤트 통로가 보낼 글. null이면 통로가 없다(붙지 못한다) */
  sse: null as string | null,
}));
vi.mock('@/api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/client')>();
  const json = (body: unknown) =>
    new Response(JSON.stringify(body), { headers: { 'Content-Type': 'application/json' } });
  return {
    ...actual,
    apiFetch: async (path: string, init?: RequestInit) => {
      api.calls.push(path);
      const { pathname } = new URL(path, 'http://localhost');
      if (pathname.endsWith('/jump-cards')) return json({ cards: [] });
      if (pathname.endsWith('/chat-chart'))
        return json({
          bucketSeconds: 30,
          appliedOffsetMs: 0,
          buckets: [{ start: '2026-10-31T00:00:00.000Z', chats: 3, donations: 0 }],
        });
      // 통로를 시험할 때는 정기 조회가 답하지 않는다: 통로로 온 방송 정보만 남는다
      if (pathname.endsWith('/broadcast-info'))
        return api.sse !== null
          ? new Promise<Response>(() => {})
          : json({ latest: null, series: [] });
      if (pathname.endsWith('/events') && api.sse !== null) {
        const text = api.sse;
        // 보낸 뒤 끊지 않는다(끊으면 5초 뒤 다시 붙는다). 화면이 내려가면 닫는다
        return new Response(
          new ReadableStream<Uint8Array>({
            start(controller) {
              controller.enqueue(new TextEncoder().encode(text));
              init?.signal?.addEventListener('abort', () => controller.close());
            },
          }),
        );
      }
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
import { sameSnapshot, useLiveMockState, type LiveCardSnapshot } from './useLiveMockState';

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

  it('서명만 바뀐 사진은 1분 안에는 같은 카드다: 서버가 목록마다 새로 서명해 글자로 비교하면 사진 있는 카드는 늘 바뀐 카드가 된다', () => {
    const signed = (photo: string, at: string) =>
      `https://s3.example/${photo}.jpg?X-Amz-Date=${at}&X-Amz-Signature=sig`;
    const now = Date.parse('2026-10-31T00:00:30Z');
    const shown = card({ thumbnailUrl: signed('a', '20261031T000000Z') });

    expect(sameSnapshot(shown, card({ thumbnailUrl: signed('a', '20261031T000030Z') }), now)).toBe(
      true,
    );
    // 다른 사진 · 사진이 없어진 카드는 다른 카드다
    expect(sameSnapshot(shown, card({ thumbnailUrl: signed('b', '20261031T000030Z') }), now)).toBe(
      false,
    );
    expect(sameSnapshot(shown, card({ thumbnailUrl: null }), now)).toBe(false);
    // 1분이 지나면 새 주소를 받는다: 못 받은 사진은 새 주소가 와야 다시 시도한다
    const later = Date.parse('2026-10-31T00:01:00Z');
    expect(
      sameSnapshot(shown, card({ thumbnailUrl: signed('a', '20261031T000100Z') }), later),
    ).toBe(false);
    expect(sameSnapshot(card(), card(), now)).toBe(true);
  });
});

describe('useLiveMockState: 통로로 온 방송 정보', () => {
  beforeEach(() => {
    api.calls.length = 0;
    resetLiveData();
    publishLiveData({ streamId: 's1', status: 'live', startedAt: Date.now() - 600_000 });
  });
  afterEach(() => {
    api.sse = null;
  });

  // 라이브 화면처럼 저장소를 구독하는 바깥 화면(LiveScreenBody) 안에 훅을 쓰는 화면(LiveDashboard)이 있다
  function Dashboard() {
    useLiveMockState(false);
    return null;
  }
  function Body() {
    const info = useLiveField('info');
    return (
      <>
        <output>{info?.series.map((p) => p.viewers).join(',') ?? ''}</output>
        <Dashboard />
      </>
    );
  }

  it('그리기 밖에서 저장소에 싣는다: 상태 갱신 함수 안에서 실으면 구독하는 다른 화면을 그리기 중에 깨운다', async () => {
    const errors = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    const ev = {
      time: new Date(Date.now() - 5_000).toISOString(),
      title: '제목',
      tags: [],
      category: 'talk',
      viewers: 0,
      platform: 'CHZZK',
    };
    api.sse = `event: broadcast-info\ndata: ${JSON.stringify(ev)}\n\n`;
    const { unmount } = render(<Body />, {
      wrapper: makeQueryWrapper(createTestQueryClient()),
    });
    // 0명도 값이다
    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('0'));
    unmount();
    expect(
      errors.mock.calls.some((args) => String(args[0]).includes('Cannot update a component')),
    ).toBe(false);
    errors.mockRestore();
  });
});
